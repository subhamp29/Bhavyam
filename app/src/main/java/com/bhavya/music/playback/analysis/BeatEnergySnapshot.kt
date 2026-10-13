package com.bhavya.music.playback.analysis

/**
 * Beat energy analysis snapshot published by [BeatEnergyTap].
 *
 * All levels are Float32 full-scale fractions: 1.0 is 0 dBFS.
 * [timestampMs] is the capture time before latency compensation.
 * [isActive] is false when no PCM is available (offload, USB exclusive, paused).
 */
data class BeatEnergySnapshot(
    val bassEnergy: Float = 0f,        // 0..1 (relative to full-scale)
    val overallRms: Float = 0f,        // 0..1
    val beatDetected: Boolean = false, // true for one frame on onset
    val timestampMs: Long = 0L,        // when this frame was captured (pre-latency)
    val isActive: Boolean = false,     // false when no PCM available
) {
    companion object {
        /** Idle snapshot for inactive paths. */
        val IDLE = BeatEnergySnapshot()
    }
}