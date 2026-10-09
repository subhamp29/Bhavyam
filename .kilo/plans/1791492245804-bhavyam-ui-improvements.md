# Bhavyam UI Improvements Plan

## Codebase Audit Summary

**Stack:** Jetpack Compose, Material 3, Dynamic Color, Kyant Backdrop (liquid glass), Coil, Hilt, Navigation Compose

**Key Files by Screen:**
| Screen | File |
|--------|------|
| Main Shell / Bottom Nav | `app/src/main/java/com/bhavya/music/ui/shell/MainShell.kt` |
| Home/Feed | `app/src/main/java/com/bhavya/music/ui/feed/FeedScreen.kt` |
| Statistics (Home tab) | `app/src/main/java/com/bhavya/music/ui/home/HomeScreen.kt` |
| Playlists List | `app/src/main/java/com/bhavya/music/ui/playlist/PlaylistScreen.kt` |
| Playlist Detail | `app/src/main/java/com/bhavya/music/ui/playlist/PlaylistDetailScreen.kt` |
| Now Playing (Full Player) | `app/src/main/java/com/bhavya/music/ui/player/PlayerHost.kt` (FullPlayer function) |
| Liquid Glass System | `app/src/main/java/com/bhavya/music/ui/theme/LiquidGlass.kt` |
| Shape Tokens | `app/src/main/java/com/bhavya/music/ui/theme/Shape.kt` |
| Theme Setup | `app/src/main/java/com/bhavya/music/ui/theme/Theme.kt` |
| Common Components | `app/src/main/java/com/bhavya/music/ui/common/` |

---

## Phase 1: Layout Bugs (Highest Priority)

### 1.1 Floating Nav Bar Content Padding
**Problem:** `FloatingNavDefaults.ContentBottomPadding = 112.dp` is a magic number. Content gets clipped behind the floating nav bar on Playlists (last item) and Home (text).

**Fix:** Replace with dynamic calculation using `WindowInsets.safeDrawing` + nav bar measured height.
- File: `MainShell.kt` → `FloatingNavDefaults.contentBottomPadding()`
- Measure nav bar height via `onGloballyPositioned` and expose via CompositionLocal
- All scrollable screens (FeedScreen, HomeScreen, PlaylistScreen, PlaylistDetailScreen) already use `FloatingNavDefaults.contentBottomPadding()` — just needs the implementation fixed.

### 1.2 Now Playing Title/Artist Clipping
**Problem:** Title row has heart + lyrics buttons on the right, causing "n 1 • Ep 1" (left) and "Twin Strin" (right) clipping.

**Fix:** In `PlayerHost.kt` → `FullPlayer` function (lines 2609-2663):
- Make title a single left-aligned `Text` with `basicMarquee(iterations = Int.MAX_VALUE)`
- Artist underneath in a `FlowRow` (already split)
- Move heart + lyrics buttons out of the title row → place next to More menu or below progress bar
- Ensure title row has `Modifier.weight(1f).fillMaxWidth()` with proper horizontal constraints

### 1.3 Pluralization
**Current:** `pluralStringResource(R.plurals.playlist_track_count, count)` — already correct in strings.xml (one: "%1$d track", other: "%1$d tracks")

**Action:** Verify all track count displays use this plural resource. Search for hardcoded "tracks" strings.

### 1.4 Tab/Header Name Consistency
**Current:** Bottom nav tab = "Feed" (`R.string.nav_feed`), FeedScreen header = "Home"

**Decision:** Use "Home" for both.
- Change `R.string.nav_feed` → "Home" in strings.xml
- Keep FeedScreen header as "Home" (already correct)

---

## Phase 2: Home Screen Declutter

### 2.1 Hero Card Only
**Keep:** InfiniteRadioHero (lines 886-932 in FeedScreen.kt) as the only large container.

### 2.2 Remove Card Backgrounds from Lower Sections
**Sections to flatten:** Quick access, Quick picks, Because you listen to, Fresh finds, Jump back in, Mixes, Spotlight, Top artists, Heavy rotation, Albums, Charts, New releases, Friends

**Pattern:** Replace `Surface(shape=RoundedCornerShape(24.dp), color=surfaceContainerHigh.copy(alpha=0.5f))` wrapper with direct content + `FeedSectionHeader`.

### 2.3 Quick Access: Compact Chips/Grid
**Current:** `QuickTilesGrid` with large tiles (see FeedScreen lines 310-338)
**Fix:** Convert to 2-column grid of compact rows or chips. Reduce tile height, remove card background.

