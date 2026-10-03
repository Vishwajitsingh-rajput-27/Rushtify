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

        assertEquals(listOf("abc123", "def456", "ghi789"), playlists.map { it.id })
        assertEquals("Morning Mix", playlists.first().title)
        assertEquals("Road Trip", playlists[1].title)
        assertEquals(SpotifyPlaylistKind.CREATED, playlists.first().kind)
        assertEquals(SpotifyPlaylistKind.SAVED, playlists[1].kind)
        assertTrue(playlists[2].title.isNotBlank())
    }

    @Test
    fun ignoresNonPlaylistUris() {
        val html = "<script type=\"application/json\">{" +
            "\"uri\":\"spotify:track:abc\",\"name\":\"Song\"}</script>"
        assertTrue(importer.parseAccountPage(html).isEmpty())
    }
}
