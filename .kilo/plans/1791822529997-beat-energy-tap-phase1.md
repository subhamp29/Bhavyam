# Phase 1 Plan: BeatEnergyTap.kt — Audio Analysis (No UI)

## Goal
Create `playback/analysis/BeatEnergyTap.kt` next to `OutputTap.kt` that computes ~every 20–30 ms:
- **Bass energy** (~20–150 Hz) via cheap low-pass filter (1-pole IIR) or small FFT
- **Overall RMS** (full-band)
- **Beat/onset flag** using adaptive threshold (energy vs moving average) with ~250 ms minimum gap

**Constraints:**
- Read-only on audio buffers (`ByteBuffer.duplicate()` like `OutputTap`)
- Allocation-free on audio thread (preallocated buffers only, no boxing, no logging)
- Publish to lock-free holder (volatile/Atomic float bits) read by UI
- Compensate output latency (sink latency + user sync offset)
- Detect inactive paths (offload, USB exclusive, paused, stopped) → report "inactive"
- Unit tests with synthetic kick-drum PCM

---

## 1. File & Class Structure

```
app/src/main/java/com/bhavya/music/playback/analysis/
├── BeatEnergyTap.kt          # NEW
└── BeatEnergySnapshot.kt     # NEW (data class for published values)

app/src/test/java/com/bhavya/music/playback/analysis/
└── BeatEnergyTapTest.kt      # NEW (unit tests)
```

### BeatEnergySnapshot.kt (data class)
```kotlin
data class BeatEnergySnapshot(
    val bassEnergy: Float,        // 0..1 (relative to full-scale)
    val overallRms: Float,        // 0..1
    val beatDetected: Boolean,    // true for one frame on onset
    val timestampMs: Long,        // when this frame was captured (pre-latency)
    val isActive: Boolean,        // false when no PCM available (offload, USB exclusive, paused)
)
```

### BeatEnergyTap.kt — Public API
```kotlin
class BeatEnergyTap(
    sampleRateHz: Int = 48000,
    channelCount: Int = 2,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    // Called from NativeProcessingAudioSink.handleProcessedBuffer with processed output buffer
    fun observe(buffer: ByteBuffer) { ... }
    
    // Called when format changes (sample rate / channels)
    fun setFormat(sampleRateHz: Int, channelCount: Int) { ... }
    
    // Called when playback pauses/resumes/stops
    fun setActive(active: Boolean) { ... }
    
    // Latency compensation: sink latency (frames) + user sync offset (ms)
    fun setLatencyCompensation(sinkLatencyFrames: Int, userSyncOffsetMs: Int) { ... }
    
    // Lock-free read for UI (called from draw phase, not composition)
    val latestSnapshot: BeatEnergySnapshot
        get() = ... // volatile/Atomic read
    
    fun reset() { ... }
}
```

---

## 2. Audio Processing Algorithm

### Frame Rate Target
- Process every **N frames ≈ 20–30 ms** at output sample rate
- At 48 kHz: 960–1440 frames per analysis window
- Use `processor.nativeOutputSampleRate` from `NativePcmAudioProcessor`

### Bass Energy (20–150 Hz) — 1-Pole IIR Low-Pass
Cheaper than FFT, allocation-free, streaming-friendly.

```kotlin
// Precomputed coefficient for target cutoff ~150 Hz
// alpha = exp(-2 * PI * fc / fs)
private var lpfAlpha: Float = 0f
private var lpfStateL: Float = 0f
private var lpfStateR: Float = 0f

// On format change:
lpfAlpha = exp(-2.0 * PI * 150.0 / sampleRateHz).toFloat()

// Per sample (interleaved stereo):
val inL = sample
val inR = sample
lpfStateL = lpfStateL + lpfAlpha * (inL - lpfStateL)
lpfStateR = lpfStateR + lpfAlpha * (inR - lpfStateR)
bassAccum += lpfStateL * lpfStateL + lpfStateR * lpfStateR
rmsAccum += inL * inL + inR * inR
```

**Why 1-pole IIR:** 2 multiplies + 1 add per channel per sample. No buffers, no FFT tables, deterministic latency.

### Overall RMS
Accumulate `sumSquares` same loop. At window end: `sqrt(sumSquares / frameCount)`.

