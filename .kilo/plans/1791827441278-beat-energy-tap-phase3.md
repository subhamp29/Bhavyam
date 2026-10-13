# Phase 3 Plan: Advanced Beat-Reactive Shader Effects (AGSL)

## Goal
Extend the Phase 2 `BeatReactiveLayer` with AGSL shader uniforms for richer visual effects: bass-driven color shifts, RMS brightness modulation, beat-onset pulse waves, and twisted geometry reactions.

---

## 1. AGSL Shader Uniforms

### Add to `FLUID_AGSL` (FluidArtworkBackground.kt)

```agsl
// Beat-reactive uniforms (add after line 88)
uniform float beatBass;      // 0–1, low-frequency energy
uniform float beatRms;       // 0–1, overall loudness
uniform bool beatOnset;      // true on beat detection
uniform float beatIntensity; // 0.1–1.0, user intensity setting
```

### Shader Effects to Implement

| Effect | Uniform | Visual |
|--------|---------|--------|
| **Bass color shift** | `beatBass` | Shift hue toward warm (bass) / cool (treble) |
| **RMS brightness** | `beatRms` | Modulate `brightness` uniform dynamically |
| **Beat pulse wave** | `beatOnset` + `beatBass` | Expanding ring / twist radius spike |
| **Intensity scaling** | `beatIntensity` | Master multiplier on all beat effects |

---

## 2. AgslFluidRenderer Updates

### In `render()` method (line ~317):

```kotlin
fun render(
    // ... existing params ...
    beatBass: Float = 0f,
    beatRms: Float = 0f,
    beatOnset: Boolean = false,
    beatIntensity: Float = 1f,
) {
    // ... existing setup ...
    
    // Beat-reactive uniforms
    shader.setFloatUniform("beatBass", beatBass * beatIntensity)
    shader.setFloatUniform("beatRms", beatRms * beatIntensity)
    shader.setBoolUniform("beatOnset", beatOnset)
    shader.setFloatUniform("beatIntensity", beatIntensity)
    
    // Dynamic brightness based on RMS
    val dynamicBrightness = if (extraBlur) 0.32f else BRIGHTNESS + beatRms * 0.3f * beatIntensity
    shader.setFloatUniform("brightness", dynamicBrightness)
    
    // Dynamic twist radius on beat
    val dynamicTwistRadius = (maxOf(width, height) + minOf(width, height)) / 2f * TWIST_REACH * (1f + beatBass * 0.5f * beatIntensity)
    shader.setFloatUniform("twistRadius", dynamicTwistRadius)
    
    paint.shader = shader
    canvas.drawRect(0f, 0f, width, height, paint)
}
```

---

## 3. BeatReactiveLayer → Shader Bridge

### Update `BeatReactiveLayer` to extract values and pass to renderer

```kotlin
@Composable
fun BeatReactiveLayer(
    beatEnergy: StateFlow<BeatEnergySnapshot?>,
    modifier: Modifier = Modifier,
    settings: MiscSettings,
    content: @Composable () -> Unit,
) {
    // ... existing glass/foreground/battery gates ...
    
    val snapshot by beatEnergy.collectAsStateWithLifecycle(initialValue = null)
    
    val enabled = isGlassCapable && isForeground && !isPowerSaveMode &&
        settings.beatReactiveEnabled && (snapshot?.isActive == true)
    
    val intensity = settings.beatReactiveIntensity.coerceIn(0.1f, 1.0f)
    
    // Extract beat data for shader
    val beatBass = if (enabled) (snapshot?.bassEnergy?.coerceIn(0f, 1f) ?: 0f) * intensity else 0f
    val beatRms = if (enabled) (snapshot?.overallRms?.coerceIn(0f, 1f) ?: 0f) * intensity else 0f
    val beatOnset = enabled && (snapshot?.beatDetected == true)
    
    // Pass to content via CompositionLocal or direct parameter
    // (FluidArtworkBackground's AgslFluidRenderer is internal, so we wrap)
    val beatShaderParams = remember {
        mutableStateOf(BeatShaderParams(beatBass, beatRms, beatOnset, intensity))
    }
    beatShaderParams.value = BeatShaderParams(beatBass, beatRms, beatOnset, intensity)
    
    // Provide via CompositionLocal for AgslFluidRenderer to read
    CompositionLocalProvider(LocalBeatShaderParams provides beatShaderParams) {
        Box(modifier.graphicsLayer {
            // Keep scale pulse as fallback for non-AGSL path
            if (beatOnset) { scaleX = 1.02f; scaleY = 1.02f } else { scaleX = 1f; scaleY = 1f }
        }) {
            content()
        }
    }
}

data class BeatShaderParams(
    val bass: Float,
    val rms: Float,
    val onset: Boolean,
    val intensity: Float,
)

val LocalBeatShaderParams = staticCompositionLocalOf { BeatShaderParams(0f, 0f, false, 1f) }
```

