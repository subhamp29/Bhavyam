package com.bhavya.music.data.update

/**
 * Transient, deliberately unpersisted state for a detected update. The only
 * durable facts are the release metadata (re-fetched) and the in-flight
 * DownloadManager id (persisted by `AppUpdateManager`, so a process death
 * mid-transfer can be resumed).
 *
 * Every non-idle state carries the [version] it refers to, and that is
 * load-bearing: a release check can discover a newer tag while an older APK is
 * already on disk, and without the version the UI would claim "ready to
 * install" for a file that was never downloaded.
 */
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    /**
     * [totalBytes] is 0 or -1 while the size is still unknown, so UI must
     * render an indeterminate bar rather than dividing by it.
     */
    data class Downloading(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateInstallState

    data class ReadyToInstall(val version: String) : UpdateInstallState

    data class Failed(val version: String, val reason: UpdateFailure) : UpdateInstallState
}

enum class UpdateFailure(val isRetryable: Boolean) {
    Network(isRetryable = true),

    /**
     * The downloaded APK is not signed by the key Bhavya is currently running
     * under, so the installer would fail with
     * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and is never opened. Not retryable:
     * re-downloading the identical asset cannot change the answer.
     */
    SignatureMismatch(isRetryable = false),

    /**
     * The downloaded APK is not actually newer than the installed one. Almost
     * always a `versionCode` that was not bumped alongside `versionName`.
     */
    NotNewer(isRetryable = false),

    /** The per-app "install unknown apps" grant is missing or was revoked. */
    InstallPermissionMissing(isRetryable = false),

    Unknown(isRetryable = true),
}

/** The release version this state refers to, or null for [UpdateInstallState.Idle]. */
val UpdateInstallState.version: String?
    get() = when (this) {
        is UpdateInstallState.Idle -> null
        is UpdateInstallState.Downloading -> version
        is UpdateInstallState.ReadyToInstall -> version
        is UpdateInstallState.Failed -> version
    }

val UpdateInstallState.isDownloading: Boolean get() = this is UpdateInstallState.Downloading

/**
 * 0-100, or null when the total size is not known yet — a `total == 0`
 * division would otherwise produce NaN.
 */
val UpdateInstallState.progressPercent: Int?
    get() {
        val downloading = this as? UpdateInstallState.Downloading ?: return null
        val total = downloading.totalBytes
        if (total <= 0L) return null
        return ((downloading.downloadedBytes * 100L) / total).toInt().coerceIn(0, 100)
    }