### Beat/Onset Detection — Adaptive Threshold
```kotlin
// Exponential moving average of bass energy (slow, ~1–2 s time constant)
bassEma = bassEma * 0.99f + bassEnergy * 0.01f  // tuned per window rate

// Adaptive threshold: k * EMA (k ~ 1.5–2.0)
val threshold = bassEma * BEAT_THRESHOLD_MULTIPLIER

// Minimum gap between beats (~250 ms → ~10 windows at 25 ms)
if (bassEnergy > threshold && framesSinceLastBeat > MIN_BEAT_GAP_FRAMES) {
    beatDetected = true
    framesSinceLastBeat = 0
} else {
    framesSinceLastBeat += framesInWindow
}
```

**Parameters (tunable constants):**
- `ANALYSIS_WINDOW_MS = 25` → ~1920 frames @ 48 kHz stereo
- `BEAT_THRESHOLD_MULTIPLIER = 1.8f`
- `MIN_BEAT_GAP_MS = 250` → frames = `MIN_BEAT_GAP_MS * sampleRateHz / 1000`
- `EMA_ALPHA = 0.01f` (per window)

---

## 3. Latency Compensation

**Problem:** Tap sees audio ~80 ms before it's heard (AudioTrack buffer + DAC latency).

**Solution:** Delay published snapshots by `sinkLatencyFrames + userSyncOffsetMs`.

Implementation:
- Ring buffer of `BeatEnergySnapshot` (capacity ≈ max latency / windowMs + 2)
- `writeIndex` advances each analysis window
- `readIndex = writeIndex - latencyFrames / framesPerWindow`
- On `setLatencyCompensation`, compute `latencyFrames = sinkLatencyFrames + (userSyncOffsetMs * sampleRateHz / 1000)`
- Default sink latency: **80 ms** (configurable via setting in Phase 2)

**API:**
```kotlin
fun setLatencyCompensation(sinkLatencyFrames: Int, userSyncOffsetMs: Int) {
    // sinkLatencyFrames from AudioTrack.getLatencyFrames() or default ~3840 @ 48kHz
    // userSyncOffsetMs from settings (Phase 2)
}
```

---

## 4. Inactive Path Detection

`setActive(false)` called when:
- `NativeProcessingAudioSink` enters exclusive USB (`usbExclusive == true`)
- Bit-perfect direct path active (`bitPerfectRequested && bitPerfectAtConfigure && !processingActive`)
- Fallback path active (`!processingActive && !usbExclusive`)
- Playback paused/stopped (MusicPlayer calls `setActive(false)`)

When inactive: publish `BeatEnergySnapshot(bassEnergy=0f, overallRms=0f, beatDetected=false, isActive=false)`.

---

## 5. Integration Point: NativeProcessingAudioSink

**Location:** `handleProcessedBuffer` (line ~593-623), after `processor.getOutput()`:

```kotlin
// Existing code:
val output = processor.getOutput()
pendingOutput = output
// ... 

// NEW: Feed beat tap with processed output (post-DSP, pre-AudioTrack)
beatEnergyTap?.observe(output.duplicate())
```

**Requirements:**
- `BeatEnergyTap` instance held in `NativeProcessingAudioSink`
- Created in `configure()` when `processingActive` path chosen
- `setFormat` called with `processedFormat.sampleRate`, `processedFormat.channelCount`
- `setActive(true/false)` on `play()` / `pause()` / path switches
- `setLatencyCompensation` with `activeDelegate.getLatencyFrames()` (or default)

---

## 6. Lock-Free Publication

```kotlin
// In BeatEnergyTap:
private val snapshotHolder = AtomicReference<BeatEnergySnapshot>(BeatEnergySnapshot.IDLE)

fun publish(snapshot: BeatEnergySnapshot) {
    snapshotHolder.set(snapshot)  // volatile write
}

val latestSnapshot: BeatEnergySnapshot
    get() = snapshotHolder.get()  // volatile read
```

**No locks, no allocations** — single atomic reference swap.

---

## 7. Unit Tests (BeatEnergyTapTest.kt)

### Test 1: Input Buffer Unchanged
```kotlin
@Test fun observe_doesNotMutateInput() {
    val buffer = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    // Fill with known pattern
    val original = FloatArray(buffer.capacity())
    buffer.get(original)
    buffer.rewind()
    
    tap.observe(buffer)
    
    val after = FloatArray(buffer.capacity())
    buffer.rewind()
    buffer.get(after)
    assertArrayEquals(original, after)
}
```

