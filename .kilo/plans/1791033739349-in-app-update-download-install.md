# In-app update: detect, download, hand off to the system installer

## Goal

When a newer GitHub Release exists, Bhavya detects it on app open, shows an **Update now**
button, downloads the APK in-app with visible progress, and hands it to the Android package
installer — instead of bouncing the user out to a browser.

## What already exists (reuse, don't rebuild)

| Piece | Location |
| --- | --- |
| GitHub release check | `AppUpdateManager.kt:59-138`, `releases/latest` on `subhamp29/Bhavyam` |
| Version comparison | `AppUpdateManager.kt:158-175` (numeric, `v` prefix stripped) |
| `UpdateInfo` state flow | `AppUpdateManager.kt:22-32`, exposed via `updateInfo` |
| Silent check at startup | `AppUpdateManager.kt:49-51` (`init`) |
| Dismissal storage | `AppUpdateManager.kt:40` — `bhavya_updates` SharedPreferences |
| Banner UI | `MainShell.kt:269-285` + `UpdatePromptCard` `MainShell.kt:314-378` |
| Settings UI | `SettingsScreen.kt:1710-1792` (About: banner + "Check for Updates" row) |
| ViewModel bridges | `MainShellViewModel` `MainShell.kt:141-154`, `SettingsViewModel.kt:108-115` |
| FileProvider | `AndroidManifest.xml:192-200`, authority `${applicationId}.provider` |
| Foreground hook | `MainActivity.onResume()` `MainActivity.kt:171` |
| Notification-channel pattern | `TrackDownloadManager.kt:223-239` |
| `POST_NOTIFICATIONS` | `AndroidManifest.xml:16` (already declared) |

## Constraints the user accepted

- **Android never installs silently.** Download is fully automatic *after the tap*; the final
  install is always a system dialog, and the first time also needs the per-app "Install unknown
  apps" grant. Not negotiable, not a limitation of this design.
- **The published APK must be signed with the same key as the installed one**, or the install
  fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. See Risk R1.
- **`versionName` must be bumped** on every release, or detection reports "you're on the latest".
  Task 7 turns this into an enforced CI check.

## Decisions

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | Download only after the user taps; never auto-download on detection. | No surprise multi-MB usage on metered connections, and devices that never update never pay for the APK. |
| D2 | Re-check on every `MainActivity.onResume()`, throttled to 6 h on success / 30 min on failure. | "Open the app and it tells you" with zero new dependencies. Backoff-on-failure avoids hammering the unauthenticated GitHub API (60 req/h per IP) while a device is offline, without a 6 h penalty for a transient blip. |
| D3 | Android `DownloadManager` does the transfer, not OkHttp. | No permission, survives process death and reboots, has its own progress + notification, and resumes nothing for us to get wrong. `POST_NOTIFICATIONS` is already declared. |
| D4 | Destination is `getExternalFilesDir(null)/updates/`, served through the **existing** FileProvider rather than `getUriForDownloadedFile()`. | App-specific external dir = no storage permission on any API, auto-removed on uninstall. `getUriForDownloadedFile()` returns null on some OEM builds; FileProvider is deterministic and already wired up. |
| D5 | Verify the downloaded APK's signing cert and `versionCode` against the installed app **before** invoking the installer. | A mismatched or stale APK otherwise produces an opaque system error. Fail with a specific message instead. This is a correctness guard, not a security boundary — TLS + GitHub's API is the trust source. |
| D6 | On download completion: open the installer if Bhavya is foregrounded, otherwise post a "tap to install" notification. | Android blocks launching an activity from the background on modern versions, so a notification is the only handoff path when the user walked away. |
| D7 | No `.apk` asset on the release ⇒ keep today's browser fallback and label the button "Download", not "Update now". | Current code silently falls back to `html_url` (`:102`, `:122`). Making it explicit prevents a button that promises in-app install and delivers a web page. |
| D8 | One new sealed `UpdateInstallState` on the existing `UpdateInfo`; no new repository, no Room table, no DataStore key. | The download is transient process state, exactly like `isChecking`. `bhavya_updates` SharedPreferences stores only the resume bits (download id + version). |
| D9 | New strings go in `values/strings.xml` only. | 13 other locales fall back to English. `lint.abortOnError = false` (`build.gradle.kts:174`) so `MissingTranslation` cannot fail the build. |