### 2.4 Section Headers: Reusable Component
**Extract:** `FeedSectionHeader` → make it reusable across all screens
- Shuffle = icon-only button (already exists as `onShuffleClick`)
- "Play all" = smaller tonal button (reduce padding, font size)
- Add `SectionHeader` composable in `ui/common/`

### 2.5 Cap Quick Picks at 4 Rows
**Current:** `QuickPicksRows` renders all items (FeedScreen line 375)
**Fix:** Add `take(4)` or pagination indicator.

### 2.6 Dismissible "Connect Last.fm" Banner on Stats
**Current:** `LocalStatsBanner` in HomeScreen.kt (lines 570-593) — not dismissible
**Fix:** Add dismiss button + persist dismissal in `SessionPreferences` or `SettingsPreferences`

---

## Phase 3: Liquid Glass Nav Bar Consistency

### 3.1 Glass Effect on All Screens
**Current:** `liquidGlassChrome` in `FloatingNavBar` (MainShell.kt line 450) delegates to `drawInteractiveGlass`
**Issues:** 
- Fallback on older API is flat dark pill (`surfaceContainerHighest.copy(alpha=0.8f)`)
- No top highlight border
- No specular gradient

**Fix:** Enhance `drawInteractiveGlass` / `Modifier.liquidGlass` to always render:
- Background blur (API 31+ `Modifier.blur` / `RenderEffect`, fallback: semi-transparent tonal)
- 1dp top highlight border (white/alpha in light, subtle in dark)
- Faint specular gradient (existing `lens` effect in Kyant)

### 3.2 FAB (Sparkle) Visibility
**Current:** Only on Playlists tab (MainShell.kt lines 497-541)
**Decision:** Show on **all three main tabs** (Home, Stats, Playlists) for consistency — the generator is a global action. Keep enter/exit animation.

### 3.3 Selected-Tab Label Expansion
**Current:** AnimatedVisibility with `expandHorizontally` (lines 595-621) — works but verify no layout jumps on small screens.

### 3.4 Contrast Accessibility
**Verify:** Text/icon colors on glass meet WCAG AA over any content. Use `liquidGlassContainerColor` logic (transparent when glass active, opaque fallback).

---

## Phase 4: Now Playing Polish

### 4.1 Art/Title Gap
**Problem:** Empty gap between artwork and title in `FullPlayer` (PlayerHost.kt lines 2338-2511)
**Fix:** Enlarge artwork slightly, rebalance vertical spacing (reduce Spacer heights, adjust Column weights)

### 4.2 Video Thumbnail Handling
**Current:** `PlayerArtwork` uses `ContentScale.Crop` (line 2500)
**Fix:** For video-sourced tracks (has `videoId`), use `ContentScale.Fit` inside rounded container over blurred enlarged copy as background. Keep `Crop` for square album art.
- Detect: `track.videoId.isNotBlank()`
- Implementation in `PlayerArtwork` composable (PlayerHost.kt ~line 1043)

### 4.3 Heart/Lyrics Buttons Crowding Title
**Fix:** Already covered in Phase 1.2 — move out of title row.

### 4.4 "OPUS 158 kbps" Chip
**Current:** `PlayerUtilityControls` → quality badge pill (lines 3324-3415) — heavy, prominent
**Fix:** Make small, low-emphasis: outlined or text-only, reduced padding, smaller font, lower alpha.

### 4.5 Shuffle/Repeat Active States
**Current:** `PlayerModeButton` (lines 3231-3281) — uses `active` param for container color
**Fix:** Ensure clear visual distinction:
- Active: filled/tinted (primaryContainer + onPrimaryContainer)
- Repeat-one: show "1" badge or distinct icon (already uses `RepeatOne` icon)
- Inactive: low-alpha tonal

### 4.6 Play/Pause Pill Width Stability
**Current:** `MainControls` pill width flexes between 112-188dp (line 3125)
**Fix:** Fix pill width to design width (188dp) — remove `coerceIn` floor, or use fixed width with text truncation.

---

## Phase 5: Playlists & Detail Screens

### 5.1 Playlist Detail Header
**Current:** Giant cover with thumbs-up over purple gradient (PlaylistDetailScreen.kt lines 316-349)
**Fix:** For Liked Music: 2x2 mosaic of first 4 album arts. For others: smaller cover (reduce 440dp height, add proper gradient overlay).

### 5.2 Content Descriptions / Tooltips
**Add:** `contentDescription` for lock icon (line 626) and locate icon (line 504) — currently have content descriptions but verify they're meaningful.

