# Phase 4 Plan: Beat-Reactive Effects on Additional Visual Surfaces

## Goal
Extend Phase 3 beat-reactive AGSL shader effects to three additional surfaces:
1. **Lyrics background** (Medium priority) — Already uses `FluidArtworkBackground`, just needs beat data wiring
2. **Mini-player artwork** (Low priority) — Uses `PlayerArtwork`, needs `BeatReactiveLayer` wrapper
3. **Lock screen widget** (Low priority) — RemoteViews-based, requires different architecture

---

## Current State (Phase 3 Complete)

| Surface | Component | Beat-Reactive Status |
|---------|-----------|---------------------|
| Full-player artwork (Now Playing) | `FluidArtworkBackground` | ✅ Done — AGSL uniforms + `BeatReactiveLayer` + `CompositionLocal` |
| Full-player artwork (Lyrics tab) | `FluidArtworkBackground` | ⚠️ Uses component but **not passing** `beatEnergy`/`settings` |
| Mini-player artwork | `PlayerArtwork` inside `MiniPlayer` | ❌ No beat effects |
| Lock screen widget | `WidgetViews` / `WidgetUpdater` | ❌ RemoteViews — separate process |

---

## 1. Lyrics Background (Medium)

### Analysis
- `PlayerHost.kt:1869` calls `FluidArtworkBackground()` for both Now Playing and Lyrics tabs
- Parameters passed: `track`, `modifier`, `extraBlur`, `rotatingBackgroundEnabled`, `fallback`
- **Missing**: `beatEnergy` and `settings` — defaults to `MutableStateFlow(null)` and empty `MiscSettings()`

### Fix
In `PlayerHost.kt` `ExpandedPlayer` composable, pass existing `beatEnergy` and `settings` to `FluidArtworkBackground`:

```kotlin
FluidArtworkBackground(
    track = track,
    modifier = Modifier.fillMaxSize(),
    extraBlur = currentTab == FullPlayerTab.LYRICS,
    rotatingBackgroundEnabled = rotatingBackgroundEnabled,
    beatEnergy = beatEnergy,           // ADD: from line 305 / 838
    settings = settings,               // ADD: from line 836
    fallback = { ... }
)
```

### Validation
- Lyrics tab background shows hue shift / pulse on beat
- No performance regression (same AGSL renderer)
- Settings gate respected (battery/foreground/glass)

---

## 2. Mini-Player Artwork (Low)

### Analysis
- `PlayerHost.kt:1054` uses `PlayerArtwork(track, Modifier.fillMaxSize(), 18.dp)` inside `MiniPlayer`
- `PlayerArtwork` is a simple Coil-based image with blur/corner options
- Mini-player is always visible when music plays — high visibility surface
- `beatEnergy` and `settings` available in `PlayerHost` scope (lines 305, 303)

### Options

| Option | Description | Pros | Cons |
|--------|-------------|------|------|
| **A. Wrap `PlayerArtwork` with `BeatReactiveLayer`** | Add `BeatReactiveLayer(beatEnergy, settings) { PlayerArtwork(...) }` | Reuses existing AGSL path; minimal code | Mini-player is small (56dp) — shader may be overkill |
| **B. Add `graphicsLayer` scale pulse only** | Apply Phase 2-style scale pulse to artwork Box | Lightweight; works on all API levels | No color/brightness effects |
| **C. New lightweight shader** | Simplified AGSL for small surfaces | Tailored performance | More code to maintain |

### Recommendation: Option A + Size Guard
- Wrap `PlayerArtwork` with `BeatReactiveLayer` when size > threshold (e.g., tablet/landscape)
- On phone portrait (56dp), skip shader — just keep `graphicsLayer` pulse fallback
- `BeatReactiveLayer` already has `isGlassCapable` gate (API 31+)

### Implementation Sketch
```kotlin
// In MiniPlayer, around line 1047-1055
Box(Modifier.size(56.dp)) {
    val isTablet = isTabletOrWideScreen()
    if (isTablet && settings.beatReactiveEnabled) {
        BeatReactiveLayer(
            beatEnergy = beatEnergy,
            settings = settings,
            content = {
                Surface(...) { PlayerArtwork(track, Modifier.fillMaxSize(), 18.dp) }
            }
        )
    } else {
        Surface(...) { PlayerArtwork(track, Modifier.fillMaxSize(), 18.dp) }
    }
}
```

### Validation
- Tablet/landscape mini-player shows beat effects
- Phone portrait unchanged (performance)
- No recomposition on beat (draw-phase only)

