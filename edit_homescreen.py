import re

filepath = r'E:\music exe\LastWave-Native-main\app\src\main\java\com\bhavya\music\ui\home\HomeScreen.kt'

with open(filepath, 'r') as f:
    content = f.read()

# 1. Remove the rememberSnapFlingBehavior import
content = content.replace(
    'import androidx.compose.foundation.lazy.rememberLazyListState\nimport androidx.compose.foundation.lazy.rememberSnapFlingBehavior',
    'import androidx.compose.foundation.lazy.rememberLazyListState\nimport androidx.compose.animation.core.FastOutSlowInEasing\nimport androidx.compose.animation.core.tween\nimport androidx.compose.foundation.ExperimentalFoundationApi\nimport androidx.compose.foundation.FlingBehavior\nimport androidx.compose.foundation.ScrollState\nimport androidx.compose.runtime.Composable\nimport androidx.compose.ui.unit.toPx'
)

# 2. Add the carousel function before PodiumSection
carousel_func = '''

@OptIn(ExperimentalFoundationApi::class)
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

content = content.replace(
    '}\n\n@Composable\nprivate fun PodiumSection(',
    '}' + carousel_func + '@Composable\nprivate fun PodiumSection('
)

# 3. Update Top Artists
content = content.replace(
    '''if (artists.isNotEmpty()) {
            PodiumSectionTitle("Top Artists")
            val snapFlingBehavior = rememberSnapFlingBehavior()
            LazyRow(''',
    '''if (artists.isNotEmpty()) {
            PodiumSectionTitle("Top Artists")
            val snapFlingBehavior = rememberCarouselFlingBehavior(84.dp)
            LazyRow('''
)

# 4. Update Top Albums
content = content.replace(
    '''if (albums.isNotEmpty()) {
            PodiumSectionTitle("Top Albums")
            val snapFlingBehavior = rememberSnapFlingBehavior()
            LazyRow(''',
    '''if (albums.isNotEmpty()) {
            PodiumSectionTitle("Top Albums")
            val snapFlingBehavior = rememberCarouselFlingBehavior(96.dp)
            LazyRow('''
)

with open(filepath, 'w') as f:
    f.write(content)

print('Changes applied successfully!')