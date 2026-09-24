package com.rushtify.app.data.lyrics

import com.rushtify.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Serializable
private data class PaxEnvelope(
    val syncType: String? = null,
    val lyrics: List<PaxLine> = emptyList(),
    val lrc: String? = null,
    val plain: String? = null,
)

@Serializable
private data class PaxLine(
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val text: List<PaxWord> = emptyList(),
    val background: Boolean = false,
    val backgroundText: List<PaxWord> = emptyList(),
)

@Serializable
private data class PaxWord(
    val text: String = "",
    val timestamp: Long = 0L,
    val endtime: Long = 0L,
    val duration: Long = 0L,
    val part: Boolean = false,
)

@Serializable
private data class ITunesSearchResponse(
    val results: List<ITunesSong> = emptyList(),
)

@Serializable
private data class ITunesSong(
    val trackId: Long = 0L,
    val trackName: String? = null,
    val artistName: String? = null,
    val trackTimeMillis: Long? = null,
)

/**
 * Apple Music syllable-synced lyrics via the Lyrically aggregator
 * (https://lyrics.paxsenix.org, free, no API key).
 *
 * Two hops: the iTunes Search API resolves `title + artist` to an Apple
 * Music `trackId`, then `/apple-music/lyrics?v=2` returns the normalised
 * envelope whose `lyrics` array carries per-word `timestamp/endtime`
 * timings (`part: true` = continuation of the previous word, e.g.
 * "with"+"drawals"). The envelope's `lrc`/`plain` fields are the fallback
 * when a track has no syllable timings. Every request identifies as
 * `Rushtify` in User-Agent.
 */
