# Phase 5: Verification Plan for Beat-Reactive Visuals (Phases 0-4)

## Objective
Verify that all beat-reactive visual features from Phases 0-4 are correctly implemented, integrated, and functional before considering the feature complete.

## Implementation Summary (Confirmed Complete)

| Phase | Scope | Key Files | Status |
|-------|-------|-----------|--------|
| 0 | Core Beat Detection | `BeatEnergyTap.kt`, `NativeProcessingAudioSink.kt`, `MusicPlayer.kt` | ✅ Done |
| 1 | PlayerViewModel Bridge | `PlayerViewModel.kt` (maps `beatEnergy`), `PlayerHost.kt` | ✅ Done |
| 2 | Full-Screen Artwork (AGSL) | `FluidArtworkBackground.kt` (AGSL shader, `BeatReactiveLayer`) | ✅ Done |
| 3 | Mini-Player & Lyrics | `PlayerHost.kt` (MiniPlayer wrapper, Lyrics background) | ✅ Done |
| 4 | Lock Screen Widget | `WidgetViews.kt`, `ObsidianWidgetViews.kt`, `WidgetUpdater.kt`, `MusicPlaybackService.kt` | ✅ Done |
| Settings | UI Controls | `SettingsScreen.kt`, `SettingsPreferences.kt` | ✅ Done |

---

## Verification Checklist

### 1. Core Beat Detection (Phase 0)
- [ ] **BeatEnergyTap** processes audio at ~25ms windows (40 FPS)
- [ ] **Bass energy** (20-150 Hz IIR) and **RMS** computed correctly
- [ ] **Beat onset** detection with adaptive threshold + 250ms minimum gap
- [ ] **Latency compensation** ring buffer works (sink latency + user sync offset)
- [ ] **Lock-free publication** via AtomicReference to UI thread
- [ ] **Active/inactive gating**: disabled when DSP path off, USB exclusive, paused
- [ ] **Format changes** reset state cleanly (`setFormat`)

**Test**: Play audio with strong bass; verify `beatEnergy` StateFlow emits non-null snapshots with `isActive=true`, `beatDetected` toggles on beats.

### 2. PlayerViewModel Bridge (Phase 1)
- [ ] `PlayerViewModel.beatEnergy` maps `MusicPlayer.beatEnergy` 1:1
- [ ] No transformation/loss of snapshot fields

### 3. Full-Screen Artwork AGSL (Phase 2)
- [ ] **AGSL uniforms** receive beat params: `beatBass`, `beatRms`, `beatOnset`, `beatIntensity`
- [ ] **BeatReactiveLayer guards** all enforced:
  - API 31+ (TIRAMISU)
  - Foreground (Lifecycle.RESUMED)
  - Not in power save mode
  - `settings.beatReactiveEnabled == true`
  - `snapshot?.isActive == true`
- [ ] **Visual effects** visible:
  - Bass-driven hue shift (warm on bass)
  - RMS-driven brightness bump
  - Beat onset pulse wave (expanding ring)
  - Intensity scaling (0.1–1.0)
- [ ] **GraphicsLayer pulse**: 1.0→1.02x scale on beat
- [ ] **Crossfade** still works during track changes

**Test**: Enable beat reactive in settings, play bass-heavy track; verify background reacts to beats.

### 4. Mini-Player Artwork (Phase 3)
- [ ] Wrapped in `BeatReactiveLayer` in `PlayerHost.kt`
- [ ] **Size guard**: `isTabletOrWideScreen()` prevents activation on narrow phones
- [ ] Same guards as full-screen (API 31+, foreground, power save, settings, snapshot)
- [ ] Visual effect subtle (smaller artwork = smaller pulse)

**Test**: On tablet/wide screen, open mini-player; verify artwork pulses on beat.

### 5. Lyrics Background (Phase 3)
- [ ] `FluidArtworkBackground` call in `PlayerHost.kt` (Lyrics section) receives `beatEnergy` and `settings`
- [ ] Same AGSL shader and `BeatReactiveLayer` as full-screen
- [ ] Effects visible behind lyrics text

