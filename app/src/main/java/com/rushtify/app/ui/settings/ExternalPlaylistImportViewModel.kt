package com.rushtify.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rushtify.app.data.playlist.AppleMusicPlaylistImporter
import com.rushtify.app.data.playlist.ExternalImportResult
import com.rushtify.app.data.playlist.ExternalPlaylistLink
import com.rushtify.app.data.playlist.ExternalPlaylistResult
import com.rushtify.app.data.playlist.ExternalPlaylistSource
import com.rushtify.app.data.playlist.PlaylistImportManager
import com.rushtify.app.data.playlist.SavedPlaylist
import com.rushtify.app.data.playlist.SpotifyAccountPlaylist
import com.rushtify.app.data.playlist.SpotifyLibraryApi
import com.rushtify.app.data.playlist.SpotifyLoginEvent
import com.rushtify.app.data.playlist.SpotifyPlaylistImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the "paste a Spotify / Apple Music link" importer.
 *
 * Mirrors [YouTubeImportUiState]: one immutable data class pushed through a
 * single [StateFlow], so the screen is a pure function of it.
 */
data class ExternalImportUiState(
    val link: String = "",
    val isLoading: Boolean = false,
    val preview: ExternalPlaylistResult? = null,
    val isImporting: Boolean = false,
    val progress: String? = null,
    val errorMessage: String? = null,
    val importedCount: Int = 0,
    val lastResult: ExternalImportResult? = null,
    val spotifyPlaylists: List<SpotifyAccountPlaylist> = emptyList(),
    val selectedSpotifyPlaylistIds: Set<String> = emptySet(),
    val isLoadingSpotifyAccount: Boolean = false,
    val spotifyAuthorizationUrl: String? = null,
    val spotifyLibraryConnected: Boolean = false,
)

/**
 * Backs [ExternalPlaylistImportScreen]: detects the provider from the pasted
 * link, previews the public playlist's track list, and hands the link to
 * [PlaylistImportManager] for the actual match + save pass.
 */
@HiltViewModel
class ExternalPlaylistImportViewModel @Inject constructor(
    private val importManager: PlaylistImportManager,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val spotifyLibraryApi: SpotifyLibraryApi,
    private val appleMusicPlaylistImporter: AppleMusicPlaylistImporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ExternalImportUiState(),
    )
    val uiState: StateFlow<ExternalImportUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            spotifyLibraryApi.loginEvent.collect { event ->
                when (event) {
                    is SpotifyLoginEvent.Success -> {
                        _uiState.update {
                            it.copy(
                                spotifyPlaylists = event.playlists,
                                selectedSpotifyPlaylistIds = event.playlists.mapTo(mutableSetOf()) { playlist -> playlist.id },
                                isLoadingSpotifyAccount = false,
                                spotifyLibraryConnected = true,
                                errorMessage = if (event.playlists.isEmpty()) "No playlists were returned for this Spotify account." else null,
                            )
                        }
                        spotifyLibraryApi.consumeLoginEvent()
                    }
                    is SpotifyLoginEvent.Failure -> {
                        _uiState.update { it.copy(isLoadingSpotifyAccount = false, errorMessage = event.message) }
                        spotifyLibraryApi.consumeLoginEvent()
                    }
                    null -> Unit
                }
            }
        }
    }

    fun onLinkChange(value: String) {
        _uiState.update {
            it.copy(
                link = value,
                errorMessage = null,
                preview = if (value.isBlank()) null else it.preview,
            )
        }
    }

    /** Which provider the pasted link points at, or null when unrecognised. */
    fun detectSource(): ExternalPlaylistSource? = ExternalPlaylistLink.detect(_uiState.value.link)

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun connectSpotifyLibrary() {
        runCatching { spotifyLibraryApi.createAuthorizationUrl() }
            .onSuccess { url ->
                _uiState.update { it.copy(spotifyAuthorizationUrl = url, isLoadingSpotifyAccount = true, errorMessage = null) }
            }
            .onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "Couldn't start Spotify sign-in.") }
            }
    }

    fun clearSpotifyAuthorizationUrl() {
        _uiState.update { it.copy(spotifyAuthorizationUrl = null, isLoadingSpotifyAccount = false) }
    }

    fun handleSpotifyRedirect(uri: android.net.Uri?) {
        viewModelScope.launch { spotifyLibraryApi.handleRedirect(uri) }
    }

    fun toggleSpotifyPlaylist(id: String) {
        _uiState.update { state ->
            val updated = if (id in state.selectedSpotifyPlaylistIds) state.selectedSpotifyPlaylistIds - id else state.selectedSpotifyPlaylistIds + id
            state.copy(selectedSpotifyPlaylistIds = updated)
        }
    }

    fun importSelectedSpotifyAccount(onSuccess: (List<SavedPlaylist>) -> Unit) {
        val selected = _uiState.value.spotifyPlaylists.filter { it.id in _uiState.value.selectedSpotifyPlaylistIds }
        if (selected.isEmpty() || _uiState.value.isImporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, progress = "Preparing Spotify playlists...", errorMessage = null) }
            try {
                val import = if (_uiState.value.spotifyLibraryConnected) {
                    importManager.importSpotifyLibraryPlaylists(selected) { done, total ->
                        _uiState.update { it.copy(progress = "Importing playlist $done of $total...") }
                    }
                } else {
                    importManager.importSpotifyAccountPlaylists(selected) { done, total ->
                        _uiState.update { it.copy(progress = "Importing playlist $done of $total...") }
                    }
                }
                _uiState.update { it.copy(isImporting = false, progress = "${import.imported.size} imported, ${import.skipped} skipped") }
                onSuccess(import.imported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isImporting = false, progress = null, errorMessage = e.localizedMessage ?: "Spotify import failed.") }
            }
        }
    }

    /** Clears a loaded preview so the user can paste a different link. */
    fun clearPreview() {
        _uiState.update {
            it.copy(preview = null, lastResult = null, importedCount = 0, progress = null, errorMessage = null)
        }
    }

    /**
     * Resolves the pasted link into a preview (title + track rows) without
     * saving anything — the user confirms with the Import CTA afterwards.
     */
    fun loadPreview() {
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }
        val source = ExternalPlaylistLink.detect(raw)
        if (source == null) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val result: ExternalPlaylistResult? = when (source) {
                    ExternalPlaylistSource.SPOTIFY -> spotifyPlaylistImporter.fetchPlaylist(raw.trim())
                    ExternalPlaylistSource.APPLE_MUSIC -> appleMusicPlaylistImporter.fetchPlaylist(raw.trim())
                }
                val rows = result?.rows.orEmpty()
                if (rows.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            preview = null,
                            errorMessage = "No tracks found in that playlist. Make sure it is public.",
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, preview = result, errorMessage = null, lastResult = null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Couldn't read that playlist: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }

    /**
     * Runs the real import: [PlaylistImportManager] re-reads the playlist,
     * matches every row to a playable track and saves the playlist.
     */
    fun import(onSuccess: (SavedPlaylist) -> Unit) {
        if (_uiState.value.isImporting) return
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify or Apple Music playlist link.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, progress = "Importing playlist...", errorMessage = null)
            }
            try {
                val (saved, result) = importManager.importExternalPlaylist(raw.trim())
                val skipped = (result.totalRows - result.matchedCount).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = "${result.matchedCount} imported, $skipped skipped",
                        importedCount = result.matchedCount,
                        lastResult = result,
                    )
                }
                onSuccess(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = null,
                        errorMessage = "Import failed: ${e.localizedMessage ?: e.message}",
                    )
                }
            }
        }
    }
}
