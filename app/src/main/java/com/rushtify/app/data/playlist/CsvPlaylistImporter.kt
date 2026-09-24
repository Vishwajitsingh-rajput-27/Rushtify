package com.rushtify.app.data.playlist

import com.rushtify.app.data.generate.GeneratedTrack
import com.rushtify.app.data.generate.youtubeVideoIdOrNull
import com.rushtify.app.data.music.InnerTubeMusicApi
import com.rushtify.app.data.music.YouTubeMusicTrack
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class CsvRawTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val videoId: String? = null,
)

data class CsvImportResult(
    val suggestedTitle: String,
    val totalRows: Int,
    val matchedCount: Int,
    val tracks: List<GeneratedTrack>,
)

@Singleton
class CsvPlaylistImporter @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) {
    suspend fun parseAndMatchCsv(
        inputStream: InputStream,
        filename: String = "Imported Playlist",
    ): CsvImportResult = withContext(Dispatchers.IO) {
        val bytes = inputStream.readBytes()
        val charset = when {
            bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte()) -> Charsets.UTF_16LE
            bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte()) -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val rawTracks = parseTracks(String(bytes, charset).removePrefix("\uFEFF"), filename)
        val limiter = Semaphore(6)
        val tracks = coroutineScope {
            rawTracks.map { raw ->
                async {
                    limiter.withPermit {
                        try {
                            if (raw.videoId != null) {
                                val details = runCatching { innerTube.fetchSongDetails(raw.videoId) }.getOrNull()
                                GeneratedTrack(
                                    name = raw.title.ifBlank { details?.title ?: "Track" },
                                    artist = raw.artist.ifBlank { details?.artist ?: "Unknown artist" },
                                    album = raw.album ?: details?.album,
                                    artworkUrl = details?.artworkUrl ?: "https://i.ytimg.com/vi/${raw.videoId}/hqdefault.jpg",
                                    url = "https://music.youtube.com/watch?v=${raw.videoId}",
                                )
                            } else if (raw.title.isNotBlank()) {
                                val cleanArtist = raw.artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
                                val query = if (cleanArtist.isNotBlank()) "${raw.title} $cleanArtist" else raw.title
                                val candidates = runCatching {
                                    innerTube.searchSongs(
                                        query = query,
                                        limit = 30,
                                        prefetchStreams = false,
                                    )
                                }.getOrDefault(emptyList())

                                val exactMatch = candidates.firstOrNull { isExactMatch(raw, it) }
                                    ?: (if (raw.album != null) candidates.firstOrNull { isExactMatch(raw.copy(album = null), it) } else null)

                                val bestMatch = exactMatch
                                    ?: (if (cleanArtist.isNotBlank()) innerTube.findBestMatchOrNull(raw.title, cleanArtist, prefetchStreams = false) else null)
                                    ?: (if (cleanArtist.isNotBlank()) innerTube.findBestMatchOrNull(cleanArtist, raw.title, prefetchStreams = false) else null)
                                    ?: innerTube.findBestMatchOrNull(raw.title, "", prefetchStreams = false)

                                bestMatch?.let {
                                    GeneratedTrack(
                                        name = raw.title.ifBlank { it.title },
                                        artist = cleanArtist.ifBlank { it.artist },
                                        album = raw.album ?: it.album,
                                        artworkUrl = it.artworkUrl,
                                        url = "https://music.youtube.com/watch?v=${it.videoId}",
                                    )
                                }
                            } else null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        CsvImportResult(
            suggestedTitle = filename.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim().ifBlank { "Imported Playlist" },
            totalRows = rawTracks.size,
            matchedCount = tracks.size,
            tracks = tracks,
        )
    }

    internal fun isExactMatch(source: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        if (source.videoId != null && source.videoId != target.videoId) return false
        if (source.videoId == null && (source.title.isBlank() || source.artist.isBlank())) return false
        if (normalize(source.artist) in setOf("unknown", "unknown artist")) return false
        if (source.title.isNotBlank() && !sameText(source.title, target.title, allowSafeVideoLabel = true)) return false
        if (source.artist.isNotBlank() && !sameArtist(source.artist, target.artist.removeSuffix(" - Topic"))) return false
        if (!source.album.isNullOrBlank() && !sameText(source.album, target.album.orEmpty())) return false
        return true
    }

    private fun sameText(source: String, target: String, allowSafeVideoLabel: Boolean = false): Boolean {
        val normalized = normalize(source)
        val targetText = if (allowSafeVideoLabel) stripSafeVideoLabel(target) else target
        return normalized.isNotBlank() && normalized == normalize(targetText)
    }

    private fun sameArtist(source: String, target: String): Boolean {
        if (sameText(source, target)) return true
        val primaryTarget = target
            .split(Regex("(?i)\\s*(?:,|&|feat\\.?|ft\\.?|featuring)\\s*"), limit = 2)
            .first()
            .removeSuffix(" - Topic")
            .trim()
        return sameText(source, primaryTarget)
    }

    private fun stripSafeVideoLabel(title: String): String = title
        .replace(
            Regex("(?i)\\s*[\\[(](official\\s*(audio|video)|music\\s*video|lyric\\s*video|audio|video|hd|hq|4k)[\\])]"),
            "",
        )
        .trim()

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("['’]"), "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    internal fun parseTracks(text: String, filename: String): List<CsvRawTrack> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return emptyList()
        if (filename.endsWith(".m3u", true) || filename.endsWith(".m3u8", true) || lines.first().equals("#EXTM3U", true)) {
            return parseM3u(text.lineSequence().map(String::trim).toList())
        }
        if (filename.endsWith(".txt", true) && lines.none { '\t' in it } &&
            lines.first().split(',', ';').map(::normalize).all { it in TITLE_HEADERS || it in ARTIST_HEADERS || it in URL_HEADERS }.not()) {
            return lines.map(::parseTextTrack)
        }
        val delimiter = listOf(',', ';', '\t').maxBy { candidate ->
            parseRecords(text, candidate).take(5).sumOf { (it.size - 1).coerceAtLeast(0) }
        }
        val records = parseRecords(text, delimiter)
        val first = records.firstOrNull() ?: return emptyList()
        val headers = first.map(::normalize)
        val titleIndex = headers.indexOfFirst { it in TITLE_HEADERS }
        val artistIndex = headers.indexOfFirst { it in ARTIST_HEADERS }
        val albumIndex = headers.indexOfFirst { it in ALBUM_HEADERS }
        val urlIndex = headers.indexOfFirst { it in URL_HEADERS }
        val hasHeader = titleIndex >= 0 || artistIndex >= 0 || albumIndex >= 0 || urlIndex >= 0
        if (first.size == 1 && !hasHeader) return lines.filterNot { it.startsWith('#') }.map(::parseTextTrack)
        val titleColumn = if (titleIndex >= 0) {
            titleIndex
        } else if (hasHeader) {
            (0 until first.size).firstOrNull { it != artistIndex && it != albumIndex && it != urlIndex } ?: 0
        } else 0
        val artistColumn = if (artistIndex >= 0) {
            artistIndex
        } else if (hasHeader) {
            // No artist header: never guess another column (e.g. the album
            // cell) as the artist. -1 reads as empty via getOrNull.
            -1
        } else 1
        return records.drop(if (hasHeader) 1 else 0).mapNotNull { row ->
            if (hasHeader && row.map(::normalize) == headers) return@mapNotNull null
            val title = row.getOrNull(titleColumn).orEmpty().trim()
            val artist = row.getOrNull(artistColumn).orEmpty().trim()
            val rawVideoId = if (urlIndex >= 0) row.getOrNull(urlIndex)?.let(::youtubeId) else null
            val videoId = rawVideoId ?: row.firstNotNullOfOrNull(::youtubeId)
            if (title.isBlank() && videoId == null) return@mapNotNull null
            CsvRawTrack(title, artist, row.getOrNull(albumIndex)?.trim()?.takeIf(String::isNotBlank), videoId)
        }
    }

    private fun parseTextTrack(line: String): CsvRawTrack {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return CsvRawTrack("", "")
        val videoId = youtubeId(trimmed)
        if (videoId != null) {
            return CsvRawTrack("", "", videoId = videoId)
        }
        // Strip leading track numbering: "1. ", "01. ", "1) ", "[1] ", "1 - "
        val cleaned = trimmed.replace(Regex("^\\s*(?:\\[?\\d+[.)\\]]|\\d+\\s*[-–—])\\s*"), "").trim()
        if (cleaned.isBlank()) return CsvRawTrack("", "")

        // Check for " by " separator
        val byMatch = Regex("(?i)\\s+by\\s+").find(cleaned)
        if (byMatch != null) {
            val title = cleaned.substring(0, byMatch.range.first).trim()
            val artist = cleaned.substring(byMatch.range.last + 1).trim()
            if (title.isNotBlank()) return CsvRawTrack(title = title, artist = artist)
        }

        val separator = Regex("\\s+[-–—|:]\\s+").find(cleaned)
            ?: return CsvRawTrack(cleaned, "")
        return CsvRawTrack(
            title = cleaned.substring(separator.range.last + 1).trim(),
            artist = cleaned.substring(0, separator.range.first).trim(),
        )
    }

    private fun parseM3u(lines: List<String>): List<CsvRawTrack> {
        val tracks = mutableListOf<CsvRawTrack>()
        var pending: CsvRawTrack? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:", true) -> {
                    pending?.let { tracks += it }
                    val info = line.substringAfter(':')
                    val artist = Regex("""artist="([^"]+)""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)
                    val title = Regex("""title="([^"]+)""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)
                    pending = if (artist != null && title != null) CsvRawTrack(title, artist)
                    else parseTextTrack(info.substringAfter(',', ""))
                }
                line.isBlank() -> Unit
                line.startsWith('#') -> Unit
                else -> {
                    val videoId = youtubeId(line)
                    val track = pending ?: if (videoId != null) CsvRawTrack("", "", videoId = videoId)
                    else parseTextTrack(line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
                    tracks += track.copy(videoId = videoId)
                    pending = null
                }
            }
        }
        pending?.let { tracks += it }
        return tracks
    }

    private fun youtubeId(value: String): String? {
        if (!VIDEO_ID.matches(value) && !value.startsWith("https://", true) && !value.startsWith("http://", true)) return null
        return GeneratedTrack(name = "", artist = "", artworkUrl = null, url = value).youtubeVideoIdOrNull()
    }

    private fun parseRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() {
            row += field.toString().trim()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            if (row.any(String::isNotBlank)) records += row.toList()
            row.clear()
        }
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' && (quoted || field.isBlank()) -> quoted = !quoted
                char == delimiter && !quoted -> finishField()
                (char == '\n' || char == '\r') && !quoted -> {
                    finishRow()
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "Playlist file contains an unclosed quoted field" }
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return records
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val TITLE_HEADERS = setOf(
            "track name", "trackname", "title", "song", "name", "track", "song name", "songname",
            "song title", "track title", "video title", "item", "item name", "headline", "music",
        )
        val ARTIST_HEADERS = setOf(
            "artist name s", "artist names", "artist s", "artist", "artists", "artist name",
            "artistname", "track artist", "track artists", "performer", "performers", "author",
            "creator", "singer", "band", "by", "channel", "uploader",
        )
        val ALBUM_HEADERS = setOf(
            "album name", "albumname", "album", "albums", "release", "collection", "record",
        )
        val URL_HEADERS = setOf(
            "url", "uri", "track url", "track uri", "youtube url", "video id", "videoid",
            "link", "track link", "spotify uri", "spotify url", "youtube link", "video url",
        )
    }
}
