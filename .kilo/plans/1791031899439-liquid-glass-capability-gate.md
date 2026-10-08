# Liquid Glass toggle — capability gate in Appearance

## Goal

Make the existing `Appearance → Liquid Glass` switch honest on devices that cannot render
glass, so it can never read ON while nothing renders.

## What already exists (do not rebuild)

| Piece | Location |
| --- | --- |
| Toggle row | `SettingsScreen.kt:1045-1055` (Appearance tab, index 3 of 7) |
| Row component, already supports `enabled` | `SettingsScreen.kt:2525-2600` (`SettingsToggleCard`) |
| ViewModel action | `SettingsViewModel.kt:417` (`setLiquidGlass`) |
| Repository | `ThemeRepository.kt:271` (`setLiquidGlass`) |
| Persistence | `ThemePreferences.kt:56,68,92` — key `lw_liquidGlass`, default `false` |
| Composition local | `Theme.kt:99` provides `LocalLiquidGlass` from `themeState.liquidGlass` |

**No new setting, key, repository method, ViewModel method, or `SettingsTab` is needed.**
Search index entry `appearance.liquid_glass` (`SettingsSearchIndex.kt:388`) also stays valid
because the row is never removed.

## The actual defect

`isDeviceGlassCapable()` (`LiquidGlass.kt:361-373`) returns `false` when:

- `Build.VERSION.SDK_INT < Build.VERSION_CODES.S` (API < 31 / Android < 12)
- `!view.isHardwareAccelerated` (software rendering / preview)
- `context.getSystemService(ActivityManager).isLowRamDevice == true`

`isLiquidGlassBackdropSupported()` (`LiquidGlass.kt:375-377`) folds that together with
`LocalLiquidGlass.current`. So on any of those devices the switch can be ON, the flag flows
into `LocalLiquidGlass`, and every glass call site silently takes its fallback path. The user
gets a switch that does nothing.

Note: API 31-32 **is** capable (`lens` self-guards and is omitted, blur/tint still render) —
only below API 31 is the row inert. Do not gate at 33.

## Decisions

| # | Decision | Rationale |
| --- | --- | --- |
| G1 | Reuse `isDeviceGlassCapable()` verbatim as the gate. Do not add a second capability predicate and do not touch `LiquidGlass.kt`. | It is the exact predicate the renderer uses, so the invariant "switch ON ⇒ glass renders" holds structurally, not by convention. |
| G2 | Disable the row with the existing `enabled` param; do not hide it. | `SettingsToggleCard` already greys the icon, title, subtitle, switch and drops the press scale (`SettingsScreen.kt:2542-2597`). Hiding would break the settings-search deep link. |
| G3 | Keep showing the real persisted value even while disabled; do not force-write `false`. | Matches the AMOLED precedent (`SettingsScreen.kt:1027` is disabled in LIGHT theme and still shows the stored value). A disabled ON is honest once the subtitle explains why; silently rewriting DataStore on open is a side effect the user never asked for. |
| G4 | One new string, not a reason matrix. | `isDeviceGlassCapable()` returns a bare boolean, so distinguishing "too old" from "low-RAM" would require new public API in the theme layer for one settings subtitle. One accurate string covers both. |
| G5 | Subtitle pattern copied from `SettingsScreen.kt:924-925` ("Requires Android 10 or newer"), combined with the `enabled` gate from `SettingsScreen.kt:1027`. | Both idioms already exist in this file; this change is their first combination. |

## Tasks

All edits in `app/src/main/java/com/bhavya/music/ui/settings/SettingsScreen.kt` and
`app/src/main/res/values/strings.xml` unless noted.

### 1. New string resource

`strings.xml`, next to `settings_liquid_glass_sub` (line 72):

```xml
<string name="settings_liquid_glass_unsupported">Unavailable on this device \u2014 needs Android 12 or newer</string>
```

Uses the same em-dash construction already used by `dl_location_unavailable_short`
(`strings.xml:204`).

### 2. Compute the gate once, above the Appearance group

`SettingsScreen.kt:1010` — inside the existing `Column`, next to the `SectionLabel`, so the
`when (index)` block stays readable and `isDeviceGlassCapable()` is called once per
composition rather than once per row:

