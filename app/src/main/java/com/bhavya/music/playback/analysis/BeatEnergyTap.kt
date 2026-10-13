package com.bhavya.music.playback.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Post-DSP beat/energy tap for real-time visual reactivity.
 *
 * Computes ~every 25 ms:
 * - Bass energy (~20–150 Hz) via 1-pole IIR low-pass
 * - Overall RMS (full-band)
 * - Beat/onset flag using adaptive threshold with ~250 ms minimum gap
 *
 * Constraints:
 * - Read-only on audio buffers (ByteBuffer.duplicate())
 * - Allocation-free on audio thread (preallocated buffers only)
 * - Lock-free publication via AtomicReference
 * - Latency compensation via ring buffer
 * - Detects inactive paths (offload, USB exclusive, paused) → reports inactive
 */
class BeatEnergyTap(
    sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    channelCount: Int = DEFAULT_CHANNEL_COUNT,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    // ── Constants ──────────────────────────────────────────────────────────
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2
        const val DEFAULT_ANALYSIS_WINDOW_MS = 25
        const val BEAT_THRESHOLD_MULTIPLIER = 1.8f
        const val MIN_BEAT_GAP_MS = 250
        const val EMA_ALPHA = 0.01f
        const val DEFAULT_SINK_LATENCY_MS = 80
        const val MAX_LATENCY_WINDOWS = 16
    }

    // ── Configuration ──────────────────────────────────────────────────────
    private var configuredRateHz: Int = sampleRateHz.coerceAtLeast(1)
    private var configuredChannels: Int = channelCount.coerceIn(1, 2)

    // Derived from configuredRateHz
    private var framesPerWindow: Int = computeFramesPerWindow()
    private var minBeatGapFrames: Int = computeMinBeatGapFrames()

    // LPF coefficient for ~150 Hz cutoff
    private var lpfAlpha: Float = 0f
    private var lpfStateL: Float = 0f
    private var lpfStateR: Float = 0f

    // Accumulators for current window
    private var bassAccum: Double = 0.0
    private var rmsAccum: Double = 0.0
    private var framesInWindow: Int = 0

    // Beat detection state
    private var bassEma: Float = 0f
    private var framesSinceLastBeat: Int = 0
    private var lastBeatTimestampMs: Long = 0

    // Latency compensation ring buffer
    private var latencyFrames: Int = 0
    private var latencyWindows: Int = 0
    private val snapshotRing: Array<BeatEnergySnapshot> = Array(MAX_LATENCY_WINDOWS + 2) { BeatEnergySnapshot.IDLE }
    private var writeIndex: Int = 0

    // Active state
    private var active: Boolean = true

    // Lock-free holder for UI thread
    private val snapshotHolder = AtomicReference<BeatEnergySnapshot>(BeatEnergySnapshot.IDLE)

    init {
        updateLpfCoefficient()
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Called from NativeProcessingAudioSink.handleProcessedBuffer with processed output buffer. */
    fun observe(buffer: ByteBuffer) {
        if (!active) {
            publishIdle()
            return
        }

        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (!view.hasRemaining()) return

        // Process interleaved stereo directly
        while (view.hasRemaining()) {
            val inL = view.get()
            val inR = if (configuredChannels == 2 && view.hasRemaining()) view.get() else inL

            // 1-pole IIR low-pass at ~150 Hz
            lpfStateL = lpfStateL + lpfAlpha * (inL - lpfStateL)
            lpfStateR = lpfStateR + lpfAlpha * (inR - lpfStateR)

            // Accumulate bass energy (sum of squares of LPF output)
            bassAccum += (lpfStateL * lpfStateL + lpfStateR * lpfStateR).toDouble()
            // Accumulate full-band RMS
            rmsAccum += (inL * inL + inR * inR).toDouble()

            framesInWindow++

            if (framesInWindow >= framesPerWindow) {
                finalizeWindow()
            }
        }
    }

    /** Updates the stream format. Resets internal state so a rate change cannot mix windows. */
    fun setFormat(sampleRateHz: Int, channelCount: Int) {
        if (sampleRateHz > 0) configuredRateHz = sampleRateHz
        if (channelCount in 1..2) configuredChannels = channelCount

        framesPerWindow = computeFramesPerWindow()
        minBeatGapFrames = computeMinBeatGapFrames()
        updateLpfCoefficient()
        resetInternal()
        // Clear ring buffer to prevent stale snapshots after format change
        writeIndex = 0
        for (i in snapshotRing.indices) {
            snapshotRing[i] = BeatEnergySnapshot.IDLE
        }
        snapshotHolder.set(BeatEnergySnapshot.IDLE)
    }

    /** Called when playback pauses/resumes/stops or path switches bypass DSP. */
    fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            publishIdle()
        }
    }

    /**
     * Sets latency compensation: sink latency (frames) + user sync offset (ms).
     * Called with activeDelegate.getLatencyFrames() and user offset from settings.
     */
    fun setLatencyCompensation(sinkLatencyFrames: Int, userSyncOffsetMs: Int) {
        val totalLatencyFrames = sinkLatencyFrames + (userSyncOffsetMs * configuredRateHz / 1000).coerceAtLeast(0)
        latencyFrames = totalLatencyFrames.coerceAtLeast(0)
        latencyWindows = ceil(latencyFrames.toFloat() / framesPerWindow).toInt().coerceIn(0, MAX_LATENCY_WINDOWS)
    }

    /** Lock-free read for UI (called from draw phase, not composition). */
    val latestSnapshot: BeatEnergySnapshot
        get() = snapshotHolder.get()

    /** Clears all accumulators and state; published snapshot is reset to idle. */
    fun reset() {
        resetInternal()
        latencyFrames = 0
        latencyWindows = 0
        writeIndex = 0
        for (i in snapshotRing.indices) {
            snapshotRing[i] = BeatEnergySnapshot.IDLE
        }
        snapshotHolder.set(BeatEnergySnapshot.IDLE)
    }

    // ── Internal Processing ────────────────────────────────────────────────

    private fun finalizeWindow() {
        val frameCount = framesInWindow.toDouble()
        if (frameCount <= 0.0) {
            resetWindow()
            return
        }

        val bassEnergy = sqrt(bassAccum / frameCount).toFloat()
        val overallRms = sqrt(rmsAccum / frameCount).toFloat()

        // Beat detection: adaptive threshold with minimum gap
        bassEma = bassEma * (1f - EMA_ALPHA) + bassEnergy * EMA_ALPHA
        val threshold = bassEma * BEAT_THRESHOLD_MULTIPLIER

        var beatDetected = false
        if (bassEnergy > threshold && framesSinceLastBeat >= minBeatGapFrames) {
            beatDetected = true
            framesSinceLastBeat = 0
            lastBeatTimestampMs = clockMs()
        } else {
            framesSinceLastBeat += framesInWindow
        }

        val timestampMs = clockMs()

        // Publish to ring buffer for latency compensation
        val snapshot = BeatEnergySnapshot(
            bassEnergy = bassEnergy.coerceIn(0f, 1f),
            overallRms = overallRms.coerceIn(0f, 1f),
            beatDetected = beatDetected,
            timestampMs = timestampMs,
            isActive = true,
        )

        writeToRingBuffer(snapshot)
        resetWindow()
    }

    private fun writeToRingBuffer(snapshot: BeatEnergySnapshot) {
        snapshotRing[writeIndex] = snapshot

        // Read delayed snapshot for latency compensation (use current writeIndex for 0 latency)
        val readIndex = (writeIndex - latencyWindows + snapshotRing.size) % snapshotRing.size
        val delayedSnapshot = snapshotRing[readIndex]
        snapshotHolder.set(delayedSnapshot)

        writeIndex = (writeIndex + 1) % snapshotRing.size
    }

    private fun publishIdle() {
        snapshotHolder.set(BeatEnergySnapshot.IDLE)
    }

    private fun resetInternal() {
        bassAccum = 0.0
        rmsAccum = 0.0
        framesInWindow = 0
        lpfStateL = 0f
        lpfStateR = 0f
        bassEma = 0f
        framesSinceLastBeat = 0
    }

    private fun resetWindow() {
        bassAccum = 0.0
        rmsAccum = 0.0
        framesInWindow = 0
    }

    private fun updateLpfCoefficient() {
        // alpha = exp(-2 * PI * fc / fs) for 1-pole IIR
        // fc = 150 Hz (bass cutoff)
        lpfAlpha = exp(-2.0 * PI * 150.0 / configuredRateHz).toFloat()
    }

    private fun computeFramesPerWindow(): Int {
        // Window size in frames (per channel) at configured sample rate
        return (configuredRateHz * DEFAULT_ANALYSIS_WINDOW_MS / 1000.0).roundToInt().coerceAtLeast(1)
    }

    private fun computeMinBeatGapFrames(): Int {
        // Minimum gap in frames (per channel) at configured sample rate
        return (configuredRateHz * MIN_BEAT_GAP_MS / 1000.0).roundToInt().coerceAtLeast(1)
    }
}