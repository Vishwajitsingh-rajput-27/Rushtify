package com.rushtify.app.widget

import android.graphics.Bitmap

/**
 * Holds the current track's art bitmap in memory, keyed by track. The
 * widget already gets this exact bitmap handed to it by
 * [com.rushtify.app.service.MediaScrobbleListenerService] (straight off
 * MediaMetadata) — decoding it back from the on-disk PNG cache
 * (widget_now_playing_art.png) on every single widget recomposition was
 * pure wasted work and the main source of the widget feeling slow to
 * update. The file cache still exists (see [WidgetUpdater]) purely as a
 * cold-start fallback for right after a process restart, before the
 * scrobbler service has reconnected and republished anything.
 */
object WidgetArtCache {
    @Volatile var trackKey: String? = null
    @Volatile var bitmap: Bitmap? = null

    fun set(trackKey: String, bitmap: Bitmap) {
        this.trackKey = trackKey
        this.bitmap = bitmap
    }

    fun clear() {
        trackKey = null
        bitmap = null
    }
}
