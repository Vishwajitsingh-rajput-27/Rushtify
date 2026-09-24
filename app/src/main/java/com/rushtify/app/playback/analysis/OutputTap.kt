package com.rushtify.app.playback.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Post-DSP measurement tap in the style of a Media3 TeeAudioProcessor.
 *
 * Intended placement is directly after the DSP stage so the meter observes
 * exactly what reaches the sink. The tap is strictly side-effect-free on
 * audio:
 *
 * - FloatArray inputs are only read, never written to.
 * - ByteBuffer inputs are read through [ByteBuffer.duplicate], so the
 *   caller's position, limit and bytes are untouched.
 * - The tap returns nothing into the audio path; results leave only via
 *   [OutputMeterState.snapshots].
 *
 * Measurement windows are one second of interleaved samples
 * (sampleRate * channelCount). When a window fills, peak/RMS levels, the
 * clipping counter and the DC-offset estimate (window mean) are published
 * as an [OutputMeterSnapshot]. Partial windows stay internal until full,
 * so the published cadence is a stable per-second tick while playing.
 */
class OutputTap(
    sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    channelCount: Int = DEFAULT_CHANNEL_COUNT,
    private val meterState: OutputMeterState = OutputMeterState(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    private var configuredRateHz: Int = sampleRateHz.coerceAtLeast(1)
    private var configuredChannels: Int = channelCount.coerceIn(1, MAX_CHANNELS)

    private var sum: Double = 0.0
    private var sumSquares: Double = 0.0
    private var peak: Float = 0f
    private var windowSamples: Long = 0L
    private var windowClips: Long = 0L
    private var totalClips: Long = 0L

    /** State holder receiving the per-second snapshots. */
    val state: OutputMeterState
        get() = meterState

    /**
     * Updates the stream format. Resets the in-progress window so a rate
     * change cannot mix windows measured under different clocks.
     */
    fun setFormat(sampleRateHz: Int, channelCount: Int) {
        synchronized(lock) {
            if (sampleRateHz > 0) configuredRateHz = sampleRateHz
            if (channelCount in 1..MAX_CHANNELS) configuredChannels = channelCount
            resetWindowLocked()
        }
    }

    /** Clears accumulators and counters; the published snapshot is kept. */
    fun reset() {
        synchronized(lock) {
            resetWindowLocked()
            totalClips = 0L
        }
    }

    /** Observes interleaved Float32 samples without modifying them. */
    fun observe(samples: FloatArray) {
        observe(samples, 0, samples.size)
    }

    /** Observes a slice of interleaved Float32 samples without modifying it. */
    fun observe(samples: FloatArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) {
            "Slice [$offset, ${offset + length}) is outside ${samples.size} samples"
        }
        if (length == 0) return
        synchronized(lock) {
            for (index in offset until offset + length) {
                accumulateLocked(samples[index])
            }
            maybePublishLocked()
        }
    }

    /**
     * Observes interleaved little-endian Float32 frames from [buffer].
     * Reads through a duplicate, so [buffer]'s position, limit and content
     * are preserved exactly.
     */
    fun observeFloatBuffer(buffer: ByteBuffer) {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (!view.hasRemaining()) return
        synchronized(lock) {
            while (view.hasRemaining()) {
                accumulateLocked(view.get())
            }
            maybePublishLocked()
        }
    }

    private fun accumulateLocked(value: Float) {
        if (!value.isFinite()) {
            windowSamples++
            windowClips++
            totalClips++
            return
        }
        val magnitude = abs(value)
        if (magnitude > peak) peak = magnitude
        sum += value
        sumSquares += value.toDouble() * value.toDouble()
        windowSamples++
        if (magnitude >= DspMath.CLIP_THRESHOLD_LINEAR) {
            windowClips++
            totalClips++
        }
    }

    private fun maybePublishLocked() {
        val target = configuredRateHz.toLong() * configuredChannels.toLong()
        if (windowSamples < target || target <= 0L) return
        val count = windowSamples.toDouble()
        val rms = sqrt(sumSquares / count).toFloat()
        val dc = (sum / count).toFloat()
        val framesPerChannel = windowSamples.toFloat() / configuredChannels.toFloat()
        meterState.publish(
            OutputMeterSnapshot(
                sampleRateHz = configuredRateHz,
                channelCount = configuredChannels,
                windowSeconds = framesPerChannel / configuredRateHz.toFloat(),
                peakLinear = peak,
                peakDbfs = DspMath.amplitudeToDbfs(peak),
                rmsLinear = rms,
                rmsDbfs = DspMath.amplitudeToDbfs(rms),
                dcOffset = if (dc.isFinite()) dc else 0f,
                clippedSamplesInWindow = windowClips,
                totalClippedSamples = totalClips,
                clippedInWindow = windowClips > 0L,
                measuredAtMs = clockMs(),
            ),
        )
        resetWindowLocked()
    }

    private fun resetWindowLocked() {
        sum = 0.0
        sumSquares = 0.0
        peak = 0f
        windowSamples = 0L
        windowClips = 0L
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_CHANNEL_COUNT = 2
        const val MAX_CHANNELS = 8
    }
}