```kotlin
// Liquid Glass renders nothing below API 31 / on low-RAM / software-rendered
// devices, so the switch must not be operable there. Same predicate the
// renderer gates on — see LiquidGlass.kt:isDeviceGlassCapable().
val liquidGlassAvailable = isDeviceGlassCapable()
```

### 3. Gate the row

`SettingsScreen.kt:1045-1055`:

```kotlin
3 -> SettingsToggleCard(
    icon = Icons.Filled.BubbleChart,
    iconContainer = MaterialTheme.colorScheme.primaryContainer,
    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
    title = stringResource(R.string.settings_liquid_glass),
    subtitle = if (liquidGlassAvailable) {
        stringResource(R.string.settings_liquid_glass_sub)
    } else {
        stringResource(R.string.settings_liquid_glass_unsupported)
    },
    checked = theme?.liquidGlass ?: false,
    enabled = liquidGlassAvailable,
    onCheckedChange = viewModel::setLiquidGlass,
    position = position,
    isHighlighted = (highlightedSettingId == "appearance.liquid_glass"),
)
```

`SettingsGroup(rowCount = 7)` and the `position = position` argument are unchanged — no row
added or removed, so the rounded group shape stays correct.

### 4. Import

Add `import com.bhavya.music.ui.theme.isDeviceGlassCapable` to `SettingsScreen.kt`
(next to the existing `com.bhavya.music.ui.theme.ExpressivePillShape` at line 218).
`stringResource` and `R` are already imported.

Nothing else changes: `ThemePreferences`, `ThemeRepository`, `SettingsViewModel`,
`LiquidGlass.kt`, `Theme.kt` and `NavGraph.kt` are untouched.

## Risks

- **Early return inside `isDeviceGlassCapable()`.** On API < 31 it returns before its
  internal `remember` (line 368). This is pre-existing — the function already runs that path
  at every glass call site today — so `SettingsScreen` adds no new risk. Call it from a
  `Column` body, not inside `SettingsGroup`'s lambda, so the remember stays in a stable
  position regardless of which tab is rendered.
- **`view.isInEditMode`.** Compose previews will show the row disabled with the
  "Unavailable" subtitle. Harmless, and arguably correct.
- **Stale `true` in DataStore** on an incapable device stays `true` and inert (per G3). It
  only becomes visible again if the device is replaced, which cannot happen in practice.
- **No behavioural change on capable devices.** API 33+ and API 31-32 devices see exactly the
  current row.

## Out of scope

- Changing the default (`lw_liquidGlass` stays `false`).
- Any change to the search-bar glass just implemented in
  `SearchScreen.kt`, or to the other ~19 `liquidGlassChrome` call sites — they already gate on
  `isDeviceGlassBackdropSupported()`.
- Cleaning up the now-unused `settings_section_experimental` string
  (`strings.xml:70`), or the stale "existing experimental Liquid Glass preference" wording at
  `docs/liquid-glass-migration.md:17`. Both are cosmetic; flag them rather than bundling.
- A reason-specific subtitle for low-RAM vs too-old devices (see G4).

## Validation

1. `gradlew.bat :app:compileDebugKotlin` — the compile gate (`app/build.gradle.kts:172-174`
   sets `abortOnError = false`, so lint will not fail the build). No test task exists in the
   repo.
2. API 33+ device: Appearance → Liquid Glass row looks and behaves exactly as before, and
   toggling still takes effect immediately across the app.
3. API 29/30 device: the row is greyed out, the subtitle reads
   "Unavailable on this device — needs Android 12 or newer", and tapping the card or the
   switch does nothing. Confirm `isDeviceGlassCapable()` is really the cause and not, say, a
   low-RAM emulator config.
4. On the API 29/30 device, install from a build where DataStore already holds
   `lw_liquidGlass = true` and confirm the row renders greyed but **checked**, matching G3.
5. Low-RAM emulator (`adb shell setprop dalvik.vm.heapgrowthlimit`/low-RAM profile, or any
   `isLowRamDevice=true` device) on API 33+: same greyed state, and the string still reads
   truthfully (it leads with "Unavailable on this device", not "needs Android 12").
6. Settings search: type "glass" and jump to the result on an incapable device — the row must
   still be reachable and highlighted (it is disabled, not hidden).