import java.io.FileInputStream
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numéro de build croissant : fourni par la CI (BUILD_NUMBER = github.run_number). Il devient le versionCode
// (Android refuse d'installer par-dessus un versionCode non supérieur) et le dernier segment du versionName,
// pour que lielugit-updater compare correctement le tag `v<version>` à la version installée.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: providers.gradleProperty("buildNumber").orNull)?.toIntOrNull() ?: 1
val appVersionBase = providers.gradleProperty("appVersionBase").get()
val versionCodeOffset = providers.gradleProperty("versionCodeOffset").get().toInt()
val baseApplicationId = "dev.notune.transcribe"

android {
    namespace = "dev.notune.transcribe"
    compileSdk = 35

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeOffset + buildNumber
        versionName = "$appVersionBase.$buildNumber"
        // Les mises à jour ne sont actives que pour l'applicationId d'origine : un flavor avec
        // applicationIdSuffix (ex. .staging) ne peut pas être mis à jour depuis la release de production.
        buildConfigField("String", "BASE_APPLICATION_ID", "\"$baseApplicationId\"")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Clé privée : en CI elle vient des secrets du dépôt (KEYSTORE_BASE64,
        // STORE_PASS, KEY_ALIAS, KEY_PASS). Personne d'autre ne peut donc signer
        // un APK qu'Android accepterait comme mise à jour de celui-ci.
        create("release") {
            val ksFile = rootProject.file("release.keystore")
            if (ksFile.exists()) {
                fun secret(name: String) = System.getenv(name)
                    ?: throw GradleException("Variable d'environnement $name manquante pour signer avec release.keystore")
                storeFile = ksFile
                storePassword = secret("STORE_PASS")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Source sets — the Rust-built .so files land in jniLibs via cargo-ndk
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false          // extractNativeLibs=false (16KB safe)
            keepDebugSymbols += "**/*.so"
        }
    }

    // Play Asset Delivery: large model files go into a separate asset pack
    // so the base module stays under the 200 MB Play Store limit.
    assetPacks += listOf(":model_assets")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

// For APK builds (assemble/install), asset packs are ignored by AGP so we
// must include the asset-pack assets as an extra source directory.  For
// bundle builds the asset pack module handles delivery and we must NOT add
// the directory here (would cause duplicate-resource errors).
val isBundle = gradle.startParameter.taskNames.any {
    it.contains("bundle", ignoreCase = true)
}
if (!isBundle) {
    android.sourceSets.getByName("main") {
        assets.srcDirs(
            "src/main/assets",
            rootProject.file("model_assets/src/main/assets")
        )
    }
}

dependencies {
    // Material Components (Material 3 / Material You). Pulls in AppCompat.
    implementation("com.google.android.material:material:1.12.0")

    // Mises à jour automatiques depuis les releases GitHub (dépôt Maven vendoré dans libs/lielugit-maven).
    implementation("com.lielu:lielugit-updater:1.0.0")
    // Dispatchers.Main pour l'UI de mise à jour (la bibliothèque n'expose que coroutines-core).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Material/AppCompat transitively pull the legacy kotlin-stdlib-jdk7/jdk8:1.6.21
    // (via kotlinx-coroutines-android), whose classes were folded into
    // kotlin-stdlib in Kotlin 1.8 — causing duplicate-class build failures.
    // Align them with the resolved kotlin-stdlib (1.8.22), where they are empty
    // stubs. See https://kotlinlang.org/docs/whatsnew18.html#kotlin-stdlib
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}

// ---------------------------------------------------------------------------
// Rust / cargo-ndk build task
// ---------------------------------------------------------------------------

val cargoNdkBuild by tasks.registering(Exec::class) {
    description = "Build Rust native code via cargo-ndk"
    group = "build"

    workingDir = rootProject.projectDir   // Cargo.toml lives at project root

    // Detect NDK path from local.properties or env
    val ndkDir = project.findProperty("ndk.dir")?.toString()
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK")
        ?: android.ndkDirectory.absolutePath

    environment("ANDROID_NDK_HOME", ndkDir)
    // transcribe-cpp-sys builds its C++ core through CMake, whose Android
    // platform detection needs one of these (ANDROID_NDK_HOME is not enough).
    environment("ANDROID_NDK_ROOT", ndkDir)
    environment("ANDROID_NDK", ndkDir)
    // ggml cannot autodetect the CPU when cross-compiling and falls back to
    // baseline armv8-a, losing the dotprod/fp16 kernels its quantized matmuls
    // rely on (several times slower). armv8.2-a+dotprod+fp16 is supported by
    // arm64 phones from ~2018 on; the engine refuses older CPUs with a clear
    // error at load (see check_cpu_features in src/engine.rs) instead of
    // crashing mid-inference.
    environment("TRANSCRIBE_CMAKE_ARGS", "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16")

    val jniLibsDir = project.file("src/main/jniLibs")

    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
    )

    // Copy libc++_shared.so from NDK (needed because Rust links against it dynamically)
    doLast {
        val ndkPath = environment["ANDROID_NDK_HOME"] as String
        val libcpp = file("$ndkPath/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
        if (libcpp.exists()) {
            val destDir = File(jniLibsDir, "arm64-v8a")
            destDir.mkdirs()
            libcpp.copyTo(File(destDir, "libc++_shared.so"), overwrite = true)
            println("Copied libc++_shared.so from NDK")
        } else {
            throw GradleException("libc++_shared.so not found in NDK at: ${libcpp.absolutePath}")
        }
    }

    outputs.dir(jniLibsDir)
    // No input tracking — always run and let cargo's own incremental build
    // decide what to recompile (a no-op cargo invocation is fast). Without
    // this, Gradle sees unchanged outputs and skips Rust rebuilds entirely.
    outputs.upToDateWhen { false }
}

// Wire the cargo-ndk build into the Android build lifecycle
tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}

// ---------------------------------------------------------------------------
// Model asset download task
// ---------------------------------------------------------------------------

data class ModelFile(val name: String, val sha256: String)

// The bundled GGUF goes into the model_assets asset pack so the base module
// stays under the Play Store 200 MB compressed-download limit.
val modelPackFiles = listOf(
    ModelFile("parakeet-tdt-0.6b-v3-Q4_K_M.gguf",
        "b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181"),
)

val huggingFaceRepo = "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/main"

fun downloadToDir(assetsDir: File, files: List<ModelFile>) {
    assetsDir.mkdirs()
    files.forEach { model ->
        val destFile = File(assetsDir, model.name)
        if (destFile.exists() && model.sha256.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(destFile).use { fis ->
                val buf = ByteArray(8192)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash == model.sha256) {
                println("  ✓ ${model.name} already downloaded and verified")
                return@forEach
            } else {
                println("  ✗ ${model.name} checksum mismatch, re-downloading...")
                destFile.delete()
            }
        }

        if (!destFile.exists()) {
            println("  ↓ Downloading ${model.name}...")
            val downloadUrl = "$huggingFaceRepo/${model.name}?download=true"
            val proc = ProcessBuilder("curl", "-L", "-f", "-o", destFile.absolutePath, downloadUrl)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                throw GradleException("Failed to download ${model.name} (curl exit code $exitCode)")
            }

            if (model.sha256.isNotEmpty()) {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(destFile).use { fis ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        digest.update(buf, 0, read)
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != model.sha256) {
                    throw GradleException(
                        "Checksum verification failed for ${model.name}:\n" +
                        "  Expected: ${model.sha256}\n" +
                        "  Got:      $hash"
                    )
                }
                println("  ✓ ${model.name} verified")
            }
        }
    }
}

val downloadModels by tasks.registering {
    description = "Download the built-in speech model (GGUF)"
    group = "build"

    // The GGUF -> asset pack (separate install-time delivery)
    val packAssetsDir = rootProject.file("model_assets/src/main/assets/builtin-model")

    outputs.dir(packAssetsDir)

    doLast {
        downloadToDir(packAssetsDir, modelPackFiles)
    }
}

tasks.named("preBuild") {
    dependsOn(downloadModels)
}
