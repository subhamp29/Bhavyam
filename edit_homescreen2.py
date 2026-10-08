import re

filepath = r'E:\music exe\LastWave-Native-main\app\src\main\java\com\bhavya\music\ui\home\HomeScreen.kt'

with open(filepath, 'r') as f:
    lines = f.readlines()

# 1. Remove line 37 (rememberSnapFlingBehavior import) and add new imports after line 36
new_lines = []
for i, line in enumerate(lines):
    # Skip the rememberSnapFlingBehavior import line
    if 'rememberSnapFlingBehavior' in line:
        continue
    new_lines.append(line)
    # After rememberLazyListState import, add new imports
    if 'rememberLazyListState' in line and i == 35:  # line 36 (0-indexed 35)
        new_lines.append('import androidx.compose.animation.core.FastOutSlowInEasing\n')
        new_lines.append('import androidx.compose.animation.core.tween\n')
        new_lines.append('import androidx.compose.foundation.ExperimentalFoundationApi\n')
        new_lines.append('import androidx.compose.foundation.FlingBehavior\n')
        new_lines.append('import androidx.compose.foundation.ScrollState\n')
        new_lines.append('import androidx.compose.runtime.Composable\n')
        new_lines.append('import androidx.compose.ui.unit.toPx\n')

# 2. Add carousel function before PodiumSection
carousel_func = '''\n@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberCarouselFlingBehavior(itemWidthDp: Dp = 84.dp): FlingBehavior = remember {
    val itemWidth = itemWidthDp.toPx() + 12.dp.toPx() // card width + spacing
    object : FlingBehavior {
        override fun performFling(
            scrollState: ScrollState,
            velocity: Float,
            animationSpec: AnimationSpec<Float>,
            performDrag: (Float) -> Float
        ) {
            val currentOffset = scrollState.value
            val targetItem = (currentOffset / itemWidth).roundToInt()
            val targetOffset = targetItem * itemWidth
            scrollState.animateScrollTo(
                targetOffset.toFloat(),
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
        
        override fun performDrag(
            scrollState: ScrollState,
            dragDistance: Float,
            performDrag: (Float) -> Float
        ) = performDrag(dragDistance)
    }
}

'''

# Find the line with "@Composable\nprivate fun PodiumSection(" and insert before it
final_lines = []
for i, line in enumerate(new_lines):
    if '@Composable' in line and i + 1 < len(new_lines) and 'private fun PodiumSection' in new_lines[i + 1]:
        final_lines.append(carousel_func)
    final_lines.append(line)

# 3. Update rememberSnapFlingBehavior calls
content = ''.join(final_lines)
content = content.replace(
    'val snapFlingBehavior = rememberSnapFlingBehavior()',
    'val snapFlingBehavior = rememberCarouselFlingBehavior(84.dp)'
)
content = content.replace(
    'val snapFlingBehavior = rememberSnapFlingBehavior()',
    'val snapFlingBehavior = rememberCarouselFlingBehavior(96.dp)'
)

with open(filepath, 'w') as f:
    f.write(content)

print('Changes applied successfully!')