@Singleton
class AppleMusicLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
    ): LyricsResult.Success? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null

        val trackId = resolveTrackId(title, artist, durationSeconds)
            ?: run {
                val cleanedTitle = LrclibLyricsApi.cleanTrackTitle(title)
                val cleanedArtist = LrclibLyricsApi.cleanArtistName(artist)
                if (cleanedTitle != title || cleanedArtist != artist) {
                    resolveTrackId(cleanedTitle, cleanedArtist, durationSeconds)
                } else null
            } ?: return@withContext null

        val envelope = fetchEnvelope(trackId) ?: return@withContext null

        val lines = mapLines(envelope.lyrics)
        if (lines.isNotEmpty()) {
            val hasWordTiming = lines.any { it.hasSyllables }
            return@withContext LyricsResult.Success(
                lines = lines,
                isSynced = true,
                isWordSynced = hasWordTiming,
                plainLyrics = lines.joinToString("\n") { it.text },
                isInstrumental = false,
                source = if (hasWordTiming) "Apple Music (Word-Sync)" else "Apple Music (Line-Sync)",
            )
        }

        if (!envelope.lrc.isNullOrBlank()) {
            val lrcLines = LyricsRepository.parseLrc(envelope.lrc)
            if (lrcLines.isNotEmpty()) {
                return@withContext LyricsResult.Success(
                    lines = lrcLines,
                    isSynced = true,
                    isWordSynced = false,
                    plainLyrics = envelope.plain?.takeIf { it.isNotBlank() }
                        ?: lrcLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = "Apple Music (Line-Sync)",
                )
            }
        }

        if (!envelope.plain.isNullOrBlank()) {
            return@withContext LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = envelope.plain.trim(),
                isInstrumental = false,
                source = "Apple Music (Plain)",
            )
        }

        null
    }

    /** iTunes Search -> best Apple Music trackId for the song. */
    private suspend fun resolveTrackId(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): Long? {
        if (title.isBlank()) return null
        val term = if (artist.isNotBlank()) "$title $artist" else title
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}" +
            "&media=music&entity=song&limit=5"
        val body = try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        val results = try {
            json.decodeFromString<ITunesSearchResponse>(body).results
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return null
        }
        val expectedMs = durationSeconds?.takeIf { it > 0 }?.times(1_000L)
        return results
            .filter { it.trackId > 0 }
            .maxByOrNull { score(it, title, artist, expectedMs) }
            ?.takeIf { score(it, title, artist, expectedMs) >= MIN_MATCH_SCORE }
            ?.trackId
    }

    /** Exact title + artist/duration agreement wins; junk matches score ~0
     *  and are rejected rather than returning another song's lyrics. */
    private fun score(song: ITunesSong, title: String, artist: String, expectedMs: Long?): Int {
        var score = 0
        val songTitle = song.trackName.orEmpty()
        if (songTitle.equals(title, ignoreCase = true)) {
            score += 3
        } else if (songTitle.contains(title, ignoreCase = true) || title.contains(songTitle, ignoreCase = true)) {
            score += 1
        }
        if (artist.isNotBlank() && song.artistName?.contains(artist, ignoreCase = true) == true) {
            score += 2
        }
        val songMs = song.trackTimeMillis
        if (expectedMs != null && expectedMs > 0 && songMs != null && songMs > 0 &&
            abs(songMs - expectedMs) <= DURATION_TOLERANCE_MS
        ) {
            score += 2
        }
        return score
    }

    private suspend fun fetchEnvelope(trackId: Long): PaxEnvelope? {
        val url = "https://lyrics.paxsenix.org/apple-music/lyrics?id=$trackId&v=2"
            .toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return null
            json.decodeFromString<PaxEnvelope>(body).takeIf {
                it.lyrics.isNotEmpty() || !it.lrc.isNullOrBlank() || !it.plain.isNullOrBlank()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val USER_AGENT = "Rushtify"
        private const val MIN_MATCH_SCORE = 3
        private const val DURATION_TOLERANCE_MS = 12_000L

        /** Syllable envelope -> [LyricLine]. `part` words continue the
         *  previous token ("with"+"drawals"); background vocals ride along
         *  as background syllables without polluting the line text. Lines
         *  carried only by background vocals still produce a line so no
         *  section goes missing. */
        private fun mapLines(lines: List<PaxLine>): List<LyricLine> {
            return lines.mapNotNull { line ->
                val words = line.text.filter { it.text.isNotBlank() }
                // Background-only passage (lead `text` empty): build the
                // line from `backgroundText` so the section isn't dropped.
                val leads = words.isNotEmpty()
                val tokens = mutableListOf<String>()
                val syllables = mutableListOf<LyricSyllable>()
                val sourceWords = if (leads) words else line.backgroundText.filter { it.text.isNotBlank() }
                if (sourceWords.isEmpty()) return@mapNotNull null
                for (word in sourceWords) {
                    val text = word.text.trim()
                    // `part` continues the previous token ("with"+"drawals"):
                    // join without space and flag it so views skip the
                    // visual separator too. Background-only lines are all
                    // continuations of nothing, so they always start tokens.
                    val continues = leads && word.part && tokens.isNotEmpty()
                    if (continues) {
                        tokens[tokens.lastIndex] += text
                    } else {
                        tokens += text
                    }
                    syllables += LyricSyllable(
                        timeMs = word.timestamp.coerceAtLeast(0L),
                        durationMs = (
                            word.duration.takeIf { it > 0 }
                                ?: (word.endtime - word.timestamp)
                            ).coerceAtLeast(0L),
                        text = text,
                        isBackground = line.background || !leads,
                        appendToPrevious = continues,
                    )
                }
                if (leads) {
                    for (word in line.backgroundText.filter { it.text.isNotBlank() }) {
                        syllables += LyricSyllable(
                            timeMs = word.timestamp.coerceAtLeast(0L),
                            durationMs = (
                                word.duration.takeIf { it > 0 }
                                    ?: (word.endtime - word.timestamp)
                                ).coerceAtLeast(0L),
                            text = word.text.trim(),
                            isBackground = true,
                        )
                    }
                }
                val lineDuration = line.duration.takeIf { it > 0 }
                    ?: (line.endtime - line.timestamp).coerceAtLeast(0L)
                LyricLine(
                    timeMs = line.timestamp.coerceAtLeast(0L),
                    durationMs = lineDuration,
                    text = tokens.joinToString(" "),
                    syllables = syllables.sortedBy { it.timeMs },
                )
            }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        }
    }
}
