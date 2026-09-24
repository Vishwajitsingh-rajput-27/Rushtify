package com.rushtify.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

fun isRtlText(text: CharSequence?): Boolean {
    if (text.isNullOrBlank()) return false
    var rtlCount = 0
    var ltrCount = 0
    var firstStrongRtl: Boolean? = null
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> {
                if (firstStrongRtl == null) firstStrongRtl = true
                rtlCount++
            }
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> {
                if (firstStrongRtl == null) firstStrongRtl = false
                ltrCount++
            }
        }
        i += Character.charCount(codePoint)
    }
    return (firstStrongRtl == true) || (rtlCount > 0 && rtlCount >= ltrCount)
}

data class LyricSyllable(
    val timeMs: Long,
    val durationMs: Long,
    val text: String,
    val isBackground: Boolean = false,
    /** True when this fragment continues the previous token with no space
     *  (Apple Music `part` words like "with"+"drawals"). Views must not
     *  insert a visual separator before it. */
    val appendToPrevious: Boolean = false,
)

data class LyricLine(
    val timeMs: Long,
    val durationMs: Long = 0L,
    val text: String,
    val syllables: List<LyricSyllable> = emptyList(),
    val transliteration: String? = null,
    val transliterationSyllables: List<LyricSyllable> = emptyList(),
) {
    val hasSyllables: Boolean get() = syllables.isNotEmpty()
    val isRtl: Boolean get() = isRtlText(text) || syllables.any { isRtlText(it.text) }
}

sealed interface LyricsResult {
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val isWordSynced: Boolean = false,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
        val source: String? = null,
    ) : LyricsResult {
        val isRtl: Boolean get() = lines.any { it.isRtl } || isRtlText(plainLyrics)
    }

    data object Empty : LyricsResult
    data class Error(val message: String) : LyricsResult
}

