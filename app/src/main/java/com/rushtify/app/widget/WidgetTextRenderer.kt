package com.rushtify.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.rushtify.app.R
import kotlin.math.ceil

/**
 * The real fix for wanting the app's actual Google Sans Flex font inside a
 * home-screen widget: RemoteViews' TextView has no supported API to set a
 * custom Typeface at all (Glance's own `androidx.glance.text.FontFamily`
 * only recognizes the built-in system families), so a plain `Text()`
 * composable can never show it, no matter how it's styled. This renders
 * the exact string as a small bitmap using the font file that's already
 * bundled in res/font — reusing the same variable-axis approach as
 * Type.kt (wght/wdth/ROND) — and the widget displays that bitmap as an
 * `Image()` instead of text.
 *
 * Trade-off, stated plainly: this is real per-string bitmap rendering,
 * not a font hack — it correctly shows the exact typeface, weight, width
 * and roundness, but it does NOT participate in the system's font-size
 * accessibility setting (a fixed pixel size once rendered), and it's
 * re-rendered on every widget update rather than being "free" the way
 * native text is. Both were flagged before doing this; going ahead
 * because the exact font was the explicit ask.
 */
object WidgetTextRenderer {

    private val typefaceCache = mutableMapOf<String, Typeface>()

    /**
     * @param maxWidthDp widest this string is allowed to render at; longer
     *   text is truncated with an ellipsis rather than overflowing the
     *   widget's fixed RemoteViews layout (which can't wrap/scroll text).
     */
    fun render(
        context: Context,
        text: String,
        colorArgb: Int,
        sizeSp: Float,
        weight: Float,
        width: Float,
        round: Float,
        maxWidthDp: Float,
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val maxWidthPx = maxWidthDp * density

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = loadTypeface(context, weight, width, round)
            textSize = sizeSp * density
            color = colorArgb
        }

        val fitted = fitText(paint, text.ifBlank { " " }, maxWidthPx)
        val fm = paint.fontMetrics
        val textWidth = ceil(paint.measureText(fitted)).toInt().coerceAtLeast(1)
        val textHeight = ceil(fm.descent - fm.ascent).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(textWidth, textHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(fitted, 0f, -fm.ascent, paint)
        return bitmap
    }

    private fun fitText(paint: Paint, text: String, maxWidthPx: Float): String {
        if (paint.measureText(text) <= maxWidthPx) return text
        val ellipsis = "\u2026"
        val availablePx = maxWidthPx - paint.measureText(ellipsis)
        if (availablePx <= 0f) return ellipsis
        val fitCount = paint.breakText(text, true, availablePx, null)
        return text.substring(0, fitCount.coerceIn(0, text.length)) + ellipsis
    }

    /** wght/wdth/ROND match the same variation-axis approach Type.kt uses
     *  for in-app text — this is the widget's equivalent of `gsFlex()`.
     *
     *  `Typeface.Builder` has no constructor that takes an existing
     *  `Typeface` (only a File/FileDescriptor/asset path) — the real API
     *  for applying variation-axis settings to an already-resolved
     *  resource font is `android.graphics.fonts.Font.Builder` (needs the
     *  resource itself, API 26+) combined with
     *  `Typeface.CustomFallbackBuilder` (API 29+) to turn that single Font
     *  back into a usable Typeface. Below API 29 this falls back to the
     *  font's own default (non-variable-tuned) instance — still the real
     *  bundled typeface, just without the per-style weight/width/round
     *  tuning on those older versions. */
    private fun loadTypeface(context: Context, weight: Float, width: Float, round: Float): Typeface {
        val key = "$weight-$width-$round"
        typefaceCache[key]?.let { return it }
        val base = ResourcesCompat.getFont(context, R.font.google_sans_flex) ?: Typeface.DEFAULT
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val font = android.graphics.fonts.Font.Builder(context.resources, R.font.google_sans_flex)
                    .setFontVariationSettings("'wght' ${weight.toInt()}, 'wdth' $width, 'ROND' $round")
                    .build()
                val family = android.graphics.fonts.FontFamily.Builder(font).build()
                Typeface.CustomFallbackBuilder(family).build()
            }.getOrDefault(base)
        } else {
            base // custom variation instances need API 29+; older versions get the font's own default instance
        }
        typefaceCache[key] = resolved
        return resolved
    }
}
