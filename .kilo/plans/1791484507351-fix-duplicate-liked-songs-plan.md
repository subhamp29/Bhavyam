# Fix Duplicate "Liked Songs" Playlists in Bhavyam

## Stack Summary
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose (Material3)
- **Database**: Room (SQLite) — `AppDatabase` v13, `SavedPlaylistEntity` table
- **DI**: Hilt
- **Async**: Coroutines + Flow (StateFlow/MutableSharedFlow)
- **Serialization**: kotlinx.serialization (JSON for tracks)

---

## Root Cause Analysis

### 1. Duplicate Creation (Hypothesis A — CONFIRMED)
**File**: `PlaylistRepository.kt:345-369` (`ensureLikedSongs()`)

```kotlin
suspend fun ensureLikedSongs(): SavedPlaylist = likedSongsMutex.withLock {
    getLikedSongs()?.let { return@withLock it }  // Looks for mode == "liked"
    getAll().firstOrNull { it.title.equals(LIKED_SONGS_TITLE, ignoreCase = true) }?.let { legacy ->
        // Adopts any playlist named "Liked Songs" regardless of mode
        val entity = dao.getById(legacy.id)
        if (entity != null) {
            val adopted = entity.copy(
                title = LIKED_SONGS_TITLE,
                subtitle = "Songs you like in Bhavyam",
                mode = LIKED_SONGS_MODE,
                isPinned = true,
            )
            dao.upsert(adopted)
            ...
            return@withLock adopted.toDomain()
        }
    }
    val created = save(...)  // Creates NEW with mode = "liked"
    ...
}
```

**Bug**: `save()` at line 148-202 checks duplicates by **title + mode + track identity**. If a "Liked Songs" playlist exists with `mode = "custom"` (from an old import/bug), the duplicate check in `save()` **doesn't match** because mode differs (`"custom"` vs `"liked"`). So `save()` creates a **new row** with `mode = "liked"`. Next startup, `ensureLikedSongs()` finds the `"liked"` one and returns it, but the `"custom"` one remains orphaned. Repeat on every app start/sync/like → **36 duplicates**.

**Race condition**: `ensureLikedSongs()` is called from:
- `LikedSongsManager.start()` → `bootstrapForInstalledVersion()` (app start)
- `LikedSongsManager.toggle()` / `like()` (heart tap)
- `PlaylistImportManager.importYtLikedIntoLikedSongs()` (YT sync)
- `PlaylistRepository.createCustom()` (user types "Liked Songs")
- `FeedRepository.loadFeed()` (home screen load)

All call `ensureLikedSongs()` concurrently without a **single** cross-process lock (only `likedSongsMutex` guards the function body, but the check-then-insert in `save()` is outside it).

---

### 2. Import Creates Separate "Liked Music" Entry (Hypothesis B — CONFIRMED)
**File**: `YtMusicLibraryManager.kt:80-93, 189-200, 618-637`

The `playlists` StateFlow **merges** local Room playlists + remote YouTube playlists. For the connected account's "Liked Music" (remote ID `LM`), it injects a synthetic entry:
```kotlin
// Line 189-200: if account doesn't return LM, inject it
listOf(YouTubePlaylistSummary(id = "LM", title = "Liked Music", ...)) + account
```
Then `summaryToPlaylist()` (line 618-637) creates a `SavedPlaylist` with:
- `mode = "youtube_remote"`
- `remotePlaylistId = "LM"`
- `id = stableRemoteId("LM")` (negative hash)

This appears in the Playlists screen **alongside** the local `mode = "liked"` playlist → user sees **two** "Liked Songs"-like entries.

---

### 3. Delete Fails / No-Op (Hypothesis C — PARTIAL)
**File**: `PlaylistRepository.kt:387-392`, `PlaylistViewModel.kt:458-476`, `PlaylistScreen.kt:770-776`

