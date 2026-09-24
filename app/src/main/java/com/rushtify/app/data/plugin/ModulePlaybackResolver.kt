package com.rushtify.app.data.plugin

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a track through installed provider modules — one call per song.
 *
 * JSON-only (LWP2) world: modules carry config (URL + secret) that the app
 * consumes natively in LosslessMusicApi, so there is nothing module-side to
 * execute. The legacy QuickJS script runtime has been removed entirely, so
 * this resolver always falls through to the normal chain (direct backend,
 * then YouTube) and only maps quality tiers for callers. Signatures are kept
 * so existing call sites compile untouched; every method is non-throwing.
 */
@Singleton
class ModulePlaybackResolver @Inject constructor() {
    /** Repo quality tier (28/27/7/6/5/4) mapped to the module quality id. */
    fun moduleQuality(repoQuality: Int): String = when (repoQuality) {
        28 -> "ATMOS"
        27 -> "UHD"
        7 -> "HI_RES_96"
        6 -> "HD"
        5 -> "SD"
        4 -> "LOW"
        else -> "UHD"
    }

    /** Always null: JSON modules resolve natively, script modules unsupported. */
    suspend fun resolve(title: String, artist: String, repoQuality: Int): SegmentedStreamDescriptor? =
        null

    /** Quality badge from the app fallback labels; modules carry no JS policy. */
    suspend fun badgeFor(descriptor: SegmentedStreamDescriptor, fallback: String): String =
        fallback

    /** Whether the extension wants its downloads transcoded (JSON: never lossless). */
    suspend fun shouldTranscode(descriptor: SegmentedStreamDescriptor): Boolean =
        false
}
