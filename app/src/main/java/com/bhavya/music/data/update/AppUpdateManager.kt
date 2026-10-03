package com.bhavya.music.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val isChecking: Boolean = false,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "",
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    /** URL of the release's .apk asset, or null when the release has none. */
    val downloadUrl: String? = null,
    val isDismissed: Boolean = false,
    val message: String? = null,
    val installState: UpdateInstallState = UpdateInstallState.Idle,
    /**
     * Epoch millis the release was published, or null when GitHub omitted the
     * field. This -- not the version string -- is the ordering key for update
     * detection, so the version scheme can be renumbered freely.
     */
    val publishedAt: Long? = null,
) {
    /** True only when a self-installable APK is actually available. */
    val canInstallInApp: Boolean get() = downloadUrl != null
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("bhavya_updates", Context.MODE_PRIVATE)

    private val _updateInfo = MutableStateFlow(
        UpdateInfo(
            currentVersion = getCurrentVersion(),
        )
    )
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    private var progressJob: Job? = null

    /**
     * DownloadManager has no completion callback reliably delivered to
     * manifest-declared receivers on modern Android, so this receiver is
     * registered dynamically and lives for the process lifetime. [PREF_DOWNLOAD_ID]
     * is what ties an incoming broadcast to our transfer.
     */
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == -1L || prefs.getLong(PREF_DOWNLOAD_ID, -1L) != id) return
            val version = prefs.getString(PREF_DOWNLOAD_VERSION, null) ?: return
            // Broadcasts arrive on the main thread; only the cheap prefs read happens here.
            scope.launch { settleDownload(id, version) }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                context,
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { error ->
            android.util.Log.w("AppUpdateManager", "Download receiver unavailable", error)
        }
        // Hilt builds this singleton on the main thread during
        // MainActivity.onCreate, so the state restores below must not run inline.
        scope.launch {
            clearStaleApkOnVersionChange()
            restorePendingDownload()
        }
        checkForUpdate(isSilent = true)
    }

    fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: FALLBACK_VERSION
    } catch (_: Exception) {
        FALLBACK_VERSION
    }

    /** Cheap by construction: [isThrottled] short-circuits before any coroutine exists. */
    fun onAppForegrounded() {
        if (_updateInfo.value.isChecking) return
        checkForUpdate(isSilent = true)
    }

    /**
     * @param force skip the throttle. True only for the explicit
     *   "Check for Updates" row, where the user asked and expects a live answer.
     */
    fun checkForUpdate(isSilent: Boolean = false, force: Boolean = false) {
        if (!force && isThrottled()) return
        // Stamp before launching so two foregrounds racing each other cannot
        // both pass the throttle check in the same frame.
        stampCheck(THROTTLE_SUCCESS_MS)
        scope.launch {
            _updateInfo.update {
                it.copy(
                    isChecking = true,
                    message = if (!isSilent) "Checking for updates..." else it.message,
                )
            }
            try {
                val request = Request.Builder()
                    .url("$GITHUB_RELEASES_URL/latest")
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "Bhavya-Android")
                    .build()

                val response = withContext(Dispatchers.IO) {
                    okHttpClient.newCall(request).execute()
                }

                if (!response.isSuccessful) {
                    val code = response.code
                    stampCheck(THROTTLE_FAILURE_MS)
                    _updateInfo.update {
                        it.copy(
                            isChecking = false,
                            message = if (!isSilent) "Could not check updates (HTTP $code)" else null,
                        )
                    }
                    return@launch
                }

                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "")
                val releaseUrl = json.optString("html_url", FALLBACK_RELEASE_PAGE)
                val releaseNotes = json.optString("body", "")

                var downloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i)
                        val name = asset?.optString("name", "").orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset?.optString("browser_download_url")
                            break
                        }
                    }
                }

                val currentVersion = getCurrentVersion()
                val cleanTag = tagName.removePrefix("v").removePrefix("V")
                val publishedAt = json.optString("published_at", "")
                    .takeIf { it.isNotBlank() }
                    ?.toEpochMillisOrNull()

                // Ordering key is the publish date, not the version string, so
                // the scheme can be renumbered (4.2.2 -> 1.0) without stranding
                // installs that are already on a higher-looking version. The
                // version only has to *differ* from ours, which also stops a
                // fresh install of the newest build being offered itself.
                val lastSeenPublishedAt = prefs.getLong(PREF_LAST_SEEN_PUBLISHED_AT, 0L)
                val hasNewer = if (publishedAt != null) {
                    publishedAt > lastSeenPublishedAt && cleanTag != currentVersion
                } else {
                    // GitHub omitted published_at: fall back to version ordering.
                    isNewerVersion(tagName, currentVersion)
                }
                val isDismissed = publishedAt != null &&
                    lastSeenPublishedAt > 0L &&
                    publishedAt <= lastSeenPublishedAt

                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        isUpdateAvailable = hasNewer,
                        latestVersion = cleanTag,
                        currentVersion = currentVersion,
                        releaseNotes = releaseNotes,
                        releaseUrl = releaseUrl,
                        downloadUrl = downloadUrl,
                        isDismissed = isDismissed,
                        publishedAt = publishedAt,
                        // Any pending download belongs to whichever release we
                        // were looking at a moment ago; keeping it would let an
                        // older APK masquerade as "ready to install" for a
                        // freshly discovered newer tag.
                        installState = if (hasNewer && it.installState.version == cleanTag) {
                            it.installState
                        } else {
                            UpdateInstallState.Idle
                        },
                        message = if (!isSilent) {
                            if (hasNewer) "New version $cleanTag available!" else "You're on the latest version ($currentVersion)"
                        } else null,
                    )
                }
            } catch (e: Exception) {
                stampCheck(THROTTLE_FAILURE_MS)
                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        message = if (!isSilent) "Check failed: ${e.message ?: "Network error"}" else null,
                    )
                }
            }
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Enqueue the release APK through DownloadManager: no permission is
     * involved, the transfer outlives this process, and the system notification
     * keeps the user informed after they leave the app.
     *
     * Never auto-starts on detection -- only reached from a tap.
     */
    fun startDownload() {
        val snapshot = _updateInfo.value
        val apkUrl = snapshot.downloadUrl
        val version = snapshot.latestVersion
        if (apkUrl.isNullOrBlank() || version.isBlank()) {
            setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
            return
        }

        scope.launch {
            progressJob?.cancel()
            val target = updateApkFile(version)
            if (target == null) {
                setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
                return@launch
            }
            // One APK on disk at most. A superseded version can never be
            // installed, and leaving the target in place would make
            // DownloadManager fail with ERROR_FILE_ALREADY_EXISTS.
            clearStoredApks()

            val request = runCatching {
                DownloadManager.Request(Uri.parse(apkUrl)).apply {
                    setTitle("Bhavyam $version")
                    setDescription("Downloading update")
                    setMimeType(APK_MIME)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                    )
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(false)
                    setDestinationInExternalFilesDir(
                        context,
                        null,
                        "$UPDATES_DIR/${target.name}",
                    )
                }
            }.getOrNull()
            if (request == null) {
                setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Network))
                return@launch
            }

            val id = withContext(Dispatchers.IO) {
                runCatching { downloadManager()?.enqueue(request) }.getOrNull()
            }
            if (id == null) {
                setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Network))
                return@launch
            }

            prefs.edit()
                .putLong(PREF_DOWNLOAD_ID, id)
                .putString(PREF_DOWNLOAD_VERSION, version)
                .apply()
            setInstallState(UpdateInstallState.Downloading(version, 0L, 0L))
            pollProgress(id, version)
        }
    }

    fun retryUpdate() = startDownload()

    /**
     * DownloadManager exposes no progress callback, so byte counts are polled.
     * Two hazards are handled explicitly:
     *
     *  - **Clobbering a terminal state.** The completion broadcast settles the
     *    download on its own coroutine, so an iteration that already read a
     *    non-terminal snapshot can still be in flight when that happens. Every
     *    write is gated on the job still being active; without that the UI is
     *    left saying "Downloading" forever with the download id already cleared
     *    and no way back.
     *  - **Never terminating.** `STATUS_PENDING` and `STATUS_PAUSED` are not
     *    terminal, so a download paused on a lost network would spin a
     *    ContentProvider query at 2 Hz for the life of the process. The
     *    interval backs off while nothing moves, and an absolute ceiling
     *    cancels the transfer and surfaces a retryable error.
     */
    private fun pollProgress(id: Long, version: String) {
        progressJob?.cancel()
        progressJob = scope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var lastBytes = -1L
            var interval = PROGRESS_POLL_MS

            while (isActive) {
                val snapshot = withContext(Dispatchers.IO) { queryDownload(id) }
                if (!isActive) return@launch

                if (snapshot == null) {
                    // The row is gone: pruned, or removed by the user.
                    clearDownloadRecord()
                    setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
                    return@launch
                }

                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL,
                    DownloadManager.STATUS_FAILED -> {
                        settleDownload(id, version)
                        return@launch
                    }
                    else -> {
                        // Bytes moving => a real transfer, so poll briskly again.
                        interval = if (snapshot.downloaded != lastBytes) {
                            lastBytes = snapshot.downloaded
                            PROGRESS_POLL_MS
                        } else {
                            (interval * 2).coerceAtMost(PROGRESS_POLL_MAX_MS)
                        }

                        if (android.os.SystemClock.elapsedRealtime() - startedAt > PROGRESS_DEADLINE_MS) {
                            // Genuinely wedged. Cancel it so nothing is
                            // orphaned in DownloadManager, and let the user retry.
                            clearDownloadRecord()
                            withContext(Dispatchers.IO) {
                                runCatching { downloadManager()?.remove(id) }
                            }
                            setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Network))
                            return@launch
                        }

                        setInstallState(
                            UpdateInstallState.Downloading(
                                version = version,
                                downloadedBytes = snapshot.downloaded,
                                totalBytes = snapshot.total,
                            ),
                        )
                        delay(interval)
                    }
                }
            }
        }
    }

    private suspend fun settleDownload(id: Long, version: String) {
        progressJob?.cancel()
        val snapshot = queryDownload(id)
        clearDownloadRecord()
        if (snapshot?.status != DownloadManager.STATUS_SUCCESSFUL) {
            setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Network))
            return
        }
        val file = updateApkFile(version)
        if (file == null || !file.isFile || file.length() <= 0L) {
            setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
            return
        }
        setInstallState(UpdateInstallState.ReadyToInstall(version))
    }

    /**
     * Re-attach to a transfer that outlived its process. A row that has since
     * failed or vanished is dropped silently and the state left
     * [UpdateInstallState.Idle], so a fresh launch never greets the user with an
     * error about a download they never started this session.
     */
    private suspend fun restorePendingDownload() {
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        val version = prefs.getString(PREF_DOWNLOAD_VERSION, null)
        if (id == -1L || version.isNullOrBlank()) return
        val snapshot = queryDownload(id)
        if (snapshot == null ||
            snapshot.status == DownloadManager.STATUS_FAILED ||
            snapshot.status == DownloadManager.STATUS_SUCCESSFUL
        ) {
            clearDownloadRecord()
            if (snapshot?.status == DownloadManager.STATUS_SUCCESSFUL) {
                settleDownload(id, version)
            }
            return
        }
        pollProgress(id, version)
    }

    // ── Verify + install ──────────────────────────────────────────────────────

    /**
     * Hand the verified APK to the system package installer. Android never
     * installs silently, so the user always confirms in the system dialog; the
     * unknown-sources grant is re-checked on every attempt because the user can
     * revoke it at any time.
     */
    fun installDownloadedUpdate(context: Context) {
        // Resolve from the state, never from latestVersion: a newer release may
        // have been discovered since this download settled, leaving no file for it.
        val state = _updateInfo.value.installState
        val version = state.version ?: _updateInfo.value.latestVersion
        if (version.isBlank()) {
            setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
            return
        }
        val file = updateApkFile(version)
        if (file == null || !file.isFile) {
            setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
            return
        }
        val packageManager = context.packageManager
        if (!packageManager.canRequestPackageInstalls()) {
            setInstallState(
                UpdateInstallState.Failed(version, UpdateFailure.InstallPermissionMissing),
            )
            return
        }
        val failure = verifyDownloadedApk(packageManager, file)
        if (failure != null) {
            setInstallState(UpdateInstallState.Failed(version, failure))
            return
        }

        // Served through the app's existing FileProvider: getUriForDownloadedFile
        // returns null on some OEM builds, FileProvider is deterministic.
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }.getOrNull()
        if (uri == null) {
            setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                setInstallState(UpdateInstallState.Failed(version, UpdateFailure.Unknown))
            }
    }

    /**
     * Reject an APK the platform installer would refuse anyway, so the failure
     * is a readable message instead of an opaque system dialog.
     *
     * Not a security boundary -- the trust source is TLS plus GitHub's API. A
     * key-compromised release is signed correctly by definition.
     */
    private fun verifyDownloadedApk(packageManager: PackageManager, file: File): UpdateFailure? {
        val installed = runCatching {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return UpdateFailure.Unknown
        val candidate = runCatching {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        }.getOrNull() ?: return UpdateFailure.Unknown

        if (candidate.longVersionCode <= installed.longVersionCode) {
            /** Not retryable: no amount of re-downloading changes this. */
            return UpdateFailure.NotNewer
        }
        val installedDigests = signingDigests(installed.signingInfo?.apkContentsSigners)
        val candidateDigests = signingDigests(candidate.signingInfo?.apkContentsSigners)
        if (installedDigests.isEmpty() || candidateDigests.isEmpty()) {
            return UpdateFailure.SignatureMismatch
        }
        if (installedDigests.intersect(candidateDigests).isEmpty()) {
            return UpdateFailure.SignatureMismatch
        }
        return null
    }

    private fun signingDigests(signers: Array<out Signature>?): Set<String> {
        if (signers.isNullOrEmpty()) return emptySet()
        return signers.mapNotNull { signer ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signer.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.getOrNull()
        }.toSet()
    }

    /**
     * A system or sideload install replaces the package underneath us and
     * restarts the process. Any APK still in the updates directory belongs to
     * the version we just left and is now uninstallable dead weight.
     *
     * Wiping the directory rather than a version-named file is deliberate:
     * stored APKs are named for the version they *target*, which by definition
     * is not the version we were running.
     */
    private fun clearStaleApkOnVersionChange() {
        val installed = getCurrentVersion()
        val previous = prefs.getString(PREF_LAST_SEEN_VERSION, null)
        if (!previous.isNullOrBlank() && previous != installed) {
            clearStoredApks()
        }
        prefs.edit().putString(PREF_LAST_SEEN_VERSION, installed).apply()
    }

    /**
     * Record that the user has seen (and declined) this release. Stores the
     * publish timestamp rather than the version, so a re-published or
     * re-numbered tag is not silently re-suppressed by a stale key.
     */
    fun dismissUpdate() {
        val publishedAt = _updateInfo.value.publishedAt
        if (publishedAt != null) {
            prefs.edit().putLong(PREF_LAST_SEEN_PUBLISHED_AT, publishedAt).apply()
        }
        _updateInfo.update { it.copy(isDismissed = true) }
    }

    /** Browser fallback for releases with no .apk asset, and for non-retryable failures. */
    fun openUpdate(context: Context) {
        val targetUrl = _updateInfo.value.downloadUrl ?: _updateInfo.value.releaseUrl.ifBlank {
            FALLBACK_RELEASE_PAGE
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    /**
     * Single dispatch for the primary "Update" affordance.
     *
     * The banner in MainShell and the row in Settings both call this, so the
     * two surfaces cannot disagree about what a tap does for a given state.
     * Kept here rather than in the UI because the branching is about download
     * bookkeeping, not presentation.
     */
    fun performPrimaryUpdateAction(context: Context) {
        val state = _updateInfo.value
        when {
            // Nothing known yet: treat the tap as a manual re-check.
            !state.isUpdateAvailable -> checkForUpdate(isSilent = false, force = true)
            // Release ships no .apk asset, so in-app install is impossible.
            !state.canInstallInApp -> openUpdate(context)
            else -> when (val install = state.installState) {
                is UpdateInstallState.ReadyToInstall -> installDownloadedUpdate(context)
                // A live transfer owns the action; a second enqueue would fail
                // with ERROR_FILE_ALREADY_EXISTS.
                is UpdateInstallState.Downloading -> Unit
                is UpdateInstallState.Idle -> startDownload()
                is UpdateInstallState.Failed -> when {
                    // Re-granting is the only way forward; retrying the download
                    // would just fail the same check.
                    install.reason == UpdateFailure.InstallPermissionMissing ->
                        openUnknownSourcesSettings(context)
                    // Re-downloading an identical, permanently-uninstallable APK
                    // would loop forever and burn the user's data.
                    !install.reason.isRetryable -> openUpdate(context)
                    else -> retryUpdate()
                }
            }
        }
    }

    /**
     * Deep-link to this package's "install unknown apps" page. The banner shows
     * the reason inline first, so the system screen is not a dead end.
     */
    fun openUnknownSourcesSettings(context: Context) {
        val intent = unknownSourcesSettingsIntent(context)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        val cleanRemote = remote.removePrefix("v").removePrefix("V").substringBefore("-")
        val cleanLocal = local.removePrefix("v").removePrefix("V").substringBefore("-")
        if (cleanRemote == cleanLocal) return false

        val rParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun setInstallState(state: UpdateInstallState) {
        _updateInfo.update { it.copy(installState = state) }
    }

    /** GitHub returns ISO-8601, e.g. `2026-10-03T15:20:13Z`. Null if unparseable. */
    private fun String.toEpochMillisOrNull(): Long? = runCatching {
        Instant.parse(this).toEpochMilli()
    }.getOrNull()

    private fun downloadManager(): DownloadManager? = runCatching {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    }.getOrNull()

    private fun isThrottled(): Boolean {
        val last = prefs.getLong(PREF_LAST_CHECK, 0L)
        if (last <= 0L) return false
        val window = prefs.getLong(PREF_LAST_CHECK_WINDOW, THROTTLE_SUCCESS_MS)
        return System.currentTimeMillis() - last < window
    }

    private fun stampCheck(windowMs: Long) {
        prefs.edit()
            .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
            .putLong(PREF_LAST_CHECK_WINDOW, windowMs)
            .apply()
    }

    private fun updateApkFile(version: String): File? {
        if (version.isBlank()) return null
        val root = context.getExternalFilesDir(null) ?: return null
        val safeVersion = version.replace(UNSAFE_FILENAME_CHARS, "_")
        val dir = File(root, UPDATES_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, "$APK_FILENAME_PREFIX$safeVersion.apk")
    }

    private fun clearStoredApks() {
        val root = context.getExternalFilesDir(null) ?: return
        val dir = File(root, UPDATES_DIR)
        dir.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
    }

    private fun clearDownloadRecord() {
        prefs.edit()
            .remove(PREF_DOWNLOAD_ID)
            .remove(PREF_DOWNLOAD_VERSION)
            .apply()
    }

    private data class DownloadSnapshot(
        val status: Int,
        val downloaded: Long,
        val total: Long,
    )

    private fun queryDownload(id: Long): DownloadSnapshot? = runCatching {
        val manager = downloadManager() ?: return null
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            DownloadSnapshot(
                status = cursor.getIntOrDefault(DownloadManager.COLUMN_STATUS, 0),
                downloaded = cursor.getLongOrDefault(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, 0L),
                total = cursor.getLongOrDefault(DownloadManager.COLUMN_TOTAL_SIZE_BYTES, 0L),
            )
        }
    }.getOrNull()

    private fun android.database.Cursor.getIntOrDefault(column: String, fallback: Int): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else fallback
    }

    private fun android.database.Cursor.getLongOrDefault(column: String, fallback: Long): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else fallback
    }

    companion object {
        private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/subhamp29/Bhavyam/releases"
        private const val FALLBACK_RELEASE_PAGE =
            "https://github.com/subhamp29/Bhavyam/releases"
        private const val FALLBACK_VERSION = "1.1"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val UPDATES_DIR = "updates"
        private const val APK_FILENAME_PREFIX = "bhavya-update-"
        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
        private const val PROGRESS_POLL_MS = 500L

        /** Ceiling on the pause-aware backoff while bytes are still moving. */
        private const val PROGRESS_POLL_MAX_MS = 8_000L

        /**
         * Absolute ceiling on a single transfer. DownloadManager reports a
         * paused download as non-terminal forever, so without this a transfer
         * stuck on a lost network would poll until the process dies.
         */
        private const val PROGRESS_DEADLINE_MS = 30L * 60 * 1000

        /** Successful check: re-poll at most this often across foregrounds. */
        const val THROTTLE_SUCCESS_MS = 6L * 60 * 60 * 1000

        /** Failed check: retry sooner, so an offline device is not locked out for six hours. */
        const val THROTTLE_FAILURE_MS = 30L * 60 * 1000

        /** Epoch millis the last-declined release was published; 0 means never declined. */
        private const val PREF_LAST_SEEN_PUBLISHED_AT = "last_seen_published_at"
        private const val PREF_LAST_CHECK = "last_check_ms"
        private const val PREF_LAST_CHECK_WINDOW = "last_check_window_ms"
        private const val PREF_DOWNLOAD_ID = "download_id"
        private const val PREF_DOWNLOAD_VERSION = "download_version"
        private const val PREF_LAST_SEEN_VERSION = "last_seen_version"

        /** Where the system "install unknown apps" grant for this package lives. */
        fun unknownSourcesSettingsIntent(context: Context): Intent =
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
    }
}
