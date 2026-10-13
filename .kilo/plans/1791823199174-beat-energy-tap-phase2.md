# Phase 2 Plan: Beat-Reactive UI + Settings

## Goal
Wire `BeatEnergyTap.latestSnapshot` into the player UI and add user-facing settings (enabled, intensity, sync offset).

---

## 1. Settings (DataStore)

### New keys in `SettingsPreferences.kt` (`MiscSettings` + Keys)

| Key | Type | Default | Range/Notes |
|-----|------|---------|-------------|
| `lw_beat_reactive_enabled` | Boolean | `false` | Master toggle |
| `lw_beat_reactive_intensity` | Float | `0.5f` | 0.1–1.0 (multiplier on visual effect) |
| `lw_beat_reactive_sync_offset_ms` | Int | `0` | -200..+200 ms (user trim for latency) |

Add to `Keys` object, `MiscSettings` data class, `settings` Flow mapping, and suspend setters.

---

## 2. Settings UI

### Location
`SettingsScreen.kt` → Experimental section (after "System Audio Effects" toggle).

### Components
- **Toggle**: "Beat-reactive visuals" (`lw_beat_reactive_enabled`)
- **Slider**: "Intensity" (0.1–1.0, step 0.05) — disabled when toggle off
- **Slider**: "Sync offset" (-200 to +200 ms, step 10 ms) — disabled when toggle off

Reuse `SettingsToggleCard` + `SettingsSliderCard` patterns already in the file.

---

## 3. Plumbing: Sink → UI

### MusicPlayer
- Expose `beatEnergyTap: StateFlow<BeatEnergySnapshot?>` derived from active `NativeProcessingAudioSink`.
- `NativeProcessingAudioSink.getBeatEnergyTap()` already exists (Phase 1).
- On sink rebuild (track change, path switch), swap to new tap's `latestSnapshot`.

```kotlin
// In MusicPlayer, near audioSinks handling:
private val _beatEnergy = MutableStateFlow<BeatEnergySnapshot?>(null)
val beatEnergy: StateFlow<BeatEnergySnapshot?> = _beatEnergy

// When creating NativeProcessingAudioSink:
sink.getBeatEnergyTap()?.let { tap ->
    applicationScope.launch {
        // Poll at ~30 fps (draw rate)
        while (isActive) {
            _beatEnergy.value = tap.latestSnapshot
            delay(33)
        }
    }
}
```

> **Thread note**: `latestSnapshot` is lock-free (AtomicReference), safe to read from any thread.

### PlayerViewModel
- Add `beatEnergy: StateFlow<BeatEnergySnapshot?>` = `player.beatEnergy`
- Pass to `ExpandedPlayer` composable.

---

## 4. UI Integration: FluidArtworkBackground

### Current pattern (reference)
`FluidArtworkBackground` uses `withFrameNanos` + `graphicsLayer` lambda for draw-phase reads (no recomposition).

### New: BeatReactiveLayer
Create a lightweight wrapper composable that:
1. Reads `beatEnergy` via `collectAsStateWithLifecycle` → `beatSnapshot`
2. In `graphicsLayer` lambda (draw phase), reads `beatSnapshot?.bassEnergy`, `overallRms`, `beatDetected`
3. Exposes as AGSL uniforms or `graphicsLayer` params (scale, colorFilter, etc.)

```kotlin
@Composable
fun BeatReactiveLayer(
    beatEnergy: StateFlow<BeatEnergySnapshot?>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val settings = LocalMiscSettings.current // or passed in
    val enabled = settings.beatReactiveEnabled
    val intensity = settings.beatReactiveIntensity
    val syncOffset = settings.beatReactiveSyncOffsetMs

    val snapshot by beatEnergy.collectAsStateWithLifecycle(initialValue = null)

    // Feed sync offset to tap (once per settings change)
    LaunchedEffect(syncOffset) {
        player.audioSink?.getBeatEnergyTap()?.setLatencyCompensation(
            sinkLatencyFrames = defaultFrames,
            userSyncOffsetMs = syncOffset
        )
    }

    val bassEnergy = if (enabled && snapshot?.isActive == true) snapshot.bassEnergy * intensity else 0f
    val beatDetected = enabled && snapshot?.beatDetected == true

    Box(modifier
        .graphicsLayer {
            // Example: subtle scale pulse on beat
            val pulse = if (beatDetected) 1.02f else 1f
            scaleX = pulse
            scaleY = pulse
            // Or pass to AGSL via graphicsLayer uniform (requires custom shader)
        }
    ) {
        content()
    }
}
```

> **Integration point**: Wrap `FluidArtworkBackground` content or the full-player artwork Box.

### AGSL Uniforms (optional, Phase 2+)
If shader-driven effects desired later:
- Add `uniform float beatBass`, `uniform float beatRms`, `uniform bool beatOnset` to `FLUID_AGSL`
- Pass via `RuntimeShader.setFloatUniform` / `setBoolUniform` in `AgslFluidRenderer.render()`

---

## 5. Glass Capability Gate

Respect existing gates:
- `isDeviceGlassCapable()` (API 31+, not low-RAM, HW accelerated)
- `ForegroundState.rememberIsForeground()` (auto-disable in background)
- Battery saver (check `PowerManager.isPowerSaveMode`)

If any gate fails → treat as `enabled = false`.

---

## 6. Validation Checklist

- [ ] Settings persist across app restarts
- [ ] Toggle off → zero CPU/GPU overhead (no polling, no shader uniforms)
- [ ] Sync offset audible: clap track + visual align at 0 ms, offset ±100 ms visibly shifts
- [ ] Path switches (USB exclusive → DSP → fallback) → `isActive` flips correctly
- [ ] No recomposition on every beat (draw-phase only via `graphicsLayer` / `withFrameNanos`)
- [ ] Build passes (`./gradlew :app:assembleDebug`)

---

## 7. Rollback Plan

If issues:
- Remove 3 DataStore keys
- Remove Settings UI block
- Delete `BeatReactiveLayer` composable
- Remove `beatEnergy` plumbing from `MusicPlayer` + `PlayerViewModel`
- No changes to Phase 1 (`BeatEnergyTap`)

---

## 8. Open Questions (resolve before implementation)

1. **Visual effect style**: Scale pulse? Color shift? Blur radius? Particle burst? → *Recommendation: start with subtle scale pulse (1–2%) + brightness bump on `beatDetected`; extendable to AGSL uniforms later.*

2. **Polling vs callback**: `collectAsStateWithLifecycle` polls at frame rate; `NativeProcessingAudioSink` could push via callback. → *Recommendation: keep polling (33 ms) — simpler, no cross-thread callback complexity, matches `FluidArtworkBackground` pattern.*

3. **Where to apply effect**: Full-player artwork only? Mini-player too? Lyrics background? → *Recommendation: Full-player artwork (Now Playing tab) only for Phase 2.*

4. **Sync offset default**: 0 ms or measure typical device latency? → *Recommendation: 0 ms default; user calibrates manually.*