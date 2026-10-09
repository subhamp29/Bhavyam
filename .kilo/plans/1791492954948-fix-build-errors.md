# Fix Pre-existing Build Errors Plan

## Overview
The build has three files with pre-existing syntax/structure errors unrelated to the recent HomeScreen changes. This plan addresses each file systematically.

---

## File 1: FeedScreen.kt (~2111 lines)

### Errors
- **Lines 512-518**: Extra closing braces `}` breaking the LazyColumn item structure
- **Line 563**: Extra closing brace after spotlight section
- **Cascade errors**: All subsequent sections (lines 565+) show "Expecting a top level declaration", "Unresolved reference", "Function declaration must have a name" due to broken brace structure

### Root Cause
Lines 512-518 appear to be leftover braces from a previous edit:
```kotlin
                                 },
                             )
                         
                                 }
                             }
                         }
                     }
```

### Fix Strategy
1. Remove lines 512-518 (the orphaned braces)
2. Remove the extra `}` at line 563 (after spotlight section)
3. Verify the LazyColumn item structure is balanced

### Validation
- Check that each `item { ... }` has matching braces
- Ensure all `if (isSectionVisible(...))` blocks close properly

---

## File 2: PlayerHost.kt (~3858 lines)

### Error
- **Line 2757**: Extra closing brace `}` - "Expecting a top level declaration"

### Root Cause
The composable function `FullPlayerContent` (or similar) has an extra closing brace at line 2757.

### Fix Strategy
1. Locate the matching opening brace for line 2757 (likely around line 2690-2700)
2. Remove the extra `}` at line 2757
3. Verify brace balance in the surrounding composable

### Validation
- Ensure `@Composable fun PlayerProgressSlider(...)` at line 2760 is at top level
- Check all `if` blocks and `let` scopes are properly closed

---

## File 3: MainShell.kt (~642 lines)

### Errors
1. **Line 171**: `private const val BaseContentBottomPadding = 112.dp` — `const val` only allows primitive types or `String`, not `Dp`
2. **Line 180**: `LocalNavBarHeight.current +` — expects `Dp` but gets type from composition local
3. **Line 186**: `val LocalNavBarHeight = staticCompositionLocalOf { BaseContentBottomPadding }` — default value type mismatch
4. **Line 407**: `val navBarHeight = remember { mutableStateOf<Dp>(BaseContentBottomPadding) }` — same const issue

### Root Cause
`Dp` is an inline value class, not a primitive. Cannot be used in `const val`.

### Fix Strategy
1. Change `BaseContentBottomPadding` from `const val` to a regular `val` or use a raw `Float`/`Int` constant
2. Convert to `Dp` at usage sites via `.dp` extension
3. Update all three usage sites (lines 180, 186, 407)

### Recommended Approach
```kotlin
// Replace const val with regular val using raw float
private val BaseContentBottomPaddingDp = 112f

// Usage:
navBarHeight.value = 112.dp  // or BaseContentBottomPaddingDp.dp
staticCompositionLocalOf { BaseContentBottomPaddingDp.dp }
mutableStateOf<Dp>(BaseContentBottomPaddingDp.dp)
```

---

## Execution Order

1. **FeedScreen.kt** — Fix first (most errors, blocks understanding of Feed flow)
2. **PlayerHost.kt** — Quick single-brace fix
3. **MainShell.kt** — Type-system fix for const val

---

## Validation Checklist

After each fix:
- [ ] `./gradlew :app:compileDebugKotlin --no-daemon` passes for that file
- [ ] No cascade errors in dependent files
- [ ] Run full compile to confirm all three files clean

---

## Out of Scope
- FeedScreen logic correctness (only syntax structure)
- PlayerHost UI behavior (only brace balance)
- MainShell nav bar measurement logic (only const val type fix)