- DAO delete is a simple `DELETE FROM saved_playlists WHERE id = :id` — **no FK cascade** (tracks stored as JSON in same row, so no child table).
- **UI blocks delete for system playlist**: `PlaylistScreen.kt:770-776` hides "Delete" menu when `playlist.mode == LIKED_SONGS_MODE`.
- **But**: user can still delete via:
  - Multi-select delete (line 339-341 in `PlaylistViewModel.kt` — no mode check!)
  - Direct `playlistRepository.delete(id)` call from elsewhere
- After delete, `ensureLikedSongs()` **re-creates** it on next sync/startup/like → "comes back".

---

### 4. UI Observes Reactively (Hypothesis D — FALSE)
**File**: `PlaylistRepository.kt:80-85`, `PlaylistViewModel.kt:127`, `PlaylistScreen.kt:126`

- `PlaylistRepository.playlists` is a `Flow` that emits on `_changes` (line 75-76).
- `PlaylistViewModel` collects `playlistRepository.changes.debounce(750L)` → calls `load()`.
- `PlaylistScreen` uses `collectAsStateWithLifecycle()` on `uiState.playlists`.
- **Reactivity works** — delete DOES refresh the screen. The problem is the **re-creation** after delete.

---

### 5. "1 tracks" Plural Bug
**File**: `PlaylistScreen.kt:685`
```kotlin
"${playlist.remoteTrackCount?.let { "$it tracks" } ?: "Connected account"}"
```
Hardcoded "tracks" — should use plural string resource.

---

## Fix Plan

### 1. Give Built-in Liked Songs a Stable Identity
**Files**: `SavedPlaylistEntity.kt`, `PlaylistRepository.kt`, `DatabaseModule.kt` (migration)

- Add `systemKey` column (TEXT, nullable, unique index) to `saved_playlists`.
- For the built-in Liked Songs: `systemKey = "liked_songs"`.
- For imported YT playlists: `systemKey = "yt_<remoteId>"` (e.g., `"yt_LM"`).
- **Unique index** on `systemKey` prevents duplicates at DB level.
- `ensureLikedSongs()` becomes idempotent **get-or-create inside a transaction**:
  ```sql
  INSERT OR IGNORE INTO saved_playlists (id, title, subtitle, mode, tracksJson, createdAtMillis, systemKey, isPinned)
  VALUES (..., 'liked_songs')
  ```
  Then `SELECT * WHERE systemKey = 'liked_songs'`.

### 2. Key Imported YT Playlists by Remote ID (Upsert)
**Files**: `YtMusicLibraryManager.kt`, `PlaylistRepository.kt`

- In `summaryToPlaylist()`, set `systemKey = "yt_${summary.id}"`.
- In `PlaylistRepository.saveOrUpdateRemote()` (new method), upsert by `systemKey`.
- `YtMusicLibraryManager.publish()` should upsert via repository instead of creating new `SavedPlaylist` objects each time.

### 3. Make Delete Use PK, Respect System Playlists, Cascade
**Files**: `PlaylistRepository.kt`, `PlaylistViewModel.kt`, `PlaylistScreen.kt`

- `delete(id)` stays PK-based (already correct).
- Add `isSystem` check: `systemKey != null` → block delete with toast "System playlists can't be deleted".
- For non-system playlists, delete succeeds.
- Since tracks are JSON in same row, no cascade needed.

### 4. Ensure Reactive List (Already Works)
- No change needed — Flow + `collectAsStateWithLifecycle` is correct.

### 5. One-Time Migration / Startup Cleanup
**File**: `DatabaseModule.kt` (new migration v13→14)

