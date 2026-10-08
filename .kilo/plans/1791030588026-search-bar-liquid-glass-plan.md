# Liquid Glass search header — player-parity material

## Goal

Give `SearchScreen`'s header the same liquid glass material the mini player uses, with the
results list scrolling underneath it.

Success looks like: the header band, the search pill and the five filter pills are glass;
suggestions / recent searches / explore chips smear behind them as you scroll; everything
is unchanged when the Liquid Glass setting is off.

## Locked decisions

| # | Decision | Rationale |
| --- | --- | --- |
| D1 | **Floating header over a full-bleed list.** Root `Column` becomes a `Box`; the list fills the screen and the header overlays it. | Without this there are no pixels behind the bar. Matches `PlayerHost.kt:666-701` and the existing `ArtistDetailScreen.kt:190-202` + `:730-737` floating-header pattern. |
| D2 | **Player material, fixed luminance.** `Modifier.liquidGlass(backdrop, shape, interactive = false)` (static overload, `luminanceAnimation = 0.5f`). **Do not** copy the 5×5 luminance sampler loop from `PlayerHost.kt:931-961`. | Same vibrancy / `colorControls`, 8dp blur, 0.272 scrim, lens + highlight + press glow. The search page behind the bar is flat mid-tone, so the adaptive loop would almost never move the value — while adding a full-resolution `layer.toImageBitmap()` GPU readback every second on the one screen where the user types. |
| D3 | **7 glass surfaces:** header band + search pill + 5 filter chips. | Explicitly chosen over the single-card approach. See Risks. |
| D4 | **Chips sample the list capture directly, not the band's glass.** No nesting, no `exportedBackdrop`. | Matches how `liquidGlassChrome` already behaves today. Avoids touching `LiquidGlass.kt`. |
| D5 | **Back button and clear (X) stay plain fills.** | The player's own precedent, stated at `PlayerHost.kt:1072`: *"Inner controls sit plain on the glass card (no nested glass)."* Also, `HeaderActionIcon` (`ui/common/ExpressiveHeader.kt:181`) is shared by every screen — changing it would be an app-wide change. |
| D6 | **All glass modifiers use `interactive = false`.** | The static overload's `pressedScale` is 1.12f (`LiquidGlass.kt:450`). On a text field that fights cursor placement; on a ~30dp chip it is oversized. Press feedback for the chips already comes from the existing `clickable` ripple. |

## Why a capture has to be added

The app has exactly three backdrop captures today, and `SearchScreen` is inside none of
them as a *consumer*:

- `navigationBackdrop` — `MainShell.kt:224`, consumed by `FloatingNavBar`
- `miniBackdrop` — `PlayerHost.kt:671`, consumed by `MiniPlayer`
- `playerBackdrop` — `PlayerHost.kt:1833`, provided via `LocalLiquidGlassBackdrop`

`PlayerHost` deliberately does **not** provide `LocalLiquidGlassBackdrop` to `content()`, so
`LocalLiquidGlassBackdrop.current` is `null` inside `SearchScreen`. Also note
`LiquidGlassPreset.Card` is a no-op (`LiquidGlass.kt:706-715`) — the default preset draws
nothing. A screen-scoped capture is therefore mandatory.

## Hard rules for the implementation

1. **Sibling only.** A glass surface must never be a descendant of the capture it samples
   (`docs/liquid-glass-migration.md:27`). The capture goes on the list `Box`; the band, pill
   and chips go in the sibling overlay.
2. **Unconditional `remember`.** `rememberBackdrop(...)` must be called unconditionally;
   gate only its *use*. Toggling the setting must not change composition shape
   (same discipline as `MainShell.kt:224`, `PlayerHost.kt:671`).
3. **Gate the modifier, don't trust the fallback.** `Modifier.liquidGlass` falls back to
   `.clip(shape).background(surfaceContainerHighest.copy(alpha = 0.8f))` when unsupported
   (`LiquidGlass.kt:529-537`). That is *not* today's pill/band colour, so only apply the
   modifier when the backdrop is non-null; otherwise leave the existing `.background(...)`.
4. **Never `RectangleShape`.** `lens` throws `UnsupportedOperationException` for shapes that
   aren't `CornerBasedShape` / `AbsoluteRoundedCornerShape` / `RoundedRectangularShape` on
   API 33+. `SearchHeaderShape` and `CircleShape` are both fine.

## Tasks, in order

All edits in `app/src/main/java/com/bhavya/music/ui/search/SearchScreen.kt` unless noted.

### 1. Add the capture (near the top of `SearchScreen`, ~line 139)

