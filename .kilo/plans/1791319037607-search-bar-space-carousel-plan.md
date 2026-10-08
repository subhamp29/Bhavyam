# Plan: Remove Search Bar Blank Space & Add Carousel Animation

## Issues to Fix

1. **Blank space under search bar** - Reduce header bottom padding in `SearchScreen.kt`
2. **Carousel animation for horizontal suggestions** - Add snap-to-item carousel to Top Artists and Top Albums rows in `HomeScreen.kt`

---

## Changes Required

### 1. SearchScreen.kt - Reduce Header Bottom Padding
**File**: `app/src/main/java/com/bhavya/music/ui/search/SearchScreen.kt`
**Line**: 442

**Current**:
```kotlin
.padding(top = 6.dp, bottom = 10.dp)
```

**Change to**:
```kotlin
.padding(top = 6.dp, bottom = 4.dp)
```

This reduces the blank space under the search bar by 6dp.

---

### 2. HomeScreen.kt - Add Carousel/Snap Animation to PodiumSection
**File**: `app/src/main/java/com/bhavya/music/ui/home/HomeScreen.kt`
**Lines**: 603-621 (LazyRow for Artists and Albums)

**Current Implementation** (uses basic LazyRow):
```kotlin
LazyRow(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(horizontal = 2.dp),
) { ... }
```

**New Implementation** (add snap fling behavior):
```kotlin
val snapFlingBehavior = rememberSnapFlingBehavior()

LazyRow(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(horizontal = 2.dp),
    flingBehavior = snapFlingBehavior,
) { ... }
```

Need to add import:
```kotlin
import androidx.compose.foundation.lazy.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.snapFlingBehavior
```

Apply to both:
- Top Artists LazyRow (lines 603-610)
- Top Albums LazyRow (lines 614-621)

---

## Validation Steps

1. Build the project: `./gradlew assembleDebug`
2. Run on device/emulator to verify:
   - Search bar has less space below it
   - Top Artists and Top Albums rows snap to items when swiped horizontally

---

## Risk Assessment

- **Low risk**: Simple padding change in SearchScreen
- **Low risk**: Adding `rememberSnapFlingBehavior` is a standard Compose API for carousel behavior
- No changes to data models, view models, or business logic

---

## Dependencies

- Requires Compose Foundation 1.6+ for `rememberSnapFlingBehavior` (already available in modern Compose)