### 5.3 Two-Line Titles in Track Rows
**Current:** `NativeTrackRow` (line 1407) and `TrackRow` (HomeScreen line 1124) use `maxLines = 1`
**Fix:** Change to `maxLines = 2` with `overflow = TextOverflow.Ellipsis`

### 5.4 Stats Empty/Low-Data State
**Current:** HomeScreen shows stats card but no special empty state for low data
**Fix:** Add proper empty state in HomeScreen when stats are zero/minimal.

### 5.5 User Avatar in Header
**Current:** `ProfileAvatar` (HomeScreen line 536) shows generic icon when no avatar
**Fix:** Show "Sign in" affordance for guests (clickable → navigate to login), show avatar when signed in.

---

## Phase 6: Design Tokens

### 6.1 Corner Radius Tokens
**Define in Shape.kt:**
```kotlin
// Large: hero cards, playlist detail header
val CornerRadiusLarge = 28.dp  // or 32.dp (ExpressiveHeroShape)

// Medium: list rows, cards
val CornerRadiusMedium = 16.dp  // TrackRowShape, ListContainerShape

// Small: thumbnails, artwork
val CornerRadiusSmall = 12.dp   // ArtworkShape
```

### 6.2 Spacing Scale
**Define in new file `Spacing.kt` or extend `Shape.kt`:**
```kotlin
val SpacingXs = 4.dp
val SpacingSm = 8.dp
val SpacingMd = 16.dp
val SpacingLg = 24.dp
val SpacingXl = 32.dp
```

### 6.3 Replace Hardcoded Values
**Search/replace:** All hardcoded `.dp` values for corners and spacing → use tokens.
**Preserve:** Dynamic color behavior, dark/light theme support.

---

## Implementation Order & Validation Checklist

### After Phase 1:
- [ ] Build passes
- [ ] Playlists screen: last item fully visible above nav bar
- [ ] Home screen: no text clipped by nav bar
- [ ] Now Playing: title marquees, artist visible, heart/lyrics not overlapping
- [ ] Pluralization: "1 track" not "1 tracks"
- [ ] Bottom nav tab reads "Home"

### After Phase 2:
- [ ] Build passes
- [ ] Home screen: only hero has card background
- [ ] Section headers consistent (icon-only shuffle, small tonal "Play all")
- [ ] Quick access: compact 2-col grid, no large empty slots
- [ ] Quick picks capped at 4 rows
- [ ] Stats banner dismissible, persists across restarts

### After Phase 3:
- [ ] Build passes
- [ ] Nav bar glass effect visible on all 3 tabs (blur + highlight + specular)
- [ ] FAB visible on all 3 main tabs
- [ ] No layout jumps during tab switch
- [ ] Contrast passes on light/dark over varied content

### After Phase 4:
- [ ] Build passes
- [ ] Artwork/title gap reduced, vertical balance improved
- [ ] Video thumbnails: Fit + blurred bg; album art: Crop
- [ ] Heart/lyrics buttons relocated
- [ ] Quality badge: small, low-emphasis
- [ ] Shuffle/repeat: clear active states
- [ ] Play/pause pill: fixed width, no resize on toggle

### After Phase 5:
- [ ] Build passes
- [ ] Playlist detail: Liked Music shows 2x2 mosaic, others smaller cover
- [ ] Lock/locate icons have proper content descriptions
- [ ] Track rows allow 2-line titles
- [ ] Stats shows proper empty state
- [ ] Header shows avatar or "Sign in"

### After Phase 6:
- [ ] Build passes
- [ ] No hardcoded corner radius / spacing in modified files
- [ ] Tokens used consistently
- [ ] Dark theme works, light theme not broken
- [ ] 360dp and large screen: nothing clips

---

## Constraints & Notes

- **No behavior/architecture changes** — UI only
- **No new heavy dependencies** — use existing (Kyant, Coil, Material3)
- **Playlist data logic untouched** — separate effort
- **Accessibility:** 48dp touch targets, content descriptions, contrast
- **Test on:** 360dp width (small) and large screen

---

## Open Questions for User

1. **Phase 3 FAB decision confirmed?** Show sparkle FAB on all 3 main tabs (Home, Stats, Playlists) vs. only Playlists.
2. **Phase 4 quality badge:** "OPUS 158 kbps" → outlined chip or text-only? Current is filled tonal pill.
3. **Phase 5 Liked Music mosaic:** Use first 4 album arts in 2x2 grid, or first art + placeholder pattern?
4. **Phase 6 token naming:** Add to existing `Shape.kt` or create separate `Tokens.kt` / `Spacing.kt`?

---