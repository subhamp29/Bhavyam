package com.bhavya.music.data.playlist

import com.bhavya.music.data.generate.GeneratedTrack

/**
 * Interface for external playlist providers.
 * Each provider can detect if a URL belongs to it, extract the playlist ID,
 * and fetch/parse the playlist rows.
 */
interface PlaylistProvider {
    /** The source enum value for this provider */
    val source: ExternalPlaylistSource
    
    /** Check if the given URL/string belongs to this provider */
    fun canHandle(raw: String): Boolean
    
    /** Extract the playlist ID from a URL/string, or null if not a playlist link */
    fun extractPlaylistId(raw: String): String?
    
    /** Fetch and parse the playlist, returning track rows (no matching yet) */
    suspend fun fetchPlaylistRows(urlOrId: String): ExternalPlaylistResult
    
    /** Fetch, parse, and match to playable tracks */
    suspend fun fetchAndMatch(urlOrId: String): ExternalImportResult
}