# Cleanup pass: in-app updater code + compiler warnings

## Goal

Remove genuinely unnecessary lines from the in-app updater code and clear the
compiler warnings, with **zero behaviour change**. Bounded and
compiler-verifiable — explicitly *not* a repo-wide dead-code sweep.

## Decisions already made

| Decision | Choice |
| --- | --- |
| Scope | New updater code + compiler warnings. Repo-wide sweep and gitignore work explicitly out of scope. |
| Comments | Drop restatement, keep WHY. ~179 → ~110 lines. |

## Evidence this is safe

Everything below is either (a) something the compiler already flags as
unnecessary, or (b) a comment. No logic changes, no signature changes, no
resource deletions. The behavioural surface touched is exactly one thing:
`AutoMirrored` icons mirror in RTL locales (see 1.2).

## Two things that must NOT be "cleaned up"

- **`settings_section_experimental` is USED** — rendered at `SettingsScreen.kt:1143`.
  The first planning session's handover called it an unused leftover; that was
  wrong. A naive dead-resource sweep would break the Experimental section label.
- **`settings_check_updates` is USED** — referenced from `UpdateUi.kt:86`, and
  translated in 13 locale files.

## Phase 1 — Compiler warnings

### 1.1 Seven unnecessary safe calls (behaviour-neutral)

`viewModel.theme` is `StateFlow<ThemeUiState>` — **non-nullable**
(`SettingsViewModel.kt:202`) — collected at `SettingsScreen.kt:432`. So `theme`
is non-null and every `theme?.x ?: default` is dead defensiveness.

> Do not confuse this with `SettingsScreenState.theme: ThemeUiState?`
> (`SettingsViewModel.kt:65`), which genuinely *is* nullable. Different
> property, different type. The screen uses the non-null one.

| Line | Current | Becomes |
| --- | --- | --- |
| 1024 | `theme?.themeMode ?: ThemeMode.SYSTEM` | `theme.themeMode` |
| 1035 | `theme?.amoled ?: false` | `theme.amoled` |
| 1036 | `theme?.themeMode != ThemeMode.LIGHT` | `theme.themeMode != ThemeMode.LIGHT` |
| 1047 | *read before editing* | drop `?.` and any `?:` |
| 1064 | *read before editing* | drop `?.` and any `?:` |
| 1130 | `theme?.mode ?: AccentMode.MANUAL` | `theme.mode` |
| 1131 | `theme?.accentColorHex` | `theme.accentColorHex` |

Line 1131's target property is itself nullable, so the result stays `String?`
— dropping `?.` is still correct and is what the warning asks for.

### 1.2 Twelve deprecated icons → `AutoMirrored`

`Icons.Filled.*` → `Icons.AutoMirrored.Filled.*`, adding the
`androidx.compose.material.icons.automirrored.filled.*` imports.

| Icon | Lines |
| --- | --- |
| `QueueMusic` | 402, 1387, 1416, 1524, 1534, 4587, 4729 |
| `VolumeUp` | 942, 1232 |
| `Logout` | 3124, 3252 |
| `FormatListBulleted` | 1376 |

**This is the one intentional behaviour change**: direction-aware icons mirror
in RTL locales (`values-ar`, `values-fa`). That is the correct, intended
outcome and an accessibility improvement, not a regression.

Per site, confirm an `AutoMirrored` variant actually exists in the
material-icons-extended version resolved by `gradle/libs.versions.toml`. If any
lacks one, leave it on `Icons.Filled` rather than forcing it — and report it.

### 1.3 Hoist the redundant `Json` allocation

`SettingsViewModel.kt:687` allocates `Json { ignoreUnknownKeys = true }` inside
the function. Verified as the **only** `Json {` occurrence in that file. Hoist
to a `private val` on the class.

## Phase 2 — Comment trimming (drop restatement, keep WHY)

### Delete as pure restatement

- `UpdateInstallState.kt` file-level KDoc (lines 3-18, 16 lines) → 3-4 lines.
  Keep only: state is transient/not persisted, and the load-bearing reason every
  state carries its `version`.