```kotlin
val glassOn = isLiquidGlassBackdropSupported()
val searchBackdrop = rememberBackdrop(MaterialTheme.colorScheme.background) // unconditional
val headerBackdrop = if (glassOn) searchBackdrop else null
```

`rememberBackdrop` paints `drawRect(color)` before `drawContent()`
(`LiquidGlass.kt:115-119`), so the glass still has a theme-coloured substrate to sample at
scroll-top, before any list content exists.

New imports: `com.bhavya.music.ui.theme.isLiquidGlassBackdropSupported`,
`com.bhavya.music.ui.theme.liquidGlass`, `com.bhavya.music.ui.theme.liquidGlassContainerColor`,
`com.bhavya.music.ui.theme.liquidGlassSource`, `com.bhavya.music.ui.theme.rememberBackdrop`,
`androidx.compose.ui.layout.onSizeChanged`.

### 2. Restructure the root (lines 140-148 and 257-507)

Replace the inner `Column` with a `Box`, put the list first as the capture, overlay the header:

```kotlin
Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {          // 140 (unchanged)
    Box(Modifier.fillMaxSize().adaptiveContentWidth(maxWidth = 860.dp)) {        // was Column at 146
        // capture layer — the content the glass refracts
        Box(Modifier.fillMaxSize().liquidGlassSource(headerBackdrop)) {          // 685-pattern
            Box(Modifier.fillMaxSize().safeHorizontalContentPadding()) {        // 257, moved verbatim
                when (state.isShowingSuggestions) { /* …unchanged 258-506… */ }
            }
        }
        // header overlay — sibling consumer of the capture
        Column(Modifier.align(Alignment.TopCenter)) {                           // was 149
            Surface(/* …see task 3… */) { /* 154-254 unchanged */ }
        }
    }
}
```

Move the `when` block **verbatim**. Do not renest or re-indent logic.

`adaptiveContentWidth` is a no-op below 600dp and `widthIn(max).fillMaxWidth()` above
(`ui/common/AdaptiveLayout.kt:44-50`), so phone and tablet alignment are preserved.

### 3. Measure the header for list padding (new)

Add `var headerHeight by remember { mutableIntStateOf(0) }` and
`Modifier.onSizeChanged { headerHeight = it.height }` on the overlay `Column` (task 2).

Use it as `contentPadding.top` for all three `LazyColumn`s in `SearchScreen.kt`
(currently `8.dp` at lines 268 and 427, `12.dp` at line 307):
`top = headerHeight + 8.dp` / `+ 12.dp`.

Measuring beats hardcoding `statusBars + 102.dp`, and it stays correct if the chip row wraps.
Leave every `bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding()`
untouched.

### 4. Header band → glass (lines 149-154)

```kotlin
Surface(
    shape = SearchHeaderShape,                       // rounded bottom 24dp — already correct for a top bar
    color = liquidGlassContainerColor(
        MaterialTheme.colorScheme.surfaceContainer,
        backdrop = headerBackdrop,
    ),
    tonalElevation = if (headerBackdrop != null) 0.dp else 2.dp,
    shadowElevation = 0.dp,
    modifier = Modifier.fillMaxWidth().then(
        if (headerBackdrop != null)
            Modifier.liquidGlass(headerBackdrop, SearchHeaderShape, interactive = false)
        else Modifier
    ),
) { /* 154-254 unchanged */ }
```

`SearchHeaderShape` (`RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)`) is a
`CornerBasedShape`, so lens refraction applies. Keep the inner
`windowInsetsPadding(safeDrawing.only(Top + Horizontal))` at line 158 — the band spans from
y=0 behind the transparent status bar, matching the edge-to-edge setup in
`MainActivity.kt:146`.

### 5. Search pill → glass (lines 193-243)

Inside `BasicTextField`'s `decorationBox`, replace the `.clip(CircleShape).background(pillBg)`
`Row` (lines 194-199) with a `Surface`. Everything from line 200 down is unchanged:

```kotlin
Surface(
    shape = CircleShape,
    color = liquidGlassContainerColor(pillBg, backdrop = headerBackdrop),
    modifier = Modifier.fillMaxSize().then(
        if (headerBackdrop != null)
            Modifier.liquidGlass(headerBackdrop, CircleShape, interactive = false)
        else Modifier
    ),
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { /* 200-242 unchanged: icon, placeholder, innerTextField, clear IconButton */ }
}
```

The `Surface` supplies the circular clip, so drop the explicit `.clip`. Keep the search
icon's `primary` tint and the placeholder's `onSurfaceVariant` at 0.74f — unlike the mini
player, which switches to hard white/black on glass (`PlayerHost.kt:1048-1054`), this field
must keep theme colours because `primary` carries the query-active state.