@Singleton
class LyricsRepository @Inject constructor(
    private val lyricsPlusApi: LyricsPlusApi,
    private val betterLyricsApi: BetterLyricsApi,
    private val kugouApi: KugouLyricsApi,
    private val lrclibApi: LrclibLyricsApi,
    private val appleMusicApi: AppleMusicLyricsApi,
    private val settingsPreferences: com.rushtify.app.data.local.SettingsPreferences,
    private val downloadedTrackDao: dagger.Lazy<com.rushtify.app.data.local.db.DownloadedTrackDao>,
) {
    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        forceRefresh: Boolean = false,
        wordByWord: Boolean = true,
        onPartialResult: suspend (LyricsResult.Success) -> Unit = {},
    ): LyricsResult = withContext(Dispatchers.Default) {
        // User-chosen provider order: tried first, automatic fallback to the
        // rest when it returns nothing. Part of the cache key so switching
        // providers never serves the previous provider's result.
        val preferred = runCatching {
            settingsPreferences.settings.first().lyricsProvider
        }.getOrDefault(com.rushtify.app.data.local.LyricsProvider.AUTO)
        val cacheKey = "${artist.trim().lowercase()}|${title.trim().lowercase()}|${album?.trim()?.lowercase()}|$durationSeconds|$wordByWord|${preferred.id}"
        if (!forceRefresh) {
            cache[cacheKey]?.takeIf {
                !wordByWord || (it is LyricsResult.Success && (it.isWordSynced || it.isInstrumental))
            }?.let { return@withContext it }
        }

        var localLyrics: LyricsResult.Success? = null
        run {
            // 0. LOCAL OFFLINE: Check if this track is downloaded with embedded or saved lyrics
            val localTrack = runCatching {
                val dao = downloadedTrackDao.get()
                dao.findByTitleAndArtist(title, artist)
                    ?: dao.findByTrackKey("${artist.lowercase()}_${title.lowercase()}")
            }.getOrNull()
            if (localTrack != null && (localTrack.hasLyrics || !localTrack.plainLyrics.isNullOrBlank() || !localTrack.syncedLyrics.isNullOrBlank() || !localTrack.lrcFilePath.isNullOrBlank())) {
                var synced = localTrack.syncedLyrics
                if (synced.isNullOrBlank() && !localTrack.lrcFilePath.isNullOrBlank()) {
                    val lrcFile = java.io.File(localTrack.lrcFilePath)
                    if (lrcFile.exists() && lrcFile.length() > 0) {
                        synced = runCatching { lrcFile.readText() }.getOrNull()
                    }
                }
                if (!synced.isNullOrBlank()) {
                    val lines = parseLrc(synced)
                    if (lines.isNotEmpty()) {
                        val result = LyricsResult.Success(
                            lines = lines,
                            isSynced = true,
                            isWordSynced = false,
                            plainLyrics = localTrack.plainLyrics,
                            isInstrumental = false,
                            source = "Downloaded Lyrics (LRC)",
                        )
                        if (!wordByWord) {
                            cache[cacheKey] = result
                            return@withContext result
                        }
                        localLyrics = result
                        onPartialResult(result)
                        return@run
                    }
                }
                val plain = localTrack.plainLyrics
                if (!plain.isNullOrBlank()) {
                    val result = LyricsResult.Success(
                        lines = emptyList(),
                        isSynced = false,
                        isWordSynced = false,
                        plainLyrics = plain.trim(),
                        isInstrumental = false,
                        source = "Downloaded Lyrics (Plain)",
                    )
                    if (!wordByWord) {
                        cache[cacheKey] = result
                        return@withContext result
                    }
                    localLyrics = result
                    onPartialResult(result)
                }
            }
        }

        // LRCLIB chosen explicitly: try it before the word providers so
        // the preference is honored. Word-sync found later still wins;
        // its line-sync result outranks every other fallback below.
        var lrclibAttempted = false
        var preferredFallback: LyricsResult.Success? = null
        if (preferred == com.rushtify.app.data.local.LyricsProvider.LRCLIB) {
            lrclibAttempted = true
            val first = fetchFromLrclib(title, artist, album, durationSeconds)
            if (first != null) {
                if (first.isWordSynced || first.isInstrumental) {
                    cache[cacheKey] = first
                    return@withContext first
                }
                preferredFallback = first
                onPartialResult(first)
            }
        }

        if (wordByWord) {
            // Preferred provider goes first with a bounded head start. Only
            // word-sync (or instrumental) short-circuits; its line-sync
            // result is stashed as the top fallback so a word-synced hit
            // from anywhere else still wins.
            if (preferred.isWordProvider) {
                val single = withTimeoutOrNull(PREFERRED_HEAD_START_MS) {
                    fetchPreferredWord(
                        preferred = preferred,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSeconds = durationSeconds,
                    )
                }
                if (single != null) {
                    if (single.isWordSynced || single.isInstrumental) {
                        cache[cacheKey] = single
                        return@withContext single
                    }
                    preferredFallback = single
                    onPartialResult(single)
                }
            }
            val wordResult = coroutineScope {
                val requests = mutableListOf(
                    async<LyricsResult.Success?> {
                        fetchWordFromLyricsPlus(title, artist, album, durationSeconds)
                    },
                    async<LyricsResult.Success?> {
                        fetchWordFromBetterLyrics(title, artist)
                    },
                    async<LyricsResult.Success?> {
                        fetchWordFromKugou(title, artist, durationSeconds)
                    },
                )
                var lineFallback: LyricsResult.Success? = null
                try {
                    while (requests.isNotEmpty()) {
                        val (request, result) = select {
                            requests.forEach { request ->
                                request.onAwait { request to it }
                            }
                        }
                        requests.remove(request)
                        if (result?.isWordSynced == true) return@coroutineScope result
                        if (result != null && lineFallback == null) {
                            lineFallback = result
                            onPartialResult(result)
                        }
                    }
                    lineFallback
                } finally {
                    requests.forEach { it.cancel() }
                }
            }
            if (wordResult?.isWordSynced == true) {
                cache[cacheKey] = wordResult
                return@withContext wordResult
            }
            // Explicit choice outranks any other line-sync source.
            preferredFallback?.let {
                cache[cacheKey] = it
                return@withContext it
            }
            if (wordResult != null) {
                cache[cacheKey] = wordResult
                return@withContext wordResult
            }
        }
        localLyrics?.let { return@withContext it }

        // Fall back to LRCLIB line-by-line sync (skipped when it was
        // already tried as the preferred provider above).
        if (!lrclibAttempted) {
            fetchFromLrclib(title, artist, album, durationSeconds)?.let { result ->
                cache[cacheKey] = result
                return@withContext result
            }
        }
        preferredFallback?.let {
            cache[cacheKey] = it
            return@withContext it
        }

        // 4. FALLBACK: If all fail, return Empty (no lyrics)
        LyricsResult.Empty
    }

    /** LRCLIB attempt shared by the preferred-first path and the fallback
     *  below. Returns null when LRCLIB has nothing usable. */
    private suspend fun fetchFromLrclib(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        val lrclibRecord = try {
            lrclibApi.fetchLyrics(title, artist, album, durationSeconds)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return null

        if (lrclibRecord.instrumental == true) {
            return LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = null,
                isInstrumental = true,
                source = "LRCLIB (Instrumental)",
            )
        }

        val synced = lrclibRecord.syncedLyrics
        if (!synced.isNullOrBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) {
                return LyricsResult.Success(
                    lines = lines,
                    isSynced = true,
                    isWordSynced = false,
                    plainLyrics = lrclibRecord.plainLyrics,
                    isInstrumental = false,
                    source = "LRCLIB (Line-Sync)",
                )
            }
        }

        val plain = lrclibRecord.plainLyrics
        if (!plain.isNullOrBlank()) {
            return LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = plain.trim(),
                isInstrumental = false,
                source = "LRCLIB (Plain)",
            )
        }
        return null
    }

    /** Single preferred word-provider attempt (null = fall through to the
     *  automatic race). Same mapping as the race entries below. */
    private suspend fun fetchPreferredWord(
        preferred: com.rushtify.app.data.local.LyricsProvider,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsResult.Success? = when (preferred) {
        com.rushtify.app.data.local.LyricsProvider.APPLE_MUSIC ->
            appleMusicApi.fetchLyrics(title, artist, album, durationSeconds)
        com.rushtify.app.data.local.LyricsProvider.LYRICS_PLUS ->
            fetchWordFromLyricsPlus(title, artist, album, durationSeconds)
        com.rushtify.app.data.local.LyricsProvider.BETTER_LYRICS ->
            fetchWordFromBetterLyrics(title, artist)
        com.rushtify.app.data.local.LyricsProvider.KUGOU ->
            fetchWordFromKugou(title, artist, durationSeconds)
        else -> null
    }

    private suspend fun fetchWordFromLyricsPlus(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        try {
            val wordResponse = lyricsPlusApi.fetchWordLyrics(title, artist, album, durationSeconds)
            if (wordResponse != null && !wordResponse.lyrics.isNullOrEmpty()) {
                val lines = wordResponse.lyrics.map { line ->
                    val syllables = line.syllabus?.map { syl ->
                        LyricSyllable(
                            timeMs = syl.time,
                            durationMs = syl.duration,
                            text = syl.text,
                            isBackground = syl.isBackground,
                        )
                    } ?: emptyList()

                    val transliterationSyllables = line.transliteration?.syllabus?.map { syl ->
                        LyricSyllable(
                            timeMs = syl.time,
                            durationMs = syl.duration,
                            text = syl.text,
                            isBackground = syl.isBackground,
                        )
                    } ?: emptyList()

                    LyricLine(
                        timeMs = line.time,
                        durationMs = line.duration,
                        text = line.text,
                        syllables = syllables,
                        transliteration = line.transliteration?.text,
                        transliterationSyllables = transliterationSyllables,
                    )
                }.sortedBy { it.timeMs }

                if (lines.isNotEmpty()) {
                    val hasWordTiming = lines.any { it.hasSyllables }
                    return LyricsResult.Success(
                        lines = lines,
                        isSynced = true,
                        isWordSynced = hasWordTiming,
                        plainLyrics = lines.joinToString("\n") { it.text },
                        isInstrumental = false,
                        source = if (hasWordTiming) "LyricsPlus (Word-Sync)" else "LyricsPlus (Line-Sync)",
                    )
                }
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun fetchWordFromBetterLyrics(
        title: String,
        artist: String,
    ): LyricsResult.Success? {
        try {
            val betterLines = betterLyricsApi.fetchWordLyrics(title, artist)
            if (!betterLines.isNullOrEmpty()) {
                val hasWordTiming = betterLines.any { it.hasSyllables }
                return LyricsResult.Success(
                    lines = betterLines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = betterLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = if (hasWordTiming) "BetterLyrics (Word-Sync)" else "BetterLyrics (Line-Sync)",
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun fetchWordFromKugou(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        try {
            val kugouLines = kugouApi.fetchWordLyrics(title, artist, durationSeconds)
            if (!kugouLines.isNullOrEmpty()) {
                val hasWordTiming = kugouLines.any { it.hasSyllables }
                return LyricsResult.Success(
                    lines = kugouLines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = kugouLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = "Kugou KRC (Word-Sync)",
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{2,3}))?\]""")
        private val OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
        /** Head start for the preferred provider before the automatic race
         *  takes over: bounds hangs, typical hits resolve well inside it. */
        private const val PREFERRED_HEAD_START_MS = 4_000L

        fun parseLrc(lrcContent: String): List<LyricLine> {
            val result = mutableListOf<LyricLine>()
            val lines = lrcContent.lines()
            var offsetMs = 0L

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val offsetMatch = OFFSET_REGEX.find(trimmed)
                if (offsetMatch != null) {
                    offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                    continue
                }

                // Check if line contains timestamp(s)
                val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
                if (matches.isEmpty()) continue

                // Extract text after all timestamps
                val text = trimmed.replace(TIMESTAMP_REGEX, "").trim()

                for (match in matches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fractionStr = match.groupValues.getOrNull(3).orEmpty()
                    val fractionMs = when (fractionStr.length) {
                        2 -> (fractionStr.toLongOrNull() ?: 0L) * 10
                        3 -> fractionStr.toLongOrNull() ?: 0L
                        1 -> (fractionStr.toLongOrNull() ?: 0L) * 100
                        else -> 0L
                    }

                    val totalMs = (minutes * 60 * 1000) + (seconds * 1000) + fractionMs + offsetMs
                    result.add(
                        LyricLine(
                            timeMs = totalMs.coerceAtLeast(0L),
                            text = text,
                            syllables = emptyList(),
                        ),
                    )
                }
            }

            return result.sortedBy { it.timeMs }
        }
    }
}
