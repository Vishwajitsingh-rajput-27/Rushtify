package com.rushtify.app.widget

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/**
 * Same swatch-priority logic as
 * [com.rushtify.app.data.repository.NowPlayingPaletteExtractor] (vibrant →
 * dominant → muted), applied directly to a bitmap already in hand instead
 * of fetching one by URL — the widget already gets its art bitmap straight
 * from MediaMetadata, so there's no network/Coil round trip needed here.
 */
object WidgetArtAccent {
    fun extractHex(art: Bitmap): String? = runCatching {
        val palette = Palette.from(art).generate()
        val swatch = palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch ?: return null
        val rgb = swatch.rgb
        "#%02X%02X%02X".format((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
    }.getOrNull()
}
