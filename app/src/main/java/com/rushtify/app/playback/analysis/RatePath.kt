package com.rushtify.app.playback.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Physical output route behind the current stream. */
enum class RatePathSource {
    SPEAKER,
    BT,
    USB_FRAMEWORK,
    USB_EXCLUSIVE,
}

/**
 * Data holder for the Now Playing verdict UI.
 *
 * Tracks the source stream format into the pipeline
 * ([inRateHz]/[inChannels]/[inDepthBits]) against the actual output
 * format ([outRateHz]/[outChannels]), whether the platform mixer sits in
 * the path ([mixerInPath]), and which route carries the stream ([source]).
 * UI wiring is separate; this file only provides the data class and the
 * updater API on [RatePathTracker]. All fields are passive observations —
 * updating them never touches audio.
 */
data class RatePathSnapshot(
    val inRateHz: Int?,
    val inChannels: Int?,
    val inDepthBits: Int?,
    val outRateHz: Int,
    val outChannels: Int,
    val mixerInPath: Boolean,
    val source: RatePathSource,
    val measuredAtMs: Long,
) {
    /** True when the output rate matches the source rate exactly. */
    val isRateMatched: Boolean
        get() = inRateHz != null && inRateHz > 0 && inRateHz == outRateHz

    /**
     * Conservative bit-perfect candidacy: rate-matched, no platform mixer
     * in the path, and an exclusive USB route. The UI combines this with
     * the DSP-bypass and gain checks it already owns.
     */
    val isBitPerfectCandidate: Boolean
        get() = isRateMatched && !mixerInPath && source == RatePathSource.USB_EXCLUSIVE

    companion object {
        fun initial(): RatePathSnapshot = RatePathSnapshot(
            inRateHz = null,
            inChannels = null,
            inDepthBits = null,
            outRateHz = 0,
            outChannels = 0,
            mixerInPath = true,
            source = RatePathSource.SPEAKER,
            measuredAtMs = 0L,
        )
    }
}

/**
 * Mutable holder for [RatePathSnapshot] with granular updaters.
 *
 * Each updater stamps [RatePathSnapshot.measuredAtMs] from [clockMs] and
 * leaves unrelated fields untouched, so the decoder path, the sink path
 * and the route monitor can each report independently. Thread-safe for
 * single-writer-per-field use via StateFlow's volatile value.
 */
class RatePathTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    initial: RatePathSnapshot = RatePathSnapshot.initial(),
) {
    private val _path = MutableStateFlow(initial)

    /** Latest rate-path snapshot for the verdict UI. */
    val path: StateFlow<RatePathSnapshot> = _path.asStateFlow()

    /** Synchronous read of the latest snapshot (convenient for tests). */
    val current: RatePathSnapshot
        get() = _path.value

    /** Replaces the whole snapshot (restores, tests, one-shot syncs). */
    fun update(snapshot: RatePathSnapshot) {
        _path.value = snapshot
    }

    /** Reports the decoded source format. Null clears an unknown field. */
    fun updateInput(rateHz: Int?, channels: Int?, depthBits: Int?) {
        _path.value = _path.value.copy(
            inRateHz = rateHz?.takeIf { it > 0 },
            inChannels = channels?.takeIf { it > 0 },
            inDepthBits = depthBits?.takeIf { it > 0 },
            measuredAtMs = clockMs(),
        )
    }

    /** Reports the actual output format opened on the sink. */
    fun updateOutput(rateHz: Int, channels: Int) {
        _path.value = _path.value.copy(
            outRateHz = rateHz.coerceAtLeast(0),
            outChannels = channels.coerceAtLeast(0),
            measuredAtMs = clockMs(),
        )
    }

    /** Reports the platform route behind the stream. */
    fun updateRoute(mixerInPath: Boolean, source: RatePathSource) {
        _path.value = _path.value.copy(
            mixerInPath = mixerInPath,
            source = source,
            measuredAtMs = clockMs(),
        )
    }

    fun reset() {
        _path.value = RatePathSnapshot.initial()
    }
}
