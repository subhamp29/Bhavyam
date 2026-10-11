package com.bhavya.music.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bhavya.music.data.playlist.AppleMusicPlaylistImporter
import com.bhavya.music.data.playlist.ExternalImportResult
import com.bhavya.music.data.playlist.ExternalPlaylistLink
import com.bhavya.music.data.playlist.ExternalPlaylistResult
import com.bhavya.music.data.playlist.ExternalPlaylistSource
import com.bhavya.music.data.playlist.PlaylistImportManager
import com.bhavya.music.data.playlist.PlaylistProvider
import com.bhavya.music.data.playlist.SavedPlaylist
import com.bhavya.music.data.playlist.SpotifyPlaylistImporter
import com.bhavya.music.data.playlist.YouTubePlaylistProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExternalImportUiState(
    val link: String = "",
    val detectedSource: ExternalPlaylistSource? = null,
    val isLoading: Boolean = false,
    val preview: ExternalPlaylistResult? = null,
    val isImporting: Boolean = false,
    val progress: String? = null,
    val errorMessage: String? = null,
    val importedCount: Int = 0,
    val skippedTracks: List<com.bhavya.music.data.playlist.ExternalTrackRow> = emptyList(),
    val lastResult: ExternalImportResult? = null,
    val showSkippedSheet: Boolean = false,
)

@HiltViewModel
class ExternalPlaylistImportViewModel @Inject constructor(
    private val importManager: PlaylistImportManager,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val appleMusicPlaylistImporter: AppleMusicPlaylistImporter,
    private val youTubePlaylistProvider: YouTubePlaylistProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExternalImportUiState())
    val uiState: StateFlow<ExternalImportUiState> = _uiState.asStateFlow()

    fun onLinkChange(value: String) {
        val source = ExternalPlaylistLink.detect(value)
        _uiState.update {
            it.copy(
                link = value,
                detectedSource = source,
                errorMessage = null,
                preview = if (value.isBlank()) null else it.preview,
            )
        }
    }

    fun onLinkPasted(link: String) {
        onLinkChange(link)
        loadPreview()
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun clearPreview() {
        _uiState.update {
            it.copy(
                preview = null,
                lastResult = null,
                importedCount = 0,
                progress = null,
                errorMessage = null,
                skippedTracks = emptyList(),
                showSkippedSheet = false,
            )
        }
    }
    
    fun dismissSkippedSheet() {
        _uiState.update { it.copy(showSkippedSheet = false) }
    }

    private fun detectProvider(raw: String): PlaylistProvider? {
        return when (ExternalPlaylistLink.detect(raw)) {
            ExternalPlaylistSource.SPOTIFY -> spotifyPlaylistImporter
            ExternalPlaylistSource.APPLE_MUSIC -> appleMusicPlaylistImporter
            ExternalPlaylistSource.YOUTUBE -> youTubePlaylistProvider
            null -> null
        }
    }

    fun loadPreview() {
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify, Apple Music, or YouTube playlist link.") }
            return
        }
        
        val source = ExternalPlaylistLink.detect(raw)
        if (source == null) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify, Apple Music, or YouTube playlist link.") }
            return
        }
        
        if (!ExternalPlaylistLink.isPlaylistLink(raw)) {
            _uiState.update {
                it.copy(
                    errorMessage = when (source) {
                        ExternalPlaylistSource.SPOTIFY -> 
                            "That's a Spotify track or album, not a playlist. Paste a playlist link to import it."
                        ExternalPlaylistSource.APPLE_MUSIC ->
                            "That's an Apple Music song or album, not a playlist. Paste a playlist link to import it."
                        ExternalPlaylistSource.YOUTUBE ->
                            "That's a YouTube video, not a playlist. Paste a playlist link to import it."
                    }
                )
            }
            return
        }

        val provider = detectProvider(raw)
        if (provider == null) {
            _uiState.update { it.copy(errorMessage = "Unsupported link. Paste a Spotify, Apple Music, or YouTube playlist link.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val result = provider.fetchPlaylistRows(raw.trim())
                val rows = result.rows
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
                        errorMessage = resolveErrorMessage(e, source),
                    )
                }
            }
        }
    }

    fun import(onSuccess: (SavedPlaylist) -> Unit) {
        if (_uiState.value.isImporting) return
        val raw = _uiState.value.link
        if (raw.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Paste a Spotify, Apple Music, or YouTube playlist link.") }
            return
        }

        val provider = detectProvider(raw)
        if (provider == null) {
            _uiState.update { it.copy(errorMessage = "Unsupported link. Paste a Spotify, Apple Music, or YouTube playlist link.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, progress = "Importing playlist...", errorMessage = null)
            }
            try {
                val previewRows = _uiState.value.preview?.rows.orEmpty()
                val (saved, result) = importManager.importExternalPlaylist(raw.trim())
                val skipped = (result.totalRows - result.matchedCount).coerceAtLeast(0)
                // Track rows the match pass could not resolve. The provider
                // returns only the matches, so the rows come from the preview
                // list when it covers the same playlist, else they are unknown.
                val skippedRows = when {
                    skipped <= 0 -> emptyList()
                    previewRows.isNotEmpty() && previewRows.size == result.totalRows ->
                        previewRows.filter { row ->
                            result.tracks.none {
                                it.name.equals(row.title, ignoreCase = true) &&
                                    it.artist.equals(row.artist, ignoreCase = true)
                            }
                        }
                    else -> emptyList()
                }
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = "${result.matchedCount} imported, $skipped skipped",
                        importedCount = result.matchedCount,
                        lastResult = result,
                        skippedTracks = skippedRows,
                        showSkippedSheet = false,
                    )
                }
                onSuccess(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val source = ExternalPlaylistLink.detect(raw) ?: ExternalPlaylistSource.SPOTIFY
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        progress = null,
                        errorMessage = resolveErrorMessage(e, source),
                    )
                }
            }
        }
    }
    
    /** Opens the "skipped tracks" sheet after an import with unmatched rows. */
    fun openSkippedTracks() {
        if (_uiState.value.skippedTracks.isNotEmpty()) {
            _uiState.update { it.copy(showSkippedSheet = true) }
        }
    }

    /** Maps exceptions into user-friendly messages. */
    private fun resolveErrorMessage(e: Exception, source: ExternalPlaylistSource): String {
        val msg = e.message ?: e.toString()
        val isPrivate = msg.contains("private", ignoreCase = true) ||
            msg.contains("403", ignoreCase = true) ||
            msg.contains("forbidden", ignoreCase = true) ||
            msg.contains("Could not load", ignoreCase = true) ||
            msg.contains("returned HTTP", ignoreCase = true) ||
            e is java.io.IOException
        val networkFailure = e is java.io.IOException || msg.contains("network", ignoreCase = true)
        
        return when {
            isPrivate && networkFailure -> 
                "Couldn't read that playlist. It may be private or the link may be invalid. Only public playlists can be imported."
            networkFailure ->
                "Network error — check your connection and try again."
            msg.contains("No tracks", ignoreCase = true) ->
                "No tracks found in that playlist. Make sure it is public."
            msg.contains("Could not match", ignoreCase = true) || msg.contains("No tracks from", ignoreCase = true) ->
                "No tracks from that playlist could be matched. Try a different playlist or check the track names."
            else -> "Couldn't import: ${e.localizedMessage ?: e.message}"
        }
    }
}