**Test**: Open lyrics screen during playback; verify background reacts.

### 6. Lock Screen Widget (Phase 4)
- [ ] **MusicPlaybackService** collects `beatEnergy`, stores `latestBeatEnergy`
- [ ] **WidgetUpdater.publish()** accepts `beatDetected`/`bassEnergy`, stores `beatTimestamp`
- [ ] **WidgetSnapshot** persists `beatTimestamp`
- [ ] **WidgetViews.build()** receives `beatTimestamp`, calls `applyBeatTint()`
- [ ] **applyBeatTint()** uses `ColorMatrix`:
  - Warm hue shift (bass-driven)
  - Brightness bump (RMS-driven)
  - Only when `beatTimestamp` within 300ms
- [ ] **ObsidianWidgetViews** same behavior
- [ ] **RemoteViews constraint**: Only CPU bitmap tint (no GPU/AGSL)

**Test**: Add widget to lock screen, play music; verify artwork tints on beats.

### 7. Settings UI & Persistence
- [ ] **Toggle**: "Beat-Reactive Visuals" (`beatReactiveEnabled`)
- [ ] **Slider**: "Intensity" 10–100% (`beatReactiveIntensity`, default 50%)
- [ ] **Sync Offset**: -200 to +200ms (`beatReactiveSyncOffsetMs`)
- [ ] Values persist across app restarts
- [ ] `MusicPlayer.setBeatEnergySyncOffset()` called on offset change

### 8. Auto-Disable Guards
- [ ] **Battery Saver**: `PowerManager.isPowerSaveMode` checked every 5s
- [ ] **Glass Capability**: API 31+, not low-RAM, HW accelerated
- [ ] **Animator Scale**: (if implemented) disabled when animator duration scale = 0
- [ ] **Foreground**: `Lifecycle.State.RESUMED` via `rememberIsForeground()`
- [ ] All surfaces (full-screen, mini-player, lyrics) respect guards simultaneously

### 9. Sync Offset
- [ ] User offset (-200 to +200ms) added to sink latency in `BeatEnergyTap.setLatencyCompensation()`
- [ ] Applied at source in `MusicPlayer.setBeatEnergySyncOffset()`
- [ ] Verified: visual beat aligns with audible beat

### 10. Build & Runtime
- [ ] `./gradlew :app:assembleDebug` succeeds
- [ ] No lint errors (`./gradlew :app:lintDebug`)
- [ ] No runtime crashes on API 29+ (minSdk 29, AGSL gated at API 31+)
- [ ] No memory leaks (beat polling job cancelled on sink switch)

---

## Test Scenarios

| Scenario | Expected |
|----------|----------|
| Cold start, play track | Beat reactive activates after first DSP buffer (~100ms) |
| Pause → Resume | Beat reactive resumes immediately |
| Track change (gapless) | Crossfade continues, beat reactive uninterrupted |
| Track change (non-gapless) | Brief pause, reactivates on new track |
| Background → Foreground | Reactivates on RESUMED |
| Battery saver ON | Disables immediately, re-enables when OFF |
| Settings toggle OFF | Disables immediately |
| USB Exclusive DAC active | Beat reactive disabled (DSP path bypassed) |
| Tablet mini-player | Shows beat pulse; phone mini-player does not |
| Lock screen widget | Artwork tints on beat (300ms window) |
| Sync offset ±200ms | Visual beat shifts relative to audio |

---

## Known Limitations (Out of Scope for Phase 5)
- USB Exclusive path: No DSP → no beat energy (by design)
- Offload/Compressed audio: No DSP → no beat energy (by design)
- Widget: RemoteViews only supports CPU ColorMatrix tint
- AGSL requires API 31+; falls back to CPU blur on older devices

---

## Validation Commands
```bash
# Build
./gradlew :app:assembleDebug

# Lint
./gradlew :app:lintDebug

# Unit tests (if any for beat detection)
./gradlew :app:testDebugUnitTest

# Install & manual test
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Sign-Off Criteria
All checklist items ✅ + manual test scenarios pass + build/lint clean = Phase 5 complete.