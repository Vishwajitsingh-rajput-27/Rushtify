package com.decent.usbaudio.media3

import androidx.annotation.OptIn
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * LoadControl wrapper that stops ExoPlayer from loading data when a native
 * audio engine is handling decode+output directly.
 *
 * Without this, ExoPlayer's FlacExtractor reads the same file in parallel
 * with the native engine, causing massive SD card I/O contention through
 * Android's FUSE layer (measured: 20x more reads than necessary).
 *
 * NOTE: Kotlin `by` delegation does NOT forward Java default methods.
 * Every method must be explicitly overridden.
 */
@OptIn(UnstableApi::class)
internal class NativeEngineAwareLoadControl(
    private val delegate: LoadControl,
    private val isNativeEngineActive: () -> Boolean
) : LoadControl {

    // ── Lifecycle ──

    override fun onPrepared() =
        delegate.onPrepared()

    override fun onStopped() =
        delegate.onStopped()

    override fun onReleased() =
        delegate.onReleased()

    // ── Track selection (all overloads) ──

    override fun onTracksSelected(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>
    ) = delegate.onTracksSelected(timeline, mediaPeriodId, renderers, trackGroups, trackSelections)

    @Suppress("DEPRECATION")
    override fun onTracksSelected(
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>
    ) = delegate.onTracksSelected(renderers, trackGroups, trackSelections)

    // ── Allocator ──

    override fun getAllocator(): Allocator =
        delegate.allocator

    // ── Back buffer ──

    override fun getBackBufferDurationUs(): Long =
        delegate.backBufferDurationUs

    override fun retainBackBufferFromKeyframe(): Boolean =
        delegate.retainBackBufferFromKeyframe()

    // ── Core: throttle loading when native engine active ──

    @Suppress("DEPRECATION")
    override fun shouldContinueLoading(
        playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float
    ): Boolean {
        if (isNativeEngineActive()) return false
        return delegate.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)
    }

    @Suppress("DEPRECATION")
    override fun shouldStartPlayback(
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean {
        if (isNativeEngineActive()) return true
        return delegate.shouldStartPlayback(timeline, mediaPeriodId, bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
    }

    @Suppress("DEPRECATION")
    override fun shouldStartPlayback(
        bufferedDurationUs: Long,
        playbackSpeed: Float,
        rebuffering: Boolean,
        targetLiveOffsetUs: Long
    ): Boolean {
        if (isNativeEngineActive()) return true
        return delegate.shouldStartPlayback(bufferedDurationUs, playbackSpeed, rebuffering, targetLiveOffsetUs)
    }
}
