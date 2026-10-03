package com.rushtify.app.data.playlist

import com.rushtify.app.data.music.InnerTubeMusicApi
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaylistImporterTest {
    private val importer = SpotifyPlaylistImporter(OkHttpClient(), mockk<InnerTubeMusicApi>())

    @Test
    fun parsesPublicProfilePlaylistsFromJsonAndLinksWithoutDuplicates() {
        val html = """
            <script type="application/json">
              {"items":[
                {"uri":"spotify:playlist:abc123","name":"Morning Mix","owner":{"uri":"spotify:user:alice"}},
                {"uri":"spotify:playlist:def456","title":"Road Trip","owner":{"uri":"spotify:user:someone-else"}}
              ]}
            </script>
            <a href="https://open.spotify.com/playlist/abc123">duplicate</a>
            <a href="https://open.spotify.com/playlist/ghi789">third</a>
        """.trimIndent()

        val playlists = importer.parseAccountPage(html, "alice")

        assertEquals(setOf("abc123", "def456", "ghi789"), playlists.map { it.id }.toSet())
        assertEquals("Morning Mix", playlists.single { it.id == "abc123" }.title)
        assertEquals("Road Trip", playlists.single { it.id == "def456" }.title)
        assertEquals(SpotifyPlaylistKind.CREATED, playlists.single { it.id == "abc123" }.kind)
        assertEquals(SpotifyPlaylistKind.SAVED, playlists.single { it.id == "def456" }.kind)
        assertTrue(playlists.single { it.id == "ghi789" }.title.isNotBlank())
    }

    @Test
    fun ignoresNonPlaylistUris() {
        val html = "<script type=\"application/json\">{" +
            "\"uri\":\"spotify:track:abc\",\"name\":\"Song\"}</script>"
        assertTrue(importer.parseAccountPage(html).isEmpty())
    }
}