- Single-line KDocs that just name the member: `/** No download in progress... */`
  above `Idle`; `/** URL of the release's .apk asset... */` above `downloadUrl`.
- `AppUpdateManager.kt:380` `/** Resolve a finished transfer into a terminal install state. */`
- `AppUpdateManager.kt:299` `/** The retry affordance. [startDownload] is already idempotent. */`

### Keep — non-obvious WHY, and each one guards a real trap

- **The two `pollProgress` hazards (302-319).** *Most important comment in the
  codebase.* It explains why the `isActive` guard and the 30-minute deadline
  exist. Delete it and a future contributor will "simplify" the guard away and
  reintroduce a permanently-dead update button.
- DownloadManager chosen over an OkHttp body (231-238) — permission-free,
  outlives the process.
- Receiver registered dynamically, not in the manifest (72-77).
- FileProvider over `getUriForDownloadedFile` (458-459) — returns null on some
  OEM builds.
- `verifyDownloadedApk` is *not* a security boundary (481-487).
- `PROGRESS_DEADLINE_MS` rationale — paused downloads are non-terminal forever.
- `isRetryable` semantics on `SignatureMismatch` / `NotNewer`.
- Unknown-sources grant is revocable, so re-check on every attempt (424-429).
- `stampCheck` before launch — the foreground race (133-135).
- 6 h success / 30 min failure throttle windows.
- `// ── Section ────` dividers — navigational in a ~750-line file.

### Condense

- `UpdateUi.kt` KDoc (11-24): the paragraph recounting how the Settings copy
  used to drift is archaeology. Cut to ~3 lines. **Keep** the
  `@param canInstallInApp` explanation — it documents a non-obvious contract.
- Multi-line inline comments that elaborate rather than explain → single line.

## Phase 3 — Narrow public surface (optional; verify tests first)

Only called from inside `AppUpdateManager`, so candidates for `private`:
`isNewerVersion` (:621), `getCurrentVersion` (:111),
`unknownSourcesSettingsIntent` (:758).

**Before changing any of these, grep `app/src/test`** for references. They are
reasonable unit-test seams; if a test uses one, leave it public. If none does,
make them private — but this is the lowest-value item in the plan and is
genuinely optional.

## Verification

Run in order. Each is a gate; do not proceed past a failure.

1. `.\gradlew.bat :app:compileDebugKotlin`
   Must succeed **and** the warning list must no longer contain any of the 7
   "Unnecessary safe call", 12 "deprecated `Icons.Filled.*`" or 1 "Redundant
   creation of Json" entries. Diff the before/after `w:` sets — the count of new
   warnings must be zero.
2. `.\gradlew.bat :app:testDebugUnitTest` — must stay green.
3. `.\gradlew.bat :app:assembleDebug` — proves resource merge is intact.
4. Grep-verify nothing dangles: confirm `settings_section_experimental` and
   `settings_check_updates` are still referenced, and that no edit removed a
   declaration still referenced elsewhere.

**Warning noise is expected and is not a regression:** the build already emits
`Deprecated 'org.jetbrains.kotlin.android' plugin usage`, the
`android.builtInKotlin` / `android.newDsl` AGP notices, and
`SettingsViewModel.kt` still reports one `Redundant creation of Json` warning
after 1.3 if the hoisted instance is not const-stable — re-read the exact
remaining warning before concluding 1.3 failed.

## Out of scope (agreed)

- Repo-wide unused-resource / dead-declaration sweep. Deferred, and it needs
  manual verification per candidate given the `settings_section_experimental` trap.
- `final_apk/` and `.kilo/` untracked leftovers; `.gitignore` tightening.
- Removing the `runCatching` wrappers in `AppUpdateManager`. Most are genuinely
  reachable (OEM FileProvider quirks, missing package installer), and the
  security review already cleared them.

## Risk

Low. This is deletion plus a mechanical icon migration. The safety net is the
`git diff`: review it as three separable chunks (Phase 1, Phase 2, Phase 3) so a
regression in one is easy to isolate and revert. Nothing here is
behaviour-preserving-by-inspection alone — every claim is backed by either a
compiler warning or a recorded reference count.