### Test 2: Beat Detection on Synthetic Kick
```kotlin
@Test fun detectBeat_onKickPattern() {
    // Generate 1 s of silence + 60 Hz sine kick every 500 ms
    val sr = 48000
    val kick = generateKick(sr, 60, 0.8f)  // 60 Hz, -2 dBFS
    val silence = FloatArray(sr / 2)  // 500 ms
    
    val buffer = ByteBuffer.allocateDirect((kick.size + silence.size) * 4 * 2)
        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    // Interleave: kick, silence, kick, silence...
    
    tap.setFormat(sr, 2)
    tap.setActive(true)
    tap.observe(buffer)
    
    // Advance through windows, collect beatDetected flags
    val beats = collectBeats(tap, expectedCount = 2)
    assertEquals(2, beats.size)
    assertTrue(beats[0] > 400 && beats[0] < 600)  // ~500 ms ± tolerance
}
```

### Test 3: Inactive Path Reports Zero
```kotlin
@Test fun inactivePath_reportsIdle() {
    tap.setActive(false)
    tap.observe(someBuffer)
    assertFalse(tap.latestSnapshot.isActive)
    assertEquals(0f, tap.latestSnapshot.bassEnergy)
}
```

### Test 4: Latency Compensation Delays Output
```kotlin
@Test fun latencyCompensation_delaysSnapshot() {
    tap.setLatencyCompensation(4800, 0)  // 100 ms @ 48 kHz
    // Feed 4 windows (100 ms)
    // latestSnapshot should still be IDLE until 5th window
}
```

---

## 8. Constants & Tuning (top of BeatEnergyTap.kt)

```kotlin
companion object {
    const val DEFAULT_ANALYSIS_WINDOW_MS = 25
    const val BEAT_THRESHOLD_MULTIPLIER = 1.8f
    const val MIN_BEAT_GAP_MS = 250
    const val EMA_ALPHA = 0.01f
    const val DEFAULT_SINK_LATENCY_MS = 80
    const val MAX_LATENCY_WINDOWS = 16  // covers ~400 ms @ 25 ms/window
}
```

---

## 9. Dependencies & Imports

- `java.nio.ByteBuffer`, `ByteOrder`
- `java.util.concurrent.atomic.AtomicReference`
- `kotlin.math.sqrt`, `exp`, `PI`
- No Coroutines, no Flow, no allocations in hot path

---

## 10. Validation Checklist (for implementer)

- [ ] `BeatEnergyTap.observe` reads via `duplicate()` — input buffer position/limit/content unchanged
- [ ] No allocations in `observe` (preallocated `FloatArray` for stereo split if needed, or process interleaved directly)
- [ ] Bass LPF coefficient recomputed on `setFormat`
- [ ] Beat detection respects minimum gap
- [ ] `setActive(false)` → `isActive=false` published immediately
- [ ] Latency ring buffer wraps correctly, no OOB
- [ ] Unit tests pass (buffer unchanged, kick detection, inactive, latency)
- [ ] Build passes (`./gradlew :app:assembleDebug`)

---

## 11. Open Questions (resolve before implementation)

1. **Stereo vs mono:** Process interleaved directly (sum L+R for mono bass)? Or keep separate and average? → **Recommendation: average L+R for both bass and RMS** (matches perceived loudness).

2. **Window overlap?** Non-overlapping windows are simpler. Overlapping (50%) gives smoother onset detection but 2x CPU. → **Recommendation: non-overlapping, 25 ms windows**.

3. **Latency source:** `AudioTrack.getLatencyFrames()` requires API 23+. Fallback to default 80 ms. → **Recommendation: try `getLatencyFrames()`, catch + default**.

4. **Where to hold `BeatEnergyTap` instance?** In `NativeProcessingAudioSink` as nullable var, created only when `processingActive` path chosen. → **Recommendation: yes, nullable in sink**.

5. **Thread safety:** `observe` called from renderer thread. `latestSnapshot` read from UI thread (draw phase). AtomicReference handles this. → **Confirmed safe**.

---

## 12. Rollback Plan

If Phase 1 causes issues:
- `BeatEnergyTap` is standalone — delete the file
- Remove 3-4 lines in `NativeProcessingAudioSink` that call it
- No other code touched

---

## Next Phase Dependency

Phase 2 (Settings) needs:
- `BeatEnergyTap` publishing `latestSnapshot`
- `NativeProcessingAudioSink` exposing tap instance (or `MusicPlayer` holding reference)
- Settings keys for: enabled, intensity, sync offset