---

## 3. Lock Screen Widget (Low)

### Analysis
- `WidgetViews.kt` builds `RemoteViews` layouts (`widget_now_playing.xml` etc.)
- `WidgetUpdater.kt` pushes bitmap to `R.id.widget_art` via `setImageViewBitmap`
- Runs in **separate process** (system widget host) — no Compose, no AGSL, no GPU access
- `WidgetViews.buildLayout()` generates artwork bitmap on background thread

### Constraints
- No AGSL / RuntimeShader available in widget process
- Bitmap must be pre-rendered and pushed via `RemoteViews`
- 30fps animation not feasible (battery / IPC limits)
- Can update ~1-2x/sec via `AppWidgetManager.updateAppWidget()`

### Options

| Option | Description | Feasibility |
|--------|-------------|-------------|
| **A. Pre-render beat frames** | Generate 5-10 frames offline, cycle on beat | Low — no real-time audio in widget |
| **B. CPU fallback in `WidgetUpdater`** | Apply same `fluidCopies` + rotation + tint on CPU | Medium — `Canvas` drawing on bitmap |
| **C. Static with pulse tint** | On beat, tint artwork bitmap warm/bright | High — simple `ColorMatrix` |
| **D. Skip widget** | Defer to Phase 5+ | N/A |

### Recommendation: Option C (Static + Pulse Tint)
- In `WidgetUpdater.updateWidget()`, detect beat via shared preference / in-memory flag
- On beat: apply `ColorMatrix` tint (warm hue + brightness boost) to artwork bitmap
- Revert after 200-300ms
- Uses existing `beatEnergy` from `MusicPlaybackService` (line 910 `widgetSignature`)

### Implementation Sketch
```kotlin
// In WidgetUpdater.kt, where artBitmap is prepared
var lastBeatMs = 0L
val artBitmap = if (beatEnergy?.beatDetected == true && 
    System.currentTimeMillis() - lastBeatMs < 300) {
    applyBeatTint(originalArtBitmap, beatEnergy.bassEnergy)
    lastBeatMs = System.currentTimeMillis()
} else {
    originalArtBitmap
}
views.setImageViewBitmap(R.id.widget_art, artBitmap)
```

### Validation
- Widget shows subtle pulse on beat (warm tint + brightness)
- No battery drain (no continuous animation)
- Works on all Android versions

---

## Dependencies & Data Flow

```
MusicPlayer.beatEnergy (StateFlow)
    │
    ├─► PlayerHost.beatEnergy (line 305)
    │       ├─► ExpandedPlayer ► FluidArtworkBackground (Now Playing) ✅
    │       ├─► ExpandedPlayer ► FluidArtworkBackground (Lyrics) ⚠️ NEEDS WIRE
    │       └─► MiniPlayer ► PlayerArtwork ❌ NEEDS WRAPPER
    │
    └─► MusicPlaybackService ► WidgetUpdater ► RemoteViews ❌ DIFFERENT PROCESS
```

---

## Task List

### Lyrics Background (1 task)
- [ ] Pass `beatEnergy` and `settings` to `FluidArtworkBackground` in `PlayerHost.kt` for Lyrics tab

### Mini-Player Artwork (2 tasks)
- [ ] Add `BeatReactiveLayer` wrapper around `PlayerArtwork` in `MiniPlayer` (tablet/landscape only)
- [ ] Add size guard to skip shader on small surfaces (< 100dp)

### Lock Screen Widget (3 tasks)
- [ ] Expose beat detection flag to `WidgetUpdater` (via `MusicPlaybackService` shared state)
- [ ] Add `applyBeatTint(Bitmap, bassEnergy)` helper in `WidgetViews` or `WidgetUpdater`
- [ ] Apply tint on beat, revert after 300ms in widget update cycle

---

## Rollback Plan
- Each surface independently revertible
- Lyrics: remove two parameters from call site
- Mini-player: remove `BeatReactiveLayer` wrapper
- Widget: remove tint logic from `WidgetUpdater`

---

## Validation Checklist
- [ ] Lyrics tab: hue shift, brightness pulse, twist wave visible
- [ ] Mini-player (tablet): beat effects visible, phone unchanged
- [ ] Widget: subtle warm/bright pulse on beat, no animation otherwise
- [ ] All gates respected: battery saver, background, glass capability
- [ ] Settings intensity slider scales all new surfaces
- [ ] Sync offset applies to lyrics/mini-player (widget uses service time)
- [ ] Build passes (`./gradlew :app:assembleDebug`)