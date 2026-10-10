package com.bhavya.music.data.playlist

import android.util.Log
import androidx.compose.runtime.Immutable
import com.bhavya.music.data.local.db.SavedPlaylistDao
import com.bhavya.music.data.local.db.SavedPlaylistEntity
import com.bhavya.music.data.generate.GeneratedTrack
import com.bhavya.music.data.generate.StoredTrack
import com.bhavya.music.data.generate.sameSongAs
import com.bhavya.music.data.generate.toGenerated
import com.bhavya.music.data.generate.toStored
import com.bhavya.music.data.generate.youtubeVideoIdOrNull
import com.bhavya.music.data.music.InnerTubeMusicApi
import com.bhavya.music.util.FileExportHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Port of playlist.js's `lw_playlists` model: id, title, subtitle, mode,
 *  tracks, date. [id] doubles as the creation timestamp (matches the
 *  original's `Date.now()`-based id). */
@Immutable
data class SavedPlaylist(
    val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracks: List<GeneratedTrack>,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
    val customCoverUri: String? = null,
    val isPinned: Boolean = false,
    /** Non-null only for a connected-account playlist that has not been made local. */
    val remotePlaylistId: String? = null,
    val remoteArtworkUrl: String? = null,
    val remoteTrackCount: Int? = null,
    /** Non-null for system playlists (built-in Liked Songs, imported YT playlists). */
    val systemKey: String? = null,
)

val SavedPlaylist.isYouTubeOnly: Boolean
    get() = remotePlaylistId != null

/** True for playlists that can be deleted by the user.
 * - The built-in Liked Songs (systemKey == "liked_songs") is never deletable.
 * - YouTube Music's Liked Music playlist (remotePlaylistId == "LM" or "VLLM")
 *   is not deletable because it's a system mirror managed by YouTube.
 * - All other playlists (custom, generated, imported) are deletable. */
val SavedPlaylist.isDeletable: Boolean
    get() = systemKey != "liked_songs" &&
        remotePlaylistId?.removePrefix("VL") != "LM"

private const val MAX_SAVED_PLAYLISTS = 20
private const val STARTUP_SYNC_WAIT_MS = 2_000L
private const val TAG = "PlaylistRepository"
const val LIKED_SONGS_MODE = "liked"
const val LIKED_SONGS_TITLE = "Liked Songs"

@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: SavedPlaylistDao,
    private val fileExportHelper: FileExportHelper,
    private val exportEvents: PlaylistExportEvents,
    private val publicMirror: PlaylistPublicMirror,
    private val innerTube: InnerTubeMusicApi,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = _changes.asSharedFlow()
    private val likedSongsMutex = Mutex()
    private val saveMutex = Mutex()

    val playlists: Flow<List<SavedPlaylist>> = flow {
        emit(getAll())
        changes.collect {
            emit(getAll())
        }
    }

    // Fire-and-forget scope for the public Downloads export copy — outlives
    // any single screen's viewModelScope (it's a Singleton), and its own
    // failure must never fail or delay save() itself.
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupSync = exportScope.async {
        runCatching { publicMirror.restoreIfDatabaseEmpty() }
            .onSuccess { restored ->
                if (restored > 0) _changes.tryEmit(Unit)
            }
            .onFailure { error ->
                Log.e(TAG, "Playlist JSON startup sync failed; continuing with Room", error)
            }
    }
    @Volatile private var startupSyncTimedOut = false

    private suspend fun awaitStartupSync() {
        if (startupSyncTimedOut) return
        if (withTimeoutOrNull(STARTUP_SYNC_WAIT_MS) { startupSync.await() } == null) {
            startupSyncTimedOut = true
        }
    }

    private suspend fun filterPlayable(tracks: List<GeneratedTrack>): List<GeneratedTrack> = tracks


    /** Newest first — matches _plRenderSaved()'s display order (the
     *  original reverses its append-ordered array before rendering). */
    suspend fun getAll(): List<SavedPlaylist> {
        return try {
            dao.getAll().map { it.toDomain() }.sortedByDescending { it.createdAtMillis }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read playlists from Room DAO", e)
            val restored = runCatching { publicMirror.restoreIfDatabaseEmpty() }.getOrDefault(0)
            if (restored > 0) {
                runCatching { dao.getAll().map { it.toDomain() }.sortedByDescending { it.createdAtMillis } }
                    .getOrDefault(emptyList())
            } else {
                loadPlaylistsFromPublicMirror()
            }
        }
    }

    suspend fun getById(id: Long): SavedPlaylist? {
        return try {
            dao.getById(id)?.toDomain() ?: getAll().firstOrNull { it.id == id }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get playlist $id from Room DAO", e)
            getAll().firstOrNull { it.id == id }
        }
    }

    /**
     * Saves a new playlist. Guards against accidental double-saves the same
     * way the original's savePlaylist() does: skips saving only when an existing
     * playlist has the same title and complete ordered track identity.
     * Returns the saved playlist, or the pre-existing duplicate if skipped.
     */
    suspend fun save(title: String, subtitle: String, mode: String, tracks: List<GeneratedTrack>, discoverSignature: String? = null): SavedPlaylist = saveMutex.withLock {
        val existing = runCatching { getAll() }.getOrDefault(emptyList())
        val playableTracks = if (mode == "custom" && tracks.isEmpty()) emptyList() else filterPlayable(tracks)
        existing.firstOrNull {
            it.mode == mode &&
                it.title.equals(title, ignoreCase = true) &&
                sameOrderedTrackIdentity(it.tracks, playableTracks)
        }
            ?.let { return@withLock it }

        // Same title+mode but different content (double-tap generate race,
        // re-import, progressive album save): suffix instead of stacking an
        // identical-titled sibling that reads as a duplication bug.
        var finalTitle = title
        if (existing.any { it.mode == mode && it.title.equals(finalTitle, ignoreCase = true) }) {
            var counter = 2
            while (existing.any { it.mode == mode && it.title.equals(finalTitle, ignoreCase = true) }) {
                finalTitle = "$title $counter"
                counter++
            }
        }

        val entity = SavedPlaylistEntity(
            id = maxOf(System.currentTimeMillis(), (existing.maxOfOrNull { it.id } ?: 0L) + 1L),
            title = finalTitle,
            subtitle = subtitle,
            mode = mode,
            tracksJson = json.encodeToString(playableTracks.map { it.toStored() }),
            createdAtMillis = System.currentTimeMillis(),
            discoverSignature = discoverSignature,
            customCoverUri = null,
            isPinned = false,
            systemKey = null,
            remotePlaylistId = null,
            remoteArtworkUrl = null,
            remoteTrackCount = null,
        )
        try {
            dao.upsert(entity)
            dao.trimGeneratedToNewest(MAX_SAVED_PLAYLISTS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upsert playlist entity in Room", e)
        }
        val saved = entity.toDomain()
        syncPublicMirrorInBackground()
        _changes.tryEmit(Unit)

        // Best-effort copy to the public Downloads folder. Room is already
        // the source of truth the app reads from, so this never blocks the
        // caller — and a failure here doesn't mean the playlist was lost.
        if (mode != "custom" && tracks.isNotEmpty()) {
            exportScope.launch {
                fileExportHelper.savePlaylistToPublicDownloads(saved.title, saved.tracks)
                    .onFailure { e ->
                        Log.e(TAG, "Public Downloads export failed for \"${saved.title}\"", e)
                    }
            }
        }

        saved
    }

    /**
     * Upserts a remote YouTube playlist by systemKey (e.g., "yt_LM" for Liked Music).
     * Used by YtMusicLibraryManager to keep the local mirror in sync without creating duplicates.
     */
    suspend fun saveOrUpdateRemote(
        systemKey: String,
        title: String,
        subtitle: String,
        tracks: List<GeneratedTrack>,
        remotePlaylistId: String,
        remoteArtworkUrl: String?,
        remoteTrackCount: Int?,
    ): SavedPlaylist = saveMutex.withLock {
        val existing = runCatching { dao.getBySystemKey(systemKey) }.getOrNull()
        val tracksJson = json.encodeToString(tracks.map { it.toStored() })
        val now = System.currentTimeMillis()
        val entity = existing?.let { e ->
            e.copy(
                title = title,
                subtitle = subtitle,
                tracksJson = tracksJson,
                remotePlaylistId = remotePlaylistId,
                remoteArtworkUrl = remoteArtworkUrl,
                remoteTrackCount = remoteTrackCount,
            )
        } ?: SavedPlaylistEntity(
            id = maxOf(now, (runCatching { getAll() }.getOrDefault(emptyList()).maxOfOrNull { it.id } ?: 0L) + 1L),
            title = title,
            subtitle = subtitle,
            mode = "youtube_remote",
            tracksJson = tracksJson,
            createdAtMillis = now,
            remotePlaylistId = remotePlaylistId,
            remoteArtworkUrl = remoteArtworkUrl,
            remoteTrackCount = remoteTrackCount,
            systemKey = systemKey,
            isPinned = false,
            discoverSignature = null,
            customCoverUri = null,
        )
        dao.upsert(entity)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        entity.toDomain()
    }

    private fun sameOrderedTrackIdentity(
        existing: List<GeneratedTrack>,
        incoming: List<GeneratedTrack>,
    ): Boolean = existing.size == incoming.size && existing.zip(incoming).all { (left, right) ->
        left.sameSongAs(right)
    }

    /**
     * Newest playlist with this title in the given mode (custom album saves
     * included), or null. Lets callers detect an already-saved album without
     * creating a same-title duplicate.
     */
    suspend fun findByTitle(title: String, mode: String = "custom"): SavedPlaylist? {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank() || cleanTitle.equals(LIKED_SONGS_TITLE, ignoreCase = true)) return null
        return runCatching { getAll() }.getOrDefault(emptyList())
            .firstOrNull { it.mode == mode && it.title.equals(cleanTitle, ignoreCase = true) }
    }

    suspend fun createCustom(title: String): SavedPlaylist {
        val cleanTitle = title.trim()
        if (cleanTitle.equals(LIKED_SONGS_TITLE, ignoreCase = true)) return ensureLikedSongs()
        runCatching { getAll() }.getOrDefault(emptyList()).firstOrNull {
            it.mode == "custom" && it.title.equals(cleanTitle, ignoreCase = true)
        }
            ?.let { return it }
        return save(
            title = cleanTitle,
            subtitle = "Custom playlist",
            mode = "custom",
            tracks = emptyList(),
        )
    }

    suspend fun rename(id: Long, title: String): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        val cleanTitle = title.trim()
        if (entity.mode == LIKED_SONGS_MODE || cleanTitle.equals(LIKED_SONGS_TITLE, ignoreCase = true)) {
            return entity.toDomain()
        }
        if (cleanTitle.isBlank()) return entity.toDomain()
        val updated = entity.copy(title = cleanTitle)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun setCustomCover(id: Long, uri: String?): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        val cleanUri = uri?.trim()?.takeIf { it.isNotBlank() }
        val updated = entity.copy(customCoverUri = cleanUri)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun setPinned(id: Long, pinned: Boolean): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        if (entity.isPinned == pinned) return entity.toDomain()
        val updated = entity.copy(isPinned = pinned)
        dao.upsert(updated)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    /**
     * Appends a track, serialized through [saveMutex] like [moveTrack]: the
     * duplicate check reads the just-written row, so a double-tap (or a
     * confirm dialog tapped twice) can never stack the same track twice.
     */
    suspend fun addTrack(
        id: Long,
        track: GeneratedTrack,
        allowDuplicate: Boolean = false,
    ): SavedPlaylist? {
        awaitStartupSync()
        return saveMutex.withLock {
            val entity = dao.getById(id) ?: return@withLock null
            val playlist = entity.toDomain()
            if (playlist.mode != "custom" && playlist.mode != LIKED_SONGS_MODE) return@withLock playlist
            if ((playlist.mode == LIKED_SONGS_MODE || !allowDuplicate) && playlist.tracks.any { it.sameSongAs(track) }) return@withLock playlist
            if (track.youtubeVideoIdOrNull() == null && !innerTube.isPlayable(track.name, track.artist)) return@withLock playlist
            val updatedTracksJson = json.encodeToString((playlist.tracks + track).map { it.toStored() })
            val updated = entity.copy(tracksJson = updatedTracksJson)
            dao.upsert(updated)
            syncPublicMirror()
            _changes.tryEmit(Unit)
            updated.toDomain()
        }
    }

    suspend fun removeTrack(id: Long, index: Int): SavedPlaylist? {
        awaitStartupSync()
        return saveMutex.withLock {
            val entity = dao.getById(id) ?: return@withLock null
            val playlist = entity.toDomain()
            if (index !in playlist.tracks.indices) return@withLock playlist
            val updatedTracks = playlist.tracks.toMutableList().apply { removeAt(index) }
            val updated = entity.copy(tracksJson = json.encodeToString(updatedTracks.map { it.toStored() }))
            dao.upsert(updated)
            syncPublicMirror()
            _changes.tryEmit(Unit)
            updated.toDomain()
        }
    }

    /**
     * Permanently moves a track within a local playlist and persists the new
     * order to Room (source of truth). No-op for out-of-range indices.
     * Serialized through [saveMutex]: a drag fires one persist per crossed
     * row, and concurrent read-modify-writes would otherwise lose moves so
     * the order snaps back instead of sticking.
     */
    suspend fun moveTrack(id: Long, fromIndex: Int, toIndex: Int): SavedPlaylist? {
        if (fromIndex == toIndex) return getById(id)
        awaitStartupSync()
        return saveMutex.withLock {
            val entity = dao.getById(id) ?: return@withLock null
            val playlist = entity.toDomain()
            if (fromIndex !in playlist.tracks.indices || toIndex !in playlist.tracks.indices) return@withLock playlist
            val updatedTracks = playlist.tracks.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            val updated = entity.copy(tracksJson = json.encodeToString(updatedTracks.map { it.toStored() }))
            dao.upsert(updated)
            syncPublicMirror()
            _changes.tryEmit(Unit)
            updated.toDomain()
        }
    }

    suspend fun getLikedSongs(): SavedPlaylist? =
        getAll().firstOrNull { it.systemKey == "liked_songs" }
            ?: getAll().firstOrNull { it.mode == LIKED_SONGS_MODE }

    /** Creates the built-in local playlist only when it is genuinely absent.
     * Idempotent: uses INSERT OR IGNORE on systemKey to avoid races. */
    suspend fun ensureLikedSongs(): SavedPlaylist = likedSongsMutex.withLock {
        // Fast path: check if already exists by systemKey
        runCatching { dao.getBySystemKey("liked_songs") }
            .getOrNull()
            ?.toDomain()
            ?.let { return@withLock it }

        // Insert or get existing in a transaction
        val entity = SavedPlaylistEntity(
            id = maxOf(System.currentTimeMillis(), (runCatching { getAll() }.getOrDefault(emptyList()).maxOfOrNull { it.id } ?: 0L) + 1L),
            title = LIKED_SONGS_TITLE,
            subtitle = "Songs you like in Bhavyam",
            mode = LIKED_SONGS_MODE,
            tracksJson = json.encodeToString(emptyList<GeneratedTrack>()),
            createdAtMillis = System.currentTimeMillis(),
            isPinned = true,
            systemKey = "liked_songs",
            remotePlaylistId = null,
            remoteArtworkUrl = null,
            remoteTrackCount = null,
        )
        dao.upsert(entity)
        syncPublicMirror()
        _changes.tryEmit(Unit)
        entity.toDomain()
    }

    /** Internal sync write: unlike normal editing, this also updates generated/imported playlists. */
    suspend fun replaceTracksForSync(
        id: Long,
        tracks: List<GeneratedTrack>,
        expectedTracks: List<GeneratedTrack>? = null,
    ): SavedPlaylist? {
        awaitStartupSync()
        val entity = dao.getById(id) ?: return null
        if (expectedTracks != null && entity.toDomain().tracks != expectedTracks) return null
        val updated = entity.copy(tracksJson = json.encodeToString(tracks.map { it.toStored() }))
        if (dao.updateTracksIfUnchanged(id, entity.tracksJson, updated.tracksJson) == 0) return null
        syncPublicMirror()
        _changes.tryEmit(Unit)
        return updated.toDomain()
    }

    suspend fun delete(id: Long) {
        awaitStartupSync()
        dao.deleteById(id)
        syncPublicMirror()
        _changes.tryEmit(Unit)
    }

    suspend fun clearAll() {
        awaitStartupSync()
        dao.clear()
        syncPublicMirror()
        _changes.tryEmit(Unit)
    }

    fun publicMirrorPlaylistCount(content: String): Int? = publicMirror.playlistCount(content)

    suspend fun importPublicMirror(content: String): Result<Int> {
        awaitStartupSync()
        return publicMirror.importAndMerge(content).onSuccess { _changes.tryEmit(Unit) }
    }

    suspend fun titles(): List<String> = getAll().map { it.title }

    /** Order-preserving signature of a Discover feed's visible tracks — port
     *  of _discTrackSignature(): used to detect "this exact feed is already
     *  saved" before creating a duplicate. */
    fun discoverSignature(tracks: List<GeneratedTrack>): String =
        tracks.joinToString("|") { it.key }

    suspend fun findByDiscoverSignature(signature: String): SavedPlaylist? =
        getAll().firstOrNull { it.discoverSignature == signature }

    private suspend fun syncPublicMirror() {
        publicMirror.writeFromDatabase().onFailure { e ->
            Log.e(TAG, "Public playlist JSON sync failed", e)
        }
    }

    private fun syncPublicMirrorInBackground() {
        exportScope.launch { syncPublicMirror() }
    }

    private fun SavedPlaylistEntity.toDomain(): SavedPlaylist {
        val tracks = try {
            json.decodeFromString<List<StoredTrack>>(tracksJson).map { it.toGenerated() }
        } catch (e: Exception) {
            emptyList()
        }
        return SavedPlaylist(
            id = id,
            title = title,
            subtitle = subtitle,
            mode = mode,
            tracks = tracks,
            createdAtMillis = createdAtMillis,
            discoverSignature = discoverSignature,
            customCoverUri = customCoverUri,
            isPinned = isPinned,
            remotePlaylistId = remotePlaylistId,
            remoteArtworkUrl = remoteArtworkUrl,
            remoteTrackCount = remoteTrackCount,
            systemKey = systemKey,
        )
    }

    private suspend fun loadPlaylistsFromPublicMirror(): List<SavedPlaylist> {
        return try {
            val content = fileExportHelper.readPublicPlaylistMirror().getOrNull()
                ?: fileExportHelper.readPublicPlaylistRecovery().getOrNull()
                ?: return emptyList()
            val mirror = json.decodeFromString<PlaylistMirrorFile>(content)
            mirror.playlists.map { entry ->
                SavedPlaylist(
                    id = entry.id,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    mode = entry.mode,
                    tracks = entry.tracks.map { it.toGenerated() },
                    createdAtMillis = entry.createdAtMillis,
                    discoverSignature = entry.discoverSignature,
                    customCoverUri = entry.customCoverUri,
                    isPinned = entry.isPinned,
                    systemKey = entry.systemKey,
                )
            }.sortedByDescending { it.createdAtMillis }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Log.e(TAG, "Fallback to public mirror failed", e)
            emptyList()
        }
    }
}