### 6. Filter chips → glass (`SearchFilterPills`, lines 905-940)

Per chip, keep the existing `.clip(CircleShape).clickable(...)` and add the glass after it:

```kotlin
Surface(
    shape = CircleShape,
    color = liquidGlassContainerColor(pillBg, backdrop = headerBackdrop),
    modifier = Modifier
        .clip(CircleShape)
        .clickable(interactionSource = interactionSource, indication = null,
                   onClick = { onTabSelected(tab) })
        .then(
            if (headerBackdrop != null)
                Modifier.liquidGlass(headerBackdrop, CircleShape, interactive = false)
            else Modifier
        ),
) { /* Text(label) unchanged */ }
```

`SearchFilterPills` reads `headerBackdrop` from the enclosing `SearchScreen` scope — add a
`backdrop: Backdrop?` parameter rather than re-deriving it.

The selected chip keeps `pillBg = colorScheme.primary` (lines 909-912) and stays opaque; only
the four unselected chips go glass. That preserves the selected/active affordance.

Chips live in a `horizontalScroll` row, so they re-sample different backdrop regions as they
move — that works (`LayerBackdrop` resolves via `layoutCoordinates.localPositionOf`), but it
is the main per-frame cost of D3.

## Risks

- **7 offscreen glass layers on a scrolling screen.** This is the real cost of D3. Kyant
  allocates a `GraphicsLayer` + `RenderEffect` per `drawBackdrop` node. If frames drop while
  scrolling results, the first thing to revert is chips → opaque `pillBg` (delete the `.then`
  block at one call site; no structural change needed). Band + pill alone is 2 layers.
- **Chips show list content, not the band.** Because D4 skips nesting, each chip is an
  independent lens over the raw list while sitting on a glass band. Busy-looking in practice.
  Fix if needed: give the band an `exportedBackdrop` and sample
  `rememberCombinedBackdrop(searchBackdrop, bandBackdrop)` — see Out of scope.
- **`SearchHeaderShape`'s rounded bottom corners now clip moving content.** Correct and
  intended, but it is a visible change from the current solid band.
- **Nested capture inside `miniBackdrop`.** `searchBackdrop` will be created inside the layer
  `PlayerHost.kt:685` records. Precedent exists (`navigationBackdrop` does the same), and
  there is no cycle because the capture contains only the list, which does not contain the
  band, pill, chips or the mini player.
- **Layout shift when glass is toggled at runtime** — none, since padding comes from a
  measured height, not from the glass flag.

## Out of scope

- Threading `exportedBackdrop` through `LiquidGlass.kt:443` (`drawInteractiveGlass`) and
  `:722` (`liquidGlassChrome`, which accepts it at line 728 and then drops it at 738-743).
  Only needed for true nested glass. The library supports it and it is safe:
  `DrawBackdropNode.draw()` records only `onDrawBehind + backdrop + onDrawSurface +
  onDrawFront` into the export, so children cannot self-capture.
- The 15 `AssistChip` explore genres (lines 377-390) — 15 more layers is not defensible.
- Live luminance sampling (D2).
- Any other screen's search field (`SettingsScreen.kt:2784`, `DownloadsScreen.kt:1128`,
  `PlaylistDetailScreen.kt:639`).

## Validation

1. `gradlew.bat :app:compileDebugKotlin` — no test or lint task is documented in the repo
   (`app/build.gradle.kts:172-174` sets `abortOnError = false`), so compilation is the gate.
2. Device: API 33+, hardware-accelerated, not low-RAM. Settings → Appearance → Liquid Glass ON.
3. Verify blurred content passes under band, pill and chips while scrolling suggestions,
   recent searches and results. Confirm the bar comes alive on scroll and is calm at rest.
4. Stress: enter/exit the search screen ~20×, open/close the IME, rotate, and scroll the chip
   row. Any native crash here means a self-capture — re-check rule 1.
5. Toggle Liquid Glass OFF and confirm the header matches today's colours and the layout is
   unchanged apart from the list now scrolling under a solid band (the accepted D1 change).
6. API 31/32 device: Kyant's `lens` self-guards on `isRuntimeShaderSupported()`, so expect
   blur + tint with no refraction. Confirm no crash.
7. Low-RAM / software-rendered path: confirm the existing opaque fallback still renders, since
   the glass modifier is gated off there.
8. Tablet / `>=600dp`: confirm the 860dp cap still centres both the capture and the band, and
   that they stay aligned (they must be siblings of the same `Box`).