```sql
-- 1. Add systemKey column + unique index
ALTER TABLE saved_playlists ADD COLUMN systemKey TEXT;
CREATE UNIQUE INDEX idx_saved_playlists_systemKey ON saved_playlists(systemKey) WHERE systemKey IS NOT NULL;

-- 2. Merge duplicates: keep OLDEST "Liked Songs" row, move songs, delete rest
--    (Handle both mode="liked" and mode="custom" with title "Liked Songs")
WITH ranked AS (
  SELECT id, title, mode, tracksJson, createdAtMillis,
         ROW_NUMBER() OVER (PARTITION BY title COLLATE NOCASE ORDER BY createdAtMillis ASC) as rn
  FROM saved_playlists
  WHERE title = 'Liked Songs'
)
DELETE FROM saved_playlists
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- 3. Set systemKey on the survivor
UPDATE saved_playlists SET systemKey = 'liked_songs'
WHERE mode = 'liked' AND title = 'Liked Songs' AND systemKey IS NULL;

-- 4. Set systemKey on YT remote playlists (best effort, from mappings)
--    This requires reading DataStore mappings; do in code post-migration.
```

### 6. Fix "1 tracks" → "1 track"
**Files**: `strings.xml`, `PlaylistScreen.kt`

- Add plural string resource: `<plurals name="playlist_track_count">...`
- Use `getQuantityString(R.plurals.playlist_track_count, count, count)`.

---

## Files to Change

| File | Change Type | Description |
|------|-------------|-------------|
| `SavedPlaylistEntity.kt` | Modify | Add `systemKey` column |
| `PlaylistRepository.kt` | Modify | Idempotent `ensureLikedSongs()`, new `saveOrUpdateRemote()` |
| `YtMusicLibraryManager.kt` | Modify | Use `systemKey` for remote playlists, upsert via repo |
| `PlaylistViewModel.kt` | Modify | Block delete for `systemKey != null` |
| `PlaylistScreen.kt` | Modify | Use plural string for track count |
| `strings.xml` | Add | Plural resource for track count |
| `DatabaseModule.kt` | Add | Migration v13→14 with cleanup SQL |
| `AppDatabase.kt` | Modify | Bump version to 14 |
| `LikedSongsManager.kt` | Minor | Remove `dedupe()` (now handled by unique index) |

---

## Manual Test Checklist

| Scenario | Expected |
|----------|----------|
| **Fresh install** | One "Liked Songs" (pinned, `systemKey=liked_songs`), no duplicates |
| **Upgrade from broken DB (v13)** | Migration runs: duplicates merged → one "Liked Songs" survives with all tracks, `systemKey` set, unique index created |
| **Kill/restart app 3×** | Still exactly one "Liked Songs" |
| **Run YT Music sync** | "Liked Music" remote playlist appears as `systemKey=yt_LM` (separate entry, subtitle "YouTube Music • Connected account"), local Liked Songs unchanged |
| **Delete normal playlist** | Removed from list, stays deleted |
| **Try delete Liked Songs** | Toast "System playlists can't be deleted", entry remains |
| **Like a song** | Adds to the single "Liked Songs", no new row created |
| **Import YT Liked Music** | Merges into local Liked Songs, no new playlist created |
| **Check track count text** | "1 track", "2 tracks", "0 tracks" all correct |

---

## Implementation Order

1. **Schema migration** (v13→14) + `systemKey` column + unique index + cleanup SQL
2. **Repository changes**: idempotent `ensureLikedSongs()`, `saveOrUpdateRemote()`
3. **YT Library Manager**: upsert remote playlists by `systemKey`
4. **ViewModel/Screen**: block system playlist delete, plural string
5. **Remove legacy dedupe** in `LikedSongsManager`
6. **Test** all checklist scenarios

---

## Risk Mitigation

- **Migration must not lose data**: cleanup SQL keeps oldest row + all its tracks; only deletes younger duplicates.
- **Unique index on nullable column**: SQLite allows multiple NULLs, only enforces uniqueness on non-NULL values.
- **Concurrent `ensureLikedSongs()`**: transaction + `INSERT OR IGNORE` + `SELECT` is atomic.
- **YT remote playlists**: `systemKey = "yt_<remoteId>"` prevents re-creation on every `publish()` call.