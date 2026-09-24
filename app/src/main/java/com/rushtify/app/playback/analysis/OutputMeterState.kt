package com.rushtify.app.playback.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One published output-meter window measured after DSP.
 *
 * All levels are Float32 full-scale fractions: 1.0 is 0 dBFS. Decibel
 * fields are floored at -120 dBFS so silence has a stable value for UI
 * bars. [totalClippedSamples] is cumulative since the last [reset];
 * [clippedSamplesInWindow] covers only this window.
 */
data class OutputMeterSnapshot(
    val sampleRateHz: Int,
    val channelCount: Int,
    val windowSeconds: Float,
    val peakLinear: Float,
    val peakDbfs: Float,
    val rmsLinear: Float,
    val rmsDbfs: Float,
    val dcOffset: Float,
    val clippedSamplesInWindow: Long,
    val totalClippedSamples: Long,
    val clippedInWindow: Boolean,
    val measuredAtMs: Long,
) {
    companion object {
        fun initial(): OutputMeterSnapshot = OutputMeterSnapshot(
            sampleRateHz = 0,
            channelCount = 0,
            windowSeconds = 0f,
            peakLinear = 0f,
            peakDbfs = DspMath.MIN_DBFS,
            rmsLinear = 0f,
            rmsDbfs = DspMath.MIN_DBFS,
            dcOffset = 0f,
            clippedSamplesInWindow = 0L,
            totalClippedSamples = 0L,
            clippedInWindow = false,
            measuredAtMs = 0L,
        )
    }
}

/**
 * StateFlow holder for the output meter.
 *
 * Kept separate from the tap so UI layers observe [snapshots] without
 * touching audio code, and unit tests can drive publishing through a fake
 * clock. This holder performs no audio processing and changes nothing
 * audible; it only stores the latest published window.
 */
class OutputMeterState(
    initial: OutputMeterSnapshot = OutputMeterSnapshot.initial(),
) {
    private val _snapshots = MutableStateFlow(initial)

    /** Latest published per-second window. */
    val snapshots: StateFlow<OutputMeterSnapshot> = _snapshots.asStateFlow()

    /** Synchronous read of the latest window (convenient for tests). */
    val current: OutputMeterSnapshot
        get() = _snapshots.value

    fun publish(snapshot: OutputMeterSnapshot) {
        _snapshots.value = snapshot
    }

    fun reset() {
        _snapshots.value = OutputMeterSnapshot.initial()
    }
}
