package com.rushtify.app.util

/**
 * Utility for splitting and handling composite multi-artist strings cleanly.
 * Handles separators such as commas, ampersands, slashes, "feat.", "ft.", "with", and "x".
 */
object ArtistHelper {
    private val SEPARATOR_REGEX = Regex(
        "(?:\\s*,\\s*|\\s*&\\s*|\\s*;\\s*|\\s*\\/\\s*|\\s*\\+\\s*|\\s+(?:ft\\.?|feat\\.?|featuring|with|and|x|X)\\s+)",
        RegexOption.IGNORE_CASE
    )

    private val STAT_PATTERN = Regex(
        """^[\d.,\s]+[kKmMbB]?\s*(?:plays?|views?|streams?|listens?|scrobbles?|tracks?)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Detects strings like "973 plays", "3.4K plays", "4.1K views", "Track 16"
     * that InnerTube / YouTube Music sometimes exposes in place of an artist.
     */
    fun isPlayCountOrStat(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val trimmed = raw.trim()
        if (STAT_PATTERN.matches(trimmed)) return true
        val lower = trimmed.lowercase()
        if (lower.endsWith(" plays") || lower.endsWith(" play") ||
            lower.endsWith(" views") || lower.endsWith(" view") ||
            lower.endsWith(" streams") || lower.endsWith(" stream") ||
            lower.endsWith(" scrobbles") || lower.endsWith(" scrobble") ||
            lower.endsWith(" tracks") || lower.endsWith(" track")
        ) {
            val prefix = trimmed.substringBeforeLast(' ').trim()
            if (prefix.matches(Regex("""^[\d.,\s]+[kKmMbB]?$"""))) return true
        }
        return false
    }

    /**
     * Splits multi-artist strings (e.g. "Arijit Singh, Badshah", "Alan Walker feat. Au/Ra", "Drake & 21 Savage")
     * into clean, individual artist names.
     */
    fun splitArtists(rawArtist: String?): List<String> {
        if (rawArtist.isNullOrBlank() || isPlayCountOrStat(rawArtist)) return emptyList()
        val trimmed = rawArtist.trim()
        val parts = trimmed.split(SEPARATOR_REGEX)
            .map { it.trim().trim(',', '&', '/', ';').trim() }
            .filter { it.isNotBlank() && !isPlayCountOrStat(it) }
            .distinct()
        return if (parts.isNotEmpty()) parts else listOf(trimmed)
    }

    /**
     * Returns the primary / first artist from a composite string.
     */
    fun primaryArtist(rawArtist: String?): String {
        if (rawArtist.isNullOrBlank() || isPlayCountOrStat(rawArtist)) return ""
        return splitArtists(rawArtist).firstOrNull() ?: rawArtist.orEmpty()
    }
}

