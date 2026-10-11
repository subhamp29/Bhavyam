package com.bhavya.music.data.playlist

import com.bhavya.music.data.generate.GeneratedTrack
import com.bhavya.music.data.music.InnerTubeMusicApi
import com.bhavya.music.data.music.YouTubePlaylistResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provider for YouTube and YouTube Music playlist links.
 *
 * Reuses [InnerTubeMusicApi.fetchPlaylist] to resolve the public track list,
 * then converts each [com.bhavya.music.data.music.YouTubeMusicTrack] into an
 * [ExternalTrackRow] so it can flow through the same match pipeline as other
 * providers.
 */
@Singleton
class YouTubePlaylistProvider @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) : PlaylistProvider {

    override val source: ExternalPlaylistSource = ExternalPlaylistSource.YOUTUBE

    override fun canHandle(raw: String): Boolean {
        val value = raw.trim().lowercase()
        val isYouTubeHost = value.contains("youtube.com") || value.contains("youtu.be")
        val isPlaylist = value.contains("list=") || value.contains("playlist/")
        return isYouTubeHost && isPlaylist
    }

    override fun extractPlaylistId(raw: String): String? {
        val id = ExternalPlaylistLink.extractId(raw, source)
        if (id != null) return id
        return innerTube.extractPlaylistId(raw.trim()).takeIf { it.isNotBlank() }
    }

    override suspend fun fetchPlaylistRows(urlOrId: String): ExternalPlaylistResult = withContext(Dispatchers.IO) {
        val result = innerTube.fetchPlaylist(urlOrId)
        if (result == null) {
            throw IllegalArgumentException("Couldn't load that YouTube playlist. It may be private, deleted, or the link is invalid.")
        }
        if (result.tracks.isEmpty()) {
            throw IllegalArgumentException("That playlist has no tracks.")
        }
        val rows = result.tracks.map { track ->
            ExternalTrackRow(
                title = track.title,
                artist = track.artist.orEmpty(),
                album = track.album,
            )
        }
        ExternalPlaylistResult(
            source = source,
            title = result.title,
            author = result.author,
            artworkUrl = result.artworkUrl,
            rows = rows,
        )
    }

    override suspend fun fetchAndMatch(urlOrId: String): ExternalImportResult = withContext(Dispatchers.IO) {
        val result = innerTube.fetchPlaylist(urlOrId)
        if (result == null) {
            throw IllegalArgumentException("Couldn't load that YouTube playlist. It may be private, deleted, or the link is invalid.")
        }
        val tracks = result.tracks.map { yt ->
            GeneratedTrack(
                name = yt.title,
                artist = yt.artist.orEmpty(),
                album = yt.album,
                artworkUrl = yt.artworkUrl,
                url = "https://music.youtube.com/watch?v=${yt.videoId}",
            )
        }
        ExternalImportResult(
            source = source,
            suggestedTitle = result.title,
            totalRows = result.tracks.size,
            matchedCount = tracks.size,
            tracks = tracks,
        )
    }
}