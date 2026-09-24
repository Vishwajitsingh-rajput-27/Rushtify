package com.rushtify.app.playback

import com.google.common.truth.Truth.assertThat
import com.rushtify.app.playback.analysis.DspMath
import com.rushtify.app.playback.analysis.OutputMeterState
import com.rushtify.app.playback.analysis.OutputTap
import com.rushtify.app.playback.analysis.RatePathSource
import com.rushtify.app.playback.analysis.RatePathTracker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

/**
 * JVM-only response tests for the playback analysis package.
 *
 * What is exercised here and why: the production DSP ([DspProcessor] in
 * cpp) is JNI-only and cannot run in local unit tests, so these tests
 * cover the JVM-testable Kotlin equivalents in `playback.analysis`
 * instead — a [DspMath.JvmBiquad] mirror of each biquad stage shape, the
 * [DspMath.BitPerfectBypass] passthrough, the [DspMath.JvmLimiter]
 * ceiling, and the analysis math itself (peak/RMS/clip/DC from
 * [DspMath.analyzeWindow] and [OutputTap], plus ReplayGain dB arithmetic).
 * No NDK, device, Robolectric or Media3 dependency is required.
 */
class DspResponseTest {

    private val sampleRateHz = 48_000.0

    @Test
    fun biquadStagesConvergeOnImpulse() {
        val stages = DspMath.clarityChain(sampleRateHz)
        assertThat(stages).isNotEmpty()
        for (stage in stages) {
            val response = impulseResponse(stage.filter, length = 4096)
            for (value in response) {
                assertThat(value.isFinite()).isTrue()
                assertThat(value.isNaN()).isFalse()
            }
            val headPeak = response.take(256).maxOf { abs(it) }
            val tailPeak = response.drop(2048).maxOf { abs(it) }
            assertThat(headPeak).isLessThan(32f)
            assertThat(tailPeak).isLessThan(0.05f)
        }
    }

    @Test
    fun equalizerBandsConvergeOnImpulse() {
        for (frequency in DspMath.EQ_FREQUENCIES_HZ) {
            val band = DspMath.eqBand(sampleRateHz, frequency, 6.0)
            val response = impulseResponse(band, length = 2048)
            for (value in response) {
                assertThat(value.isFinite()).isTrue()
                assertThat(value.isNaN()).isFalse()
            }
            val tailPeak = response.drop(1024).maxOf { abs(it) }
            assertThat(tailPeak).isLessThan(0.05f)
        }
    }

    @Test
    fun bitPerfectBypassIsSampleExact() {
        val input = floatArrayOf(
            0f, 1f, -1f, 0.5f, -0.5f, 1e-30f, -1e-30f,
            0.999999f, -0.999999f, Float.MIN_VALUE, 123.456f, -987.654f,
        )
        val output = DspMath.BitPerfectBypass.copy(input)
        assertThat(output.toList()).isEqualTo(input.toList())

        val target = FloatArray(input.size)
        DspMath.BitPerfectBypass.copyInto(input, target)
        assertThat(target.toList()).isEqualTo(input.toList())
    }

    @Test
    fun limiterNeverExceedsZeroDbfsOnSineBurst() {
        val rate = 48_000
        val frequency = 440.0
        val burst = FloatArray(rate) { index ->
            sin(2.0 * PI * frequency * index / rate).toFloat()
        }
        val limited = DspMath.JvmLimiter.limit(burst)
        // Input itself must be untouched (copy-only contract).
        assertThat(abs(burst.maxOf { abs(it) }) - 1f).isWithin(1e-5f)
        for (value in limited) {
            assertThat(value.isFinite()).isTrue()
            assertThat(value.isNaN()).isFalse()
            assertThat(abs(value)).isAtMost(1.0f)
        }

        // A hot +3.5 dB signal (equalizer-boost model) must still be capped.
        val hot = FloatArray(rate) { index -> burst[index] * 1.5f }
        val hotLimited = DspMath.JvmLimiter.limit(hot)
        for (value in hotLimited) {
            assertThat(value.isFinite()).isTrue()
            assertThat(abs(value)).isAtMost(1.0f)
        }
        assertThat(hot.map { abs(it) }.maxOrNull() ?: 0f).isGreaterThan(1.0f)
    }

    @Test
    fun meterMathPeakRmsClipAndDc() {
        val silence = FloatArray(1024)
        val silenceStats = DspMath.analyzeWindow(silence)
        assertThat(silenceStats.peakLinear).isWithin(1e-9f).of(0f)
        assertThat(silenceStats.rmsLinear).isWithin(1e-9f).of(0f)
        assertThat(silenceStats.dcOffset).isWithin(1e-9f).of(0f)
        assertThat(silenceStats.clippedSamples).isEqualTo(0L)

        val dc = FloatArray(1000) { 0.25f }
        val dcStats = DspMath.analyzeWindow(dc)
        assertThat(dcStats.peakLinear).isWithin(1e-6f).of(0.25f)
        assertThat(dcStats.rmsLinear).isWithin(1e-6f).of(0.25f)
        assertThat(dcStats.dcOffset).isWithin(1e-6f).of(0.25f)
        assertThat(dcStats.clippedSamples).isEqualTo(0L)

        val mixed = floatArrayOf(0.5f, 1.0f, -1.0f, 1.5f, -0.99f)
        val mixedStats = DspMath.analyzeWindow(mixed)
        assertThat(mixedStats.peakLinear).isWithin(1e-6f).of(1.5f)
        assertThat(mixedStats.clippedSamples).isEqualTo(3L)

        val amplitude = 0.5f
        val sine = FloatArray(48_000) { index ->
            (amplitude * sin(2.0 * PI * 1000.0 * index / 48_000.0)).toFloat()
        }
        val sineStats = DspMath.analyzeWindow(sine)
        val expectedRms = amplitude / sqrt(2.0).toFloat()
        assertThat(sineStats.rmsLinear).isWithin(0.001f).of(expectedRms)
        assertThat(sineStats.peakLinear).isWithin(0.001f).of(amplitude)
        assertThat(abs(sineStats.dcOffset)).isLessThan(0.001f)
    }