## Tasks

### 1. Manifest + FileProvider path

- `AndroidManifest.xml` — add beside `INTERNET` (`:8`):
  `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`
- `res/xml/file_paths.xml` — add
  `<external-files-path name="updates" path="updates/" />`
  (the existing `documents` entry maps `getExternalFilesDir(null)/Documents/`, which does **not**
  match `Environment.DIRECTORY_DOWNLOADS` = `"Download"`; hence a new entry rather than reusing it)

### 2. `AppUpdateManager` — state, throttle, download, verify, install

All in `app/src/main/java/com/bhavya/music/data/update/AppUpdateManager.kt`. New file
`data/update/UpdateInstallState.kt` for the sealed interface + failure enum.

- Add to `UpdateInfo`: `val installState: UpdateInstallState = UpdateInstallState.Idle`
- **Throttle.** In the same `bhavya_updates` prefs, add `last_check_ms`. `checkForUpdate()` gains
  an `isThrottled: Boolean = false` path: when called from the foreground hook, skip if
  `now - last_check_ms` is under the window. Stamp `last_check_ms` on **attempt** — 6 h when the
  response was successful, 30 min otherwise. The manual `checkForUpdates()` path
  (`SettingsViewModel.kt:115`) always bypasses the throttle and stamps 6 h.
- **Download.** `fun startDownload(apkUrl: String, version: String)`. Enqueue
  `DownloadManager.Request` with `setDestinationInExternalFilesDir(context, null,
  "updates/bhavya-update-$version.apk")`, mime `application/vnd.android.package-archive`,
  `setNotificationVisibility(VISIBILITY_VISIBLE_NOTIFY_COMPLETED)`, metered allowed, roaming off.
  Persist `download_id` + `download_version` in `bhavya_updates`. Delete any previous
  `bhavya-update-*.apk` before enqueuing so files cannot accumulate.
- **Progress.** `DownloadManager` has no progress callback — poll `query()` for
  `COLUMN_STATUS` / `COLUMN_BYTES_DOWNLOADED_SO_FAR` / `COLUMN_TOTAL_SIZE_BYTES` on a ~500 ms
  coroutine in the existing `scope` (`:39`), emitting `UpdateInstallState.Downloading(done, total)`.
  Poll only while state is `Downloading`; cancel the job on any terminal state.
- **Completion.** Register a receiver for `DownloadManager.ACTION_DOWNLOAD_COMPLETE` via
  `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)` in `init` — a
  *dynamic* receiver, so no manifest entry and no `exported` surface. On a matching id, re-`query()`
  the status: `STATUS_SUCCESSFUL` ⇒ `ReadyToInstall`, `STATUS_FAILED` ⇒ `Failed(Network)`.
- **Resume after process death.** In `init`, if `download_id` is stored, `query()` it and
  reconstruct the state (still running ⇒ `Downloading` + resume polling, done ⇒ `ReadyToInstall`).
  This is what makes D3's "survives process death" actually observable in the UI.
- **Verify + install.** `fun installDownloadedUpdate(context: Context)`:
  1. `packageManager.canRequestPackageInstalls()` is false ⇒ `Failed(InstallPermissionMissing)`; the
     UI owns the rationale dialog.
  2. `File` at `getExternalFilesDir(null)/updates/bhavya-update-$version.apk`; missing ⇒ `Failed(Unknown)`.
  3. `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`; null ⇒ `Failed(Unknown)`.
     Its `versionCode` must be `>` the installed one ⇒ else `Failed(Unknown)`.
     SHA-256 over `signingInfo.apkContentsSigners` must intersect the installed app's signers
     ⇒ else `Failed(SignatureMismatch)`.
  4. `FileProvider.getUriForFile(context, "${packageName}.provider", file)` →
     `Intent(Intent.ACTION_INSTALL_PACKAGE)` with `FLAG_GRANT_READ_URI_PERMISSION` and
     `FLAG_ACTIVITY_NEW_TASK`. `runCatching { startActivity }` (`:154` already uses this guard for
     the browser intent — same defensive shape).
  - Do **not** delete the APK here; the installer reads it asynchronously.
