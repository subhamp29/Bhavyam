package com.bhavya.music.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bhavya.music.R
import com.bhavya.music.data.update.UpdateFailure
import com.bhavya.music.data.update.UpdateInstallState
import com.bhavya.music.data.update.isDownloading
import com.bhavya.music.data.update.progressPercent

/**
 * Presentation for [UpdateInstallState], shared by the MainShell banner and
 * the Settings "Check for Updates" row so the two surfaces always describe the
 * same download the same way. Every mapping the update UI needs lives here.
 *
 * @param canInstallInApp false when the release ships no .apk asset, in which
 *   case the only honest thing to show is the release notes -- offering an
 *   in-app install button would promise something the flow cannot deliver.
 */
@Composable
fun updateInstallSubtitle(
    installState: UpdateInstallState,
    version: String,
    canInstallInApp: Boolean,
): String {
    if (!canInstallInApp) {
        return stringResource(R.string.update_ready_to_install, version)
    }
    return when (installState) {
        is UpdateInstallState.Idle -> stringResource(R.string.update_ready_to_install, version)
        is UpdateInstallState.Downloading ->
            // Unknown total size gets its own wording instead of a bogus 0%.
            installState.progressPercent?.let { percent ->
                stringResource(R.string.update_downloading, percent)
            } ?: stringResource(R.string.update_downloading_unknown_size)
        is UpdateInstallState.ReadyToInstall ->
            stringResource(R.string.update_downloaded_tap_to_install)
        is UpdateInstallState.Failed -> stringResource(
            when (installState.reason) {
                UpdateFailure.Network -> R.string.update_error_network
                UpdateFailure.SignatureMismatch -> R.string.update_error_signature
                UpdateFailure.NotNewer -> R.string.update_error_not_newer
                UpdateFailure.InstallPermissionMissing -> R.string.update_permission_required
                UpdateFailure.Unknown -> R.string.update_error_unknown
            },
        )
    }
}

@Composable
fun updateActionLabel(
    installState: UpdateInstallState,
    canInstallInApp: Boolean,
): String = when {
    !canInstallInApp -> stringResource(R.string.update_button_download)
    installState is UpdateInstallState.ReadyToInstall ->
        stringResource(R.string.update_button_install)
    installState is UpdateInstallState.Failed &&
        installState.reason == UpdateFailure.InstallPermissionMissing ->
        stringResource(R.string.update_button_allow)
    // A non-retryable failure will not be fixed by another download; the tap
    // falls through to the browser, so the label must not promise a retry.
    installState is UpdateInstallState.Failed && !installState.reason.isRetryable ->
        stringResource(R.string.update_button_download)
    installState is UpdateInstallState.Failed -> stringResource(R.string.update_button_retry)
    else -> stringResource(R.string.update)
}

/** Reads the shared install state so the title can never claim "Ready" while the subtitle reports a failure. */
@Composable
fun updateRowTitle(
    isUpdateAvailable: Boolean,
    installState: UpdateInstallState,
    version: String,
): String = when {
    !isUpdateAvailable -> stringResource(R.string.settings_check_updates)
    installState is UpdateInstallState.Failed -> stringResource(R.string.update_title_failed)
    installState is UpdateInstallState.Downloading -> stringResource(R.string.update_title_downloading)
    else -> stringResource(R.string.update_title_ready, version)
}

/** A running transfer owns the action: re-tapping would enqueue a second download into the same destination. */
fun updateActionEnabled(installState: UpdateInstallState): Boolean = !installState.isDownloading