    @Test
    fun replayGainArithmeticRoundTrips() {
        assertThat(DspMath.dbToGain(0.0)).isWithin(1e-12).of(1.0)
        assertThat(DspMath.gainToDb(1.0)).isWithin(1e-12).of(0.0)
        assertThat(DspMath.dbToGain(-6.0)).isWithin(1e-9).of(0.5011872336272722)
        assertThat(DspMath.gainToDb(2.0)).isWithin(1e-9).of(6.020599913279624)

        val input = floatArrayOf(0.5f, -0.25f, 0.125f)
        val down6 = DspMath.applyGainDb(input, -6.0)
        // Source array is never mutated.
        assertThat(input.toList()).isEqualTo(listOf(0.5f, -0.25f, 0.125f))
        val expected = 0.5011872336272722f
        assertThat(down6[0]).isWithin(1e-6f).of(0.5f * expected)
        assertThat(down6[1]).isWithin(1e-6f).of(-0.25f * expected)
        assertThat(down6[2]).isWithin(1e-6f).of(0.125f * expected)
    }

    @Test
    fun outputTapIsSideEffectFreeAndPublishesPerSecond() {
        val state = OutputMeterState()
        val tap = OutputTap(
            sampleRateHz = 1_000,
            channelCount = 1,
            meterState = state,
            clockMs = { 7L },
        )
        val before = state.current
        assertThat(before.peakLinear).isWithin(0f).of(0f)

        val input = FloatArray(500) { 0.5f }
        val pristine = input.copyOf()
        tap.observe(input)
        // Tap must copy only: caller array unchanged, nothing published yet.
        assertThat(input.toList()).isEqualTo(pristine.toList())
        assertThat(state.current.measuredAtMs).isEqualTo(0L)

        tap.observe(FloatArray(500) { 0.5f })
        val snapshot = state.current
        assertThat(snapshot.measuredAtMs).isEqualTo(7L)
        assertThat(snapshot.windowSeconds).isWithin(1e-6f).of(1f)
        assertThat(snapshot.peakLinear).isWithin(1e-6f).of(0.5f)
        assertThat(snapshot.rmsLinear).isWithin(1e-6f).of(0.5f)
        assertThat(snapshot.dcOffset).isWithin(1e-6f).of(0.5f)
        assertThat(snapshot.clippedInWindow).isFalse()
        assertThat(snapshot.totalClippedSamples).isEqualTo(0L)
    }

    @Test
    fun outputTapCountsClipsAndPreservesByteBuffer() {
        val state = OutputMeterState()
        val tap = OutputTap(
            sampleRateHz = 8,
            channelCount = 1,
            meterState = state,
            clockMs = { 9L },
        )
        val buffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.LITTLE_ENDIAN)
        val floats = buffer.asFloatBuffer()
        floats.put(floatArrayOf(0.1f, 0.2f, 1.0f, -1.0f, 1.5f, 0.3f, -0.4f, 0.0f))
        buffer.position(0)
        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        tap.observeFloatBuffer(buffer)

        assertThat(buffer.position()).isEqualTo(positionBefore)
        assertThat(buffer.limit()).isEqualTo(limitBefore)
        val snapshot = state.current
        assertThat(snapshot.clippedInWindow).isTrue()
        assertThat(snapshot.clippedSamplesInWindow).isEqualTo(3L)
        assertThat(snapshot.totalClippedSamples).isEqualTo(3L)
        assertThat(snapshot.peakLinear).isWithin(1e-6f).of(1.5f)
    }

    @Test
    fun ratePathTrackerTracksVerdictInputs() {
        val tracker = RatePathTracker(clockMs = { 123L })
        assertThat(tracker.current.source).isEqualTo(RatePathSource.SPEAKER)

        tracker.updateInput(rateHz = 44_100, channels = 2, depthBits = 16)
        tracker.updateOutput(rateHz = 44_100, channels = 2)
        tracker.updateRoute(mixerInPath = false, source = RatePathSource.USB_EXCLUSIVE)

        val candidate = tracker.current
        assertThat(candidate.isRateMatched).isTrue()
        assertThat(candidate.isBitPerfectCandidate).isTrue()
        assertThat(candidate.measuredAtMs).isEqualTo(123L)

        tracker.updateRoute(mixerInPath = true, source = RatePathSource.SPEAKER)
        assertThat(tracker.current.isBitPerfectCandidate).isFalse()

        tracker.updateOutput(rateHz = 48_000, channels = 2)
        assertThat(tracker.current.isRateMatched).isFalse()
    }

    private fun impulseResponse(filter: DspMath.JvmBiquad, length: Int): FloatArray {
        filter.reset()
        val output = FloatArray(length)
        for (index in 0 until length) {
            output[index] = filter.tick(if (index == 0) 1f else 0f, 0)
        }
        // The idle channel must stay silent while channel 0 rings.
        filter.reset()
        filter.tick(1f, 0)
        assertThat(filter.tick(0f, 1)).isWithin(1e-9f).of(0f)
        filter.reset()
        return output
    }
}
