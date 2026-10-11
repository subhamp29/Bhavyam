package com.bhavya.music.data.playlist

import com.bhavya.music.data.generate.GeneratedTrack

/**
 * Supported external playlist providers for the "paste a link" importer.
 *
 * These are **public** playlist sources: the track list is read from the
 * provider's public web page, so no API key, OAuth or account is required.
 * Private playlists / "Liked Songs" would need OAuth and are not supported.
 */
enum class ExternalPlaylistSource(
    val label: String,
    val hostHint: String,
) {
    SPOTIFY("Spotify", "spotify.com"),
    APPLE_MUSIC("Apple Music", "music.apple.com"),
    YOUTUBE("YouTube / YouTube Music", "youtube.com"),
}

/** One row read off a provider page, before it is matched to a playable track. */
data class ExternalTrackRow(
    val title: String,
    val artist: String,
    val album: String? = null,
)

/** A parsed provider playlist — the preview payload shown before importing. */
data class ExternalPlaylistResult(
    val source: ExternalPlaylistSource,
    val title: String,
    val author: String? = null,
    val artworkUrl: String? = null,
    val rows: List<ExternalTrackRow>,
)

/** Outcome of a match pass: mirrors [CsvImportResult] so the UI reports identically. */
data class ExternalImportResult(
    val source: ExternalPlaylistSource,
    val suggestedTitle: String,
    val totalRows: Int,
    val matchedCount: Int,
    val tracks: List<GeneratedTrack>,
)

/**
 * Link helpers shared by every provider importer.
 *
 * Accepts the shapes users actually paste or share:
 * - `https://open.spotify.com/playlist/<id>?si=...`
 * - `https://open.spotify.com/intl-de/playlist/<id>`   (locale segment)
 * - `https://spotify.link/<short>`                     (expanded by OkHttp redirects)
 * - `spotify:playlist:<id>`                            (URI form)
 * - `https://music.apple.com/us/playlist/<slug>/pl.<id>`
 * - `https://music.apple.com/us/playlist/<id>`         (no slug)
 * - `https://music.youtube.com/playlist?list=<id>`
 * - `https://www.youtube.com/playlist?list=<id>`
 * - `https://youtube.com/playlist?list=<id>`
 * - `https://youtu.be/playlist?list=<id>`
 */
object ExternalPlaylistLink {

    private val SPOTIFY_ID = Regex("""spotify\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?playlist/([A-Za-z0-9]+)""")
    private val SPOTIFY_URI = Regex("""spotify:playlist:([A-Za-z0-9]+)""")
    private val APPLE_ID = Regex("""music\.apple\.com/[a-z]{2}(?:-[a-z]{2})?/playlist/(?:[^/]+/)?(pl\.[A-Za-z0-9]+)""")
    private val YOUTUBE_PLAYLIST = Regex("""youtube\.com/playlist\?list=([A-Za-z0-9_-]+)""")
    private val YOUTUBE_MUSIC_PLAYLIST = Regex("""music\.youtube\.com/playlist\?list=([A-Za-z0-9_-]+)""")
    private val YOUTUBE_BE_PLAYLIST = Regex("""youtu\.be/playlist\?list=([A-Za-z0-9_-]+)""")

    /** Best-effort provider detection from a pasted string. */
    fun detect(raw: String): ExternalPlaylistSource? {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        if (value.contains("spotify.com") || value.contains("spotify.link") || value.startsWith("spotify:")) {
            return ExternalPlaylistSource.SPOTIFY
        }
        if (value.contains("music.apple.com")) return ExternalPlaylistSource.APPLE_MUSIC
        if (value.contains("youtube.com") && value.contains("list=") ||
            value.contains("music.youtube.com") && value.contains("list=") ||
            value.contains("youtu.be") && value.contains("list=")) {
            return ExternalPlaylistSource.YOUTUBE
        }
        return null
    }

    /** Provider-specific playlist id, or null when the link is not a playlist. */
    fun extractId(raw: String, source: ExternalPlaylistSource): String? {
        val value = raw.trim()
        return when (source) {
            ExternalPlaylistSource.SPOTIFY ->
                SPOTIFY_ID.find(value)?.groupValues?.getOrNull(1)
                    ?: SPOTIFY_URI.find(value)?.groupValues?.getOrNull(1)
            ExternalPlaylistSource.APPLE_MUSIC ->
                APPLE_ID.find(value)?.groupValues?.getOrNull(1)
            ExternalPlaylistSource.YOUTUBE -> {
                YOUTUBE_PLAYLIST.find(value)?.groupValues?.getOrNull(1)
                    ?: YOUTUBE_MUSIC_PLAYLIST.find(value)?.groupValues?.getOrNull(1)
                    ?: YOUTUBE_BE_PLAYLIST.find(value)?.groupValues?.getOrNull(1)
            }
        }
    }
    
    /** Check if the link is a playlist (vs track/album) for the detected provider */
    fun isPlaylistLink(raw: String): Boolean {
        val source = detect(raw)
        return source != null && extractId(raw, source) != null
    }
    
    /** Check if the link is a track/album link for a supported provider */
    fun isTrackOrAlbumLink(raw: String): Boolean {
        val value = raw.trim().lowercase()
        return when {
            value.contains("spotify.com/track/") || value.contains("spotify:track:") -> true
            value.contains("spotify.com/album/") || value.contains("spotify:album:") -> true
            value.contains("music.apple.com") && (value.contains("/song/") || value.contains("/album/")) -> true
            value.contains("youtube.com/watch?v=") || value.contains("youtu.be/") && !value.contains("list=") -> true
            value.contains("music.youtube.com/watch?v=") && !value.contains("list=") -> true
            else -> false
        }
    }
}
