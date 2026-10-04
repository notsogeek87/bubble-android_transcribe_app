package dev.notune.transcribe.update

import android.content.Context
import com.lielu.githubupdater.UpdateConfig
import com.lielu.githubupdater.UpdateInfo
import com.lielu.githubupdater.UpdateManager
import com.lielu.githubupdater.UpdateState
import dev.notune.transcribe.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Vérifie les mises à jour à chaque ouverture de l'app et pilote la fenêtre qui les propose.
 * L'état brut est celui de [UpdateManager]. Une seule instance par processus, pour que l'état
 * (téléchargement en cours, fenêtre refusée) survive aux changements de configuration.
 */
class UpdateController private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val updateManager =
        UpdateManager(
            appContext,
            UpdateConfig(githubOwner = GITHUB_OWNER, githubRepository = GITHUB_REPOSITORY),
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** `false` pour un applicationId à suffixe (ex. `.staging`) : sa signature/son id ne correspondent pas à la release. */
    val updatesEnabled: Boolean = appContext.packageName == BuildConfig.BASE_APPLICATION_ID

    val state: StateFlow<UpdateState> = updateManager.state

    private val _dismissed = MutableStateFlow(false)

    /** `true` une fois « Plus tard » touché : la fenêtre ne revient qu'à la prochaine vérification. */
    val dismissed: StateFlow<Boolean> = _dismissed

    private val _userStarted = MutableStateFlow(false)

    /** `true` dès que l'utilisateur a agi (« Installer », bouton des Paramètres) : seulement alors on lui montre une erreur. */
    val userStarted: StateFlow<Boolean> = _userStarted

    private val _upToDateNotice = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Émis quand une vérification demandée à la main conclut « à jour » (aucune fenêtre à montrer). */
    val upToDateNotice: SharedFlow<Unit> = _upToDateNotice

    /** `false` tant qu'Android n'a pas autorisé l'app à installer des applications (étape à expliquer). */
    fun canInstallPackages(): Boolean = updateManager.canInstallPackages()

    /**
     * Appelée à chaque passage de l'app au premier plan (ON_START). `force = true` : sans cela la bibliothèque
     * réutilise sa réponse précédente (« à jour ») jusqu'à `checkIntervalHours`, et une release publiée entre-temps
     * n'est pas vue. Une requête GitHub par ouverture reste très en dessous du quota (60/h).
     */
    fun checkOnOpen() {
        if (!updatesEnabled) return
        // Ne pas écraser une fenêtre déjà affichée, un téléchargement ou une installation en cours.
        val s = state.value
        if (s !is UpdateState.Idle && s !is UpdateState.UpToDate && s !is UpdateState.Error) return
        // Nouvelle vérification : un « Plus tard » ou une erreur précédents ne doivent pas masquer le résultat.
        _dismissed.value = false
        _userStarted.value = false
        launchCheck(manual = false)
    }

    /** Bouton « Rechercher une mise à jour » des Paramètres : même vérification forcée, avec retour à l'utilisateur. */
    fun checkManually() {
        if (!updatesEnabled) return
        when (state.value) {
            // Déjà en cours : rien à relancer.
            UpdateState.Checking, is UpdateState.Downloading -> return
            // Déjà trouvée : on remontre simplement la fenêtre.
            is UpdateState.UpdateAvailable, is UpdateState.Downloaded -> {
                _dismissed.value = false
                return
            }
            else -> Unit
        }
        _dismissed.value = false
        _userStarted.value = true
        launchCheck(manual = true)
    }

    private fun launchCheck(manual: Boolean) {
        scope.launch {
            // Les erreurs (hors ligne, quota GitHub…) sont aussi publiées dans `state` ; ici on reste silencieux.
            val result = runCatching { updateManager.checkForUpdate(force = true) }
            if (manual && result.isSuccess && result.getOrNull() == null) _upToDateNotice.tryEmit(Unit)
        }
    }

    fun onDismiss() {
        _dismissed.value = true
    }

    fun onInstall(update: UpdateInfo) {
        _userStarted.value = true
        scope.launch {
            runCatching {
                val apk = updateManager.downloadUpdate(update)
                install(apk)
            }
        }
    }

    /** Relance l'installation d'un APK déjà téléchargé, typiquement au retour des réglages Android. */
    fun onInstallDownloaded(apk: File) {
        _userStarted.value = true
        runCatching { install(apk) }
    }

    private fun install(apk: File) {
        if (updateManager.canInstallPackages()) {
            updateManager.installUpdate(apk)
        } else {
            updateManager.openInstallPermissionSettings()
        }
    }

    companion object {
        // Dépôt GitHub de CETTE app (celui dont les releases contiennent l'APK), pas celui de la bibliothèque.
        private const val GITHUB_OWNER = "notsogeek87"
        private const val GITHUB_REPOSITORY = "bubble-android_transcribe_app"

        @Volatile
        private var instance: UpdateController? = null

        @JvmStatic
        fun get(context: Context): UpdateController =
            instance ?: synchronized(this) {
                instance ?: UpdateController(context).also { instance = it }
            }
    }
}
