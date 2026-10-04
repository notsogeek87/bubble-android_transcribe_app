package dev.notune.transcribe.update

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.lielu.githubupdater.UpdateError
import com.lielu.githubupdater.UpdateState
import dev.notune.transcribe.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Affiche les fenêtres de mise à jour dans une activité (équivalent View de `UpdatePrompt`) et branche le
 * bouton « Rechercher une mise à jour » des Paramètres. À appeler une fois dans `onCreate`.
 */
object UpdateUi {
    @JvmStatic
    fun attach(
        activity: androidx.appcompat.app.AppCompatActivity,
        checkButton: Button,
    ) {
        val controller = UpdateController.get(activity)
        if (!controller.updatesEnabled) {
            checkButton.visibility = View.GONE
            return
        }
        checkButton.setOnClickListener { controller.checkManually() }
        activity.lifecycle.addObserver(UpdatePrompt(activity, controller, checkButton))
    }
}

private class UpdatePrompt(
    private val activity: Activity,
    private val controller: UpdateController,
    private val checkButton: Button,
) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var dialog: AlertDialog? = null
    private var progressIndicator: LinearProgressIndicator? = null

    /** État dont dépend la fenêtre affichée : évite de la recréer quand seul un drapeau annexe change. */
    private var shownState: UpdateState? = null

    // ON_START : au lancement ET quand l'app, restée en mémoire, repasse au premier plan.
    override fun onStart(owner: LifecycleOwner) {
        controller.checkOnOpen()
        job =
            scope.launch {
                launch {
                    combine(controller.state, controller.dismissed, controller.userStarted) { s, d, u -> Triple(s, d, u) }
                        .collect { (s, dismissed, userStarted) -> render(s, dismissed, userStarted) }
                }
                launch {
                    controller.upToDateNotice.collect {
                        Snackbar.make(activity.findViewById(android.R.id.content), R.string.update_up_to_date, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
    }

    override fun onStop(owner: LifecycleOwner) {
        job?.cancel()
        job = null
        closeDialog()
    }

    private fun render(
        state: UpdateState,
        dismissed: Boolean,
        userStarted: Boolean,
    ) {
        val busy = state is UpdateState.Checking || state is UpdateState.Downloading
        checkButton.isEnabled = !busy
        checkButton.setText(if (state is UpdateState.Checking) R.string.update_checking else R.string.btn_check_update)

        if (dismissed) {
            closeDialog()
            return
        }
        if (state !is UpdateState.Downloading && dialog?.isShowing == true && shownState == state) return
        when (state) {
            is UpdateState.UpdateAvailable ->
                show(
                    state = state,
                    title = R.string.update_available_title,
                    message =
                        steps(
                            activity.getString(R.string.update_available_intro, state.update.versionName),
                            R.string.update_step_download,
                            R.string.update_step_allow,
                            R.string.update_step_confirm,
                        ),
                    positive = R.string.update_install to { controller.onInstall(state.update) },
                    negative = R.string.update_later,
                )
            is UpdateState.Downloading -> showProgress((state.progress.percentage ?: 0))
            is UpdateState.Downloaded ->
                show(
                    state = state,
                    title = R.string.update_downloaded_title,
                    message =
                        if (controller.canInstallPackages()) {
                            steps(activity.getString(R.string.update_downloaded_intro), R.string.update_step_install_confirm)
                        } else {
                            steps(
                                activity.getString(R.string.update_permission_intro),
                                R.string.update_permission_step_open,
                                R.string.update_permission_step_enable,
                                R.string.update_permission_step_retry,
                            )
                        },
                    positive = R.string.update_install to { controller.onInstallDownloaded(state.file) },
                    negative = R.string.update_later,
                )
            is UpdateState.Error ->
                if (userStarted) {
                    show(
                        state = state,
                        title = R.string.update_error_title,
                        message = errorMessage(state.error),
                        positive = android.R.string.ok to { controller.onDismiss() },
                        negative = null,
                    )
                } else {
                    closeDialog()
                }
            else -> closeDialog()
        }
    }

    private fun steps(
        intro: String,
        vararg steps: Int,
    ): String =
        buildString {
            append(intro)
            steps.forEachIndexed { i, id -> append("\n\n${i + 1}. ").append(activity.getString(id)) }
            append("\n\n").append(activity.getString(R.string.update_data_kept))
        }

    private fun show(
        state: UpdateState,
        title: Int,
        message: String,
        positive: Pair<Int, () -> Unit>,
        negative: Int?,
    ) {
        closeDialog()
        val builder =
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive.first) { _, _ -> positive.second() }
                .setOnCancelListener { controller.onDismiss() }
        if (negative != null) builder.setNegativeButton(negative) { _, _ -> controller.onDismiss() }
        dialog = builder.show()
        shownState = state
    }

    private fun showProgress(percent: Int) {
        val indicator = progressIndicator
        if (indicator != null && dialog?.isShowing == true) {
            indicator.setProgressCompat(percent, true)
            return
        }
        closeDialog()
        val bar = LinearProgressIndicator(activity).apply { max = 100; setProgressCompat(percent, false) }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val container =
            LinearLayout(activity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        progressIndicator = bar
        dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_downloading_title)
                .setView(container)
                .setCancelable(false)
                .show()
    }

    private fun closeDialog() {
        dialog?.dismiss()
        dialog = null
        progressIndicator = null
        shownState = null
    }

    private fun errorMessage(error: UpdateError): String =
        when (error) {
            is UpdateError.NetworkError -> activity.getString(R.string.update_error_network)
            is UpdateError.RateLimit -> activity.getString(R.string.update_error_rate_limit)
            UpdateError.ReleaseNotFound -> activity.getString(R.string.update_error_no_release)
            is UpdateError.ApkNotFound -> activity.getString(R.string.update_error_no_apk)
            UpdateError.InstallationNotAllowed -> activity.getString(R.string.update_error_not_allowed)
            else -> activity.getString(R.string.update_error_generic, error.message)
        }
}