### In `FluidArtworkBackground`, read from CompositionLocal in `AgslFluidRenderer.render()` call:

```kotlin
// Inside renderFluidContent or where AgslFluidRenderer.render is called
val beatParams = LocalBeatShaderParams.current
agslRenderer.render(
    // ... existing params ...
    beatBass = beatParams.bass,
    beatRms = beatParams.rms,
    beatOnset = beatParams.onset,
    beatIntensity = beatParams.intensity,
)
```

---

## 4. Shader Effect Details (FLUID_AGSL)

### Bass Color Shift (add in `main()`):
```agsl
// Shift hue based on bass: bass=1 → warm (orange), bass=0 → cool (blue)
float hueShift = beatBass * 0.15; // ~54 degrees hue shift max
float3 hsv = rgb2hsv(c);
hsv.x = (hsv.x + hueShift) % 1.0;
c = hsv2rgb(hsv);
```

### Beat Pulse Wave (add in `main()`):
```agsl
if (beatOnset) {
    float dist = length(fragCoord - float2(width/2, height/2));
    float wave = smoothstep(0.0, 50.0 * beatIntensity, dist - seconds * 2000.0);
    c += float3(0.3, 0.2, 0.1) * beatBass * beatIntensity * (1.0 - wave);
}
```

### Helper functions (add to AGSL):
```agsl
float3 rgb2hsv(float3 c) {
    float4 K = float4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
    float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

float3 hsv2rgb(float3 c) {
    float4 K = float4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
    float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
```

---

## 5. Additional Visual Surfaces (Optional)

| Surface | Component | Priority |
|---------|-----------|----------|
| Lyrics background | `LyricsBackground` / `FluidLyricsBackground` | Medium |
| Mini-player artwork | `MiniPlayerArtwork` | Low |
| Lock screen widget | `WidgetArtwork` | Low |

For Phase 3, focus on **full-player artwork only** (already integrated). Other surfaces can reuse `BeatReactiveLayer` + `BeatShaderParams` pattern.

---

## 6. Performance Considerations

- **Uniform updates**: Only 4 floats + 1 bool per frame — negligible
- **Shader complexity**: hue shift + conditional pulse wave adds ~5 ALU ops
- **Battery gate**: Already respected via `BeatReactiveLayer` gating
- **Fallback**: `graphicsLayer` scale pulse works on API < 31 (no AGSL)

---

## 7. Validation Checklist

- [ ] AGSL compiles on API 31+ (Tiramisu)
- [ ] Shader effects visible: bass hue shift, RMS brightness, beat pulse
- [ ] Intensity slider scales all effects proportionally
- [ ] Sync offset still works (Phase 2 plumbing unchanged)
- [ ] No jank: `graphicsLayer` + AGSL both draw-phase, no recomposition
- [ ] Graceful degradation: non-AGSL path shows scale pulse only
- [ ] Battery/background gates disable shader updates
- [ ] Build passes (`./gradlew :app:assembleDebug`)

---

## 8. Rollback Plan

- Remove 4 AGSL uniforms from `FLUID_AGSL`
- Revert `AgslFluidRenderer.render()` signature
- Remove `BeatShaderParams` + `LocalBeatShaderParams`
- `BeatReactiveLayer` keeps `graphicsLayer` scale pulse fallback
- No changes to Phase 1/2 plumbing

---

## 9. Open Questions

1. **Hue shift direction**: Warm on bass (current) vs. cool on bass? → *Recommend warm (orange/red) for bass, feels more "physical"*
2. **Pulse wave speed**: Fixed 2000 px/sec or tempo-synced? → *Fixed speed simpler; tempo-sync needs BPM from BeatEnergySnapshot (not yet exposed)*
3. **Saturation modulation**: Also modulate saturation on beat? → *Yes, subtle: `saturation * (1 + beatBass * 0.3)`*
4. **Mini-player/lyrics**: Include in Phase 3 or defer? → *Defer to Phase 4; full-player is primary use case*