- **Post-install cleanup.** In `init`, when the detected `cleanTag` equals the installed
  `versionName`, clear `dismissed_version` and delete any `updates/*.apk`. This is how a
  successful update clears its own banner on the next launch, and it is the only place a stale
  file is removed.
- `openUpdate()` (`:146`) stays, used only for the no-asset fallback.

### 3. ViewModel bridges

- `MainShellViewModel` (`MainShell.kt:141-154`): add `startUpdateDownload()`,
  `installUpdate(context)`, `retryUpdate()`, each a one-line delegate.
- `SettingsViewModel` (`SettingsViewModel.kt:108` area): the same three delegates.
- `onAppForegrounded()` delegate on both; `MainActivity.onResume()` (`:171`) calls it inside
  `runCatching` alongside `requestHighestSupportedRefreshRate()`.

### 4. Banner UI — `MainShell.kt:314-378`

Make `UpdatePromptCard` state-driven off `updateInfo.installState`:
`Idle` → "Update" button starts the download · `Downloading` → `LinearProgressIndicator` with
percent replaces the button · `ReadyToInstall` → button reads "Install" · `Failed` → "Retry" and
the reason in the subtitle. When `updateInfo.downloadUrl == null` (D7) keep today's
`openUpdate` behaviour and label the button "Download".

The `visible = showUpdateBanner` condition at `:221` stays — the banner is about availability,
and the install sub-state lives inside it. Widen the parameters to take the whole `UpdateInfo`
plus callbacks rather than the current `version`/`onUpdate`/`onDismiss` triple.

### 5. Settings UI — `SettingsScreen.kt:1710-1792`

Same state-driven treatment on both surfaces: the big banner (`:1711-1769`) and the
"Check for Updates" row (`:1773-1792`). Repoint `onClick` (`:1719`, `:1784-1790`) at the new
delegates. Extend the row's `subtitle` `when` (`:1778-1783`) with the new states so progress and
errors are visible here too.

### 6. Strings — `res/values/strings.xml`

Add beside `update_ready_to_install` (`:17`): `update_install`, `update_downloading` (with a `%1$d%%`
format arg), `update_install_blocked`, `update_error_network`, `update_error_signature`,
`update_error_unknown`, `update_button_download`. Follow the `\u2014` em-dash style used at `:204`.
No translations (D9).

### 7. Release workflow — `.github/workflows/release.yml` (new)

No `.github/` directory exists yet. One file, triggered on `tags: ['v*']`, `permissions: contents: write`,
`concurrency: release-${{ github.ref }}` so a re-pushed tag cannot race itself.

Three jobs:

1. **`verify`** — the highest-value step. Parse `versionName` out of `app/build.gradle.kts` and
   fail the run unless it equals `${GITHUB_REF_NAME#v}`. A mismatch here is exactly the failure
   mode that makes the whole feature silently no-op, so it must be a hard failure, not a warning.
2. **`build`** — `actions/checkout@v4`, `actions/setup-java@v4` (JDK 17, matches
   `sourceCompatibility = 17`), `gradle/actions/setup-gradle@v4`, `android-actions/setup-android@v3`,
   then `./gradlew :app:assembleRelease`. Pass through the env the build already reads
   (`build.gradle.kts:35-57`): `SIGNING_KEY` (base64) **or** `RELEASE_STORE_FILE` /
   `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`, plus
   `ADDON_CLIENT_SECRET` and `RELEASE_CERT_SHA256` for `generateNativeSecrets` (`:310`).
   **Fail closed**: a step that errors if the signing secrets are absent. The build silently
   debug-signs instead (`build.gradle.kts:110-120`), so without this guard a release workflow
   publishes a debug-signed APK that no device can self-update to. See R1.
3. **`release`** — `gh release create "$GITHUB_REF_NAME" bhavya-<version>.apk --generate-notes`
   using `GITHUB_TOKEN`. Using `gh` avoids a third-party release action.

The native build needs an NDK + CMake 3.22.1 (`build.gradle.kts:157-161`) and `ndkVersion` is
**not** pinned in `app/build.gradle.kts`. Before writing the workflow, read the NDK version the
local build uses and pin it as a `NDK_VERSION` env at the top of the workflow. See Open Question Q1.

## Risks

- **R1 — Signing key drift (highest).** `build.gradle.kts:110-120` falls back to
  `initWith(getByName("debug"))` whenever the release secrets are missing. The debug keystore is
  machine-local, so a debug-signed release makes every in-app install fail with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Mitigation is threefold: the fail-closed CI check in
  Task 7, the cert comparison in Task 2 step 3, and confirming the currently published release APK
  is signed with the real key before shipping this.
- **R2 — `REQUEST_INSTALL_PACKAGES` is a sensitive permission.** Google Play requires a
  declaration plus justification for apps targeting API 34+. Not applicable while distribution is
  a GitHub-release APK, but it must be declared if this ever ships on Play. Devices may also
  revoke the per-app unknown-sources grant at any time, so the check is re-run on every install
  attempt rather than cached.
- **R3 — GitHub API rate limit.** 60 req/h per IP, unauthenticated. A 403 currently surfaces as
  `Could not check updates (HTTP 403)` and is silent on the startup check. The 6 h throttle caps
  a single device at ~4 requests/day; a carrier-grade NAT or shared office IP can still exhaust the
  shared quota.
- **R4 — Unparseable release tags.** `isNewerVersion` (`:158`) returns `false` when the tag has no
  numeric dot-separated parts, so a tag like `nightly` produces no banner. Fail-safe already; do
  not "fix" it by treating unparseable tags as newer.
- **R5 — Process death mid-download.** Mitigated by the persisted `download_id` resume in Task 2.
  Test explicitly (Validation 6) — a stale id pointing at a pruned download must degrade to
  `Failed`, never to a spinner that never resolves.
- **R6 — Storage.** The APK is tens of MB. Old files are deleted before a new download and after
  a version match; `getExternalFilesDir` is also removed on uninstall.
- **R7 — R8/proguard.** `isMinifyEnabled = true` (`:127`). No reflection is used
  (`PackageManager` APIs, not string-based class loading), the receiver is dynamically registered,
  and the sealed state class is referenced directly — so no new keep rules are expected. Verify by
  installing an actual `assembleRelease` build, not just `compileDebugKotlin`.

## Out of scope

- Silent install. Impossible on Android; the system dialog is always shown.
- Delta / patch updates, or bundling the update in a Play-style self-updating library.
- Rollback to an older version.
- Translating the new strings (D9).
- `DOWNLOAD_WITHOUT_NOTIFICATION` / metering policies beyond "metered allowed, roaming off".

## Validation

1. `gradlew.bat :app:compileDebugKotlin`, then `:app:assembleDebug` — the compile gate. Lint will
   not fail the build (`build.gradle.kts:172-176`).
2. **Happy path without needing a newer build.** From a debug install of the current tree, attach
   the *current* APK to a draft release tagged `v99.0.0`. Open Bhavya ⇒ banner appears. Tap
   "Update" ⇒ progress ⇒ "Install" ⇒ system installer opens ⇒ install ⇒ app relaunches at 99.0.0
   and the banner is gone. A debug build installing a debug-signed APK of the same machine passes
   the cert check in D5.
3. **Unknown sources.** Revoke Bhavya's "install unknown apps" grant in system Settings, then tap
   "Install" ⇒ the rationale dialog appears, and its action opens
   `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. No crash, no silent no-op.
4. **Signature mismatch.** Attach an APK signed with a *different* key to a draft release ⇒ the app
   reports `update_error_signature` and does **not** launch the installer. This is the R1 guardrail.
5. **No asset.** Publish a release with no `.apk` ⇒ banner shows, button reads "Download", and
   tapping it still opens the release page (D7).
6. **Process death mid-download.** Start a download, force-stop the app, relaunch ⇒ state is
   recovered from the stored id, no duplicate download, no stuck spinner.
7. **Backgrounded completion.** Start a download, immediately home the app ⇒ on completion a
   "tap to install" notification appears and opens the installer (D6).
8. **Throttle.** Foreground repeatedly ⇒ exactly one request, then silence for 6 h. Airplane mode
   ⇒ error state, and the next check retries after 30 min rather than 6 h (D2).
9. **Real release.** With the workflow live: tag a version, confirm the `verify` job rejects a
   tag/`versionName` mismatch, then confirm the published release carries a `.apk` asset and that
   a device on the previous version detects and installs it.
