package com.bhavya.music.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bhavya.music.playback.analysis.BeatEnergySnapshot
import coil.ImageLoader
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.bhavya.music.playback.PlayableTrack
import com.bhavya.music.data.artwork.ArtworkNormalizer
import com.bhavya.music.ui.common.ArtworkViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

@Language("AGSL")
internal val FLUID_AGSL = """
    uniform shader curArt;
    uniform shader prevArt;
    uniform float2 curArtSize;
    uniform float2 prevArtSize;
    uniform float fade;

    uniform float4 sides;
    uniform float4 angles;
    uniform float2 centre0;
    uniform float2 centre1;
    uniform float2 centre2;
    uniform float2 centre3;

    uniform float2 twistA;
    uniform float2 twistB;
    uniform float twistRadius;
    uniform float twistAngle;

    uniform float saturation;
    uniform float brightness;
    uniform float contrast;

    // Beat-reactive uniforms
    uniform float beatBass;      // 0–1, low-frequency energy
    uniform float beatRms;       // 0–1, overall loudness
    uniform int beatOnset;       // 1 on beat detection, 0 otherwise
    uniform float beatIntensity; // 0.1–1.0, user intensity setting

    float2 twist(float2 coord, float2 offset, float radius, float angle) {
        float2 d = coord - offset;
        float dist = length(d);
        if (dist < radius) {
            float ratio = (radius - dist) / radius;
            float a = ratio * ratio * angle;
            float s = sin(a);
            float c = cos(a);
            d = float2(d.x * c - d.y * s, d.x * s + d.y * c);
        }
        return d + offset;
    }

    float2 localOf(float2 p, float2 centre, float angle, float side) {
        float2 d = p - centre;
        float c = cos(-angle);
        float s = sin(-angle);
        return float2(d.x * c - d.y * s, d.x * s + d.y * c) / side + 0.5;
    }

    bool covers(float2 uv) {
        return uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0;
    }

    // HSV conversion helpers for hue shift
    float3 rgb2hsv(float3 c) {
        float4 K = float4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
        float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));
        float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }

    float3 hsv2rgb(float3 c) {
        float4 K = float4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
        float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    half4 main(float2 fragCoord) {
        float2 p = twist(fragCoord, twistA, twistRadius, twistAngle);
        p = twist(p, twistB, twistRadius, -twistAngle);

        float2 uv = clamp(localOf(p, centre0, angles.x, sides.x), 0.0, 1.0);
        float2 u1 = localOf(p, centre1, angles.y, sides.y);
        float2 u2 = localOf(p, centre2, angles.z, sides.z);
        float2 u3 = localOf(p, centre3, angles.w, sides.w);
        if (covers(u1)) uv = u1;
        if (covers(u2)) uv = u2;
        if (covers(u3)) uv = u3;

        float3 cur = float3(curArt.eval(uv * curArtSize).rgb);
        float3 prv = float3(prevArt.eval(uv * prevArtSize).rgb);
        float3 c = mix(prv, cur, fade);

        // Bass-driven hue shift: warm on bass, cool on treble
        float hueShift = beatBass * 0.15; // ~54 degrees max
        float3 hsv = rgb2hsv(c);
        hsv.x = (hsv.x + hueShift) % 1.0;
        c = hsv2rgb(hsv);

        float mid = (c.r + c.g + c.b) / 3.0;
        c = float3(mid) + (c - float3(mid)) * saturation;
        c *= brightness;
        c = c * (1.0 + contrast) - float3(0.5 * contrast);

        // Beat onset pulse wave: expanding ring from center
        if (beatOnset != 0) {
            float2 center = float2(curArtSize.x / 2.0, curArtSize.y / 2.0);
            float dist = length(fragCoord - center);
            float waveSpeed = 2000.0 * beatIntensity;
            float wave = smoothstep(0.0, 50.0 * beatIntensity, dist - seconds * waveSpeed);
            c += float3(0.3, 0.2, 0.1) * beatBass * beatIntensity * (1.0 - wave);
        }

        return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
    }
""".trimIndent()

internal data class FluidCopy(
    val centreX: Float,
    val centreY: Float,
    val side: Float,
    val angle: Float,
)

internal fun fluidCopies(width: Float, height: Float, seconds: Float, seed: Int): List<FluidCopy> {
    val longest = maxOf(width, height)
    val t = seconds * FLOW_SPEED
    val cx = width / 2f
    val cy = height / 2f
    val orbit = { rate: Float, radius: Float, phase: Float ->
        val a = rate * t + phase
        cx + width * radius * cos(a) to cy + width * radius * sin(a)
    }
    val (x3, y3) = orbit(ORBIT_RATE_MID, ORBIT_RADIUS_MID, startPhase(seed, 4))
    val (x4, y4) = orbit(ORBIT_RATE_SMALL, ORBIT_RADIUS_SMALL, startPhase(seed, 5))
    return listOf(
        FluidCopy(cx, cy, longest * SQRT_2, startPhase(seed, 0) + ROTATION[0] * t),
        FluidCopy(width / 2.5f, height / 2.5f, longest * 0.8f, startPhase(seed, 1) + ROTATION[1] * t),
        FluidCopy(x3, y3, longest * 0.5f, startPhase(seed, 2) + ROTATION[2] * t),
        FluidCopy(x4, y4, longest * 0.25f, startPhase(seed, 3) + ROTATION[3] * t),
    )
}

private fun startPhase(seed: Int, index: Int): Float =
    (((seed ushr (index * 5)) and 0x1F) / 32f) * TWO_PI

private const val TWO_PI = 6.2831855f
private val SQRT_2 = sqrt(2f)

private val ROTATION = floatArrayOf(0.06f, -0.12f, 0.06f, -0.08f)

private const val ORBIT_RADIUS_MID = 0.25f
private const val ORBIT_RADIUS_SMALL = 0.10f
private const val ORBIT_RATE_MID = 0.045f
private const val ORBIT_RATE_SMALL = 0.09f
private const val FLOW_SPEED = 2.5f

private const val SATURATION = 1.9f
private const val BRIGHTNESS = 0.52f
private const val CONTRAST = 0.3f

private const val TWIST_ANGLE = 1.6f
private const val TWIST_REACH = 1.0f

private const val BLUR_FRACTION = 0.18f
private const val BLUR_SIGMA_MIN = 80f
private const val BLUR_SIGMA_MAX = 360f

private const val TEXTURE_PX = 256
private const val TEXTURE_CACHE = 3
private const val CROSSFADE_MS = 1400
private const val APPEAR_MS = 800
private const val FRAME_SECONDS = 1f / 30f

// Beat-reactive layer for draw-phase visual effects (no recomposition)
@Composable
fun BeatReactiveLayer(
    beatEnergy: StateFlow<BeatEnergySnapshot?>,
    modifier: Modifier = Modifier,
    settings: com.bhavya.music.data.local.MiscSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var isForeground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { owner, _ ->
            isForeground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Glass capability gate: API 31+, not low-RAM, HW accelerated
    val isGlassCapable = remember {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !activityManager.isLowRamDevice &&
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay
                .let { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.getDisplayId() >= 0 }
    }

    // Battery saver check - read once, update periodically via coroutine
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
    var isPowerSaveMode by remember { mutableStateOf(powerManager.isPowerSaveMode) }
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(powerManager) {
        val job = coroutineScope.launch {
            while (true) {
                delay(5000)
                isPowerSaveMode = powerManager.isPowerSaveMode
            }
        }
        onDispose { job.cancel() }
    }

    // Collect beat energy snapshot at draw rate (~33ms)
    val snapshot by beatEnergy.collectAsStateWithLifecycle(initialValue = null)

    // Apply glass capability and foreground/battery gates
    val enabled = isGlassCapable && isForeground && !isPowerSaveMode &&
        settings.beatReactiveEnabled &&
        (snapshot?.isActive == true)

    val intensity = settings.beatReactiveIntensity.coerceIn(0.1f, 1.0f)
    val rawBass = if (enabled) (snapshot?.bassEnergy?.coerceIn(0f, 1f) ?: 0f) * intensity else 0f
    val rawRms = if (enabled) (snapshot?.overallRms?.coerceIn(0f, 1f) ?: 0f) * intensity else 0f
    val beatDetected = enabled && (snapshot?.beatDetected == true)

    // Flowy exponential moving average for fluid background shader
    val smoothedBass = remember { mutableFloatStateOf(0f) }
    val smoothedRms = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(rawBass, rawRms, enabled) {
        if (!enabled) {
            smoothedBass.floatValue = 0f
            smoothedRms.floatValue = 0f
        } else {
            val bassAlpha = if (rawBass > smoothedBass.floatValue) 0.28f else 0.08f
            smoothedBass.floatValue += (rawBass - smoothedBass.floatValue) * bassAlpha

            val rmsAlpha = if (rawRms > smoothedRms.floatValue) 0.28f else 0.08f
            smoothedRms.floatValue += (rawRms - smoothedRms.floatValue) * rmsAlpha
        }
    }

    // Beat shader parameters for AGSL uniforms
    val beatShaderParams = remember {
        mutableStateOf(BeatShaderParams(0f, 0f, false, intensity))
    }
    beatShaderParams.value = BeatShaderParams(smoothedBass.floatValue, smoothedRms.floatValue, beatDetected, intensity)

    // Provide via CompositionLocal for AgslFluidRenderer to read
    CompositionLocalProvider(LocalBeatShaderParams provides beatShaderParams.value) {
        // Subtle scale pulse (1.0–1.02x) + brightness bump on beat
        // Scale pulse scales with intensity: 1.0 to 1.0 + 0.02 * intensity
        Box(modifier
            .graphicsLayer {
                if (beatDetected) {
                    val pulse = 1f + 0.02f * intensity
                    scaleX = pulse
                    scaleY = pulse
                } else {
                    scaleX = 1f
                    scaleY = 1f
                }
                // Optional: brightness bump via color filter would require a shader
            }
        ) {
            content()
        }
    }
}

// Beat shader parameters passed via CompositionLocal from BeatReactiveLayer to AgslFluidRenderer
data class BeatShaderParams(
    val bass: Float,
    val rms: Float,
    val onset: Boolean,
    val intensity: Float,
)

val LocalBeatShaderParams = staticCompositionLocalOf { BeatShaderParams(0f, 0f, false, 1f) }

private val textureCache = LinkedHashMap<String, Bitmap>()

private suspend fun loadTexture(context: Context, url: String): Bitmap? = withContext(Dispatchers.IO) {
    synchronized(textureCache) {
        textureCache.remove(url)?.also { textureCache[url] = it }
    }?.let { return@withContext it }

    val request = ImageRequest.Builder(context)
        .data(url)
        .size(TEXTURE_PX)
        .allowHardware(false)
        .build()
    val loader = ImageLoader(context)
    val result = runCatching { loader.execute(request) }.getOrNull()
    val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null

    synchronized(textureCache) {
        textureCache[url] = bitmap
        while (textureCache.size > TEXTURE_CACHE) {
            textureCache.remove(textureCache.keys.first())
        }
    }
    bitmap
}

internal fun fluidBlurSigma(width: Float, height: Float, extraBlur: Boolean = false): Float =
    (minOf(width, height) * (if (extraBlur) 0.50f else BLUR_FRACTION)).coerceIn(
        if (extraBlur) 280f else BLUR_SIGMA_MIN,
        if (extraBlur) 720f else BLUR_SIGMA_MAX
    )

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslFluidRenderer {
    private val shader = RuntimeShader(FLUID_AGSL)
    private val paint = Paint()

    fun render(
        canvas: android.graphics.Canvas,
        width: Float,
        height: Float,
        seconds: Float,
        seed: Int,
        current: Bitmap,
        previous: Bitmap,
        fade: Float,
        extraBlur: Boolean = false,
        beatBass: Float = 0f,
        beatRms: Float = 0f,
        beatOnset: Boolean = false,
        beatIntensity: Float = 1f,
    ) {
        val copies = fluidCopies(width, height, seconds, seed)
        val curShader = BitmapShader(current, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val prevShader = BitmapShader(previous, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setInputShader("curArt", curShader)
        shader.setInputShader("prevArt", prevShader)
        shader.setFloatUniform("curArtSize", current.width.toFloat(), current.height.toFloat())
        shader.setFloatUniform("prevArtSize", previous.width.toFloat(), previous.height.toFloat())
        shader.setFloatUniform("fade", fade)
        shader.setFloatUniform("sides", copies[0].side, copies[1].side, copies[2].side, copies[3].side)
        shader.setFloatUniform("angles", copies[0].angle, copies[1].angle, copies[2].angle, copies[3].angle)
        shader.setFloatUniform("centre0", copies[0].centreX, copies[0].centreY)
        shader.setFloatUniform("centre1", copies[1].centreX, copies[1].centreY)
        shader.setFloatUniform("centre2", copies[2].centreX, copies[2].centreY)
        shader.setFloatUniform("centre3", copies[3].centreX, copies[3].centreY)
        shader.setFloatUniform("twistA", width * 0.25f, height)
        shader.setFloatUniform("twistB", width * 0.75f, 0f)
        shader.setFloatUniform("twistRadius", (maxOf(width, height) + minOf(width, height)) / 2f * TWIST_REACH * (1f + beatBass * 0.5f * beatIntensity))
        shader.setFloatUniform("twistAngle", TWIST_ANGLE)
        shader.setFloatUniform("saturation", SATURATION * (1f + beatBass * 0.3f * beatIntensity))
        shader.setFloatUniform("brightness", if (extraBlur) 0.32f else BRIGHTNESS + beatRms * 0.3f * beatIntensity)
        shader.setFloatUniform("contrast", CONTRAST)

        // Beat-reactive uniforms
        shader.setFloatUniform("beatBass", beatBass * beatIntensity)
        shader.setFloatUniform("beatRms", beatRms * beatIntensity)
        shader.setIntUniform("beatOnset", if (beatOnset) 1 else 0)
        shader.setFloatUniform("beatIntensity", beatIntensity)

        paint.shader = shader
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

@Composable
fun FluidArtworkBackground(
    track: PlayableTrack,
    modifier: Modifier = Modifier,
    extraBlur: Boolean = false,
    rotatingBackgroundEnabled: Boolean = true,
    beatEnergy: StateFlow<BeatEnergySnapshot?> = MutableStateFlow(null),
    settings: com.bhavya.music.data.local.MiscSettings = com.bhavya.music.data.local.MiscSettings(),
    artworkViewModel: ArtworkViewModel = hiltViewModel(),
    fallback: @Composable () -> Unit,
) {
    if (!rotatingBackgroundEnabled) {
        Box(modifier) {
            fallback()
        }
        return
    }

    val context = LocalContext.current
    val embeddedUrl = track.artworkUrl
    val isReal = remember(embeddedUrl) { ArtworkNormalizer.isRealImage(embeddedUrl) }
    
    val key = remember(track.title, track.artist) { ArtworkNormalizer.cacheKey(track.title, track.artist) }
    val resolvedUrl by remember(key) {
        artworkViewModel.resolved.map { it[key] }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = artworkViewModel.resolved.value[key])
    
    LaunchedEffect(key) {
        if (!isReal && resolvedUrl.isNullOrBlank()) {
            artworkViewModel.resolve(track.title, track.artist)
        }
    }
    
    val artworkUrl = if (isReal) embeddedUrl else resolvedUrl
    
    var current by remember { mutableStateOf<Bitmap?>(null) }
    var previous by remember { mutableStateOf<Bitmap?>(null) }
    val crossfade = remember { Animatable(1f) }
    val appear = remember { Animatable(0f) }
    val seed = remember { Random.nextInt() }

    val agslRenderer = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { AgslFluidRenderer() }.getOrNull()
        } else {
            null
        }
    }

    LaunchedEffect(artworkUrl) {
        val url = artworkUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val bitmap = loadTexture(context, url) ?: return@LaunchedEffect
        val showing = current
        current = bitmap
        if (showing != null && showing !== bitmap) {
            previous = showing
            crossfade.snapTo(0f)
            crossfade.animateTo(1f, tween(CROSSFADE_MS))
            previous = null
        }
    }

    LaunchedEffect(current != null) {
        if (current != null) appear.animateTo(1f, tween(APPEAR_MS))
    }

    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var pending = 0f
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) pending += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
            }
            if (pending >= FRAME_SECONDS) {
                seconds += pending
                pending = 0f
            }
        }
    }

    // Wrap with beat-reactive layer when enabled
    val beatReactiveEnabled = settings.beatReactiveEnabled
    if (beatReactiveEnabled) {
        BeatReactiveLayer(
            beatEnergy = beatEnergy,
            modifier = modifier,
            settings = settings,
            content = {
                renderFluidContent(
                    modifier = Modifier,
                    current = current,
                    previous = previous,
                    appear = appear,
                    crossfade = crossfade,
                    seconds = seconds,
                    seed = seed,
                    agslRenderer = agslRenderer,
                    extraBlur = extraBlur,
                    fallback = fallback,
                )
            }
        )
    } else {
        renderFluidContent(
            modifier = modifier,
            current = current,
            previous = previous,
            appear = appear,
            crossfade = crossfade,
            seconds = seconds,
            seed = seed,
            agslRenderer = agslRenderer,
            extraBlur = extraBlur,
            fallback = fallback,
        )
    }
}

@Composable
private fun renderFluidContent(
    modifier: Modifier,
    current: Bitmap?,
    previous: Bitmap?,
    appear: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    crossfade: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    seconds: Float,
    seed: Int,
    agslRenderer: AgslFluidRenderer?,
    extraBlur: Boolean,
    fallback: @Composable () -> Unit,
) {
    Box(modifier) {
        if (appear.value < 1f || current == null) {
            fallback()
        }

        if (current != null) {
            // Read beat shader parameters from CompositionLocal (provided by BeatReactiveLayer)
            val beatParams = LocalBeatShaderParams.current

            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = appear.value
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val sigma = fluidBlurSigma(size.width, size.height, extraBlur)
                            // A haunted GPU driver must cost us the blur, never the process.
                            renderEffect = runCatching {
                                RenderEffect
                                    .createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
                                    .asComposeRenderEffect()
                            }.getOrNull()
                        }
                    }
                    .drawBehind {
                        val cur = current ?: return@drawBehind
                        val prv = previous ?: cur

                        if (agslRenderer != null) {
                            drawIntoCanvas { canvas ->
                                agslRenderer.render(
                                    canvas = canvas.nativeCanvas,
                                    width = size.width,
                                    height = size.height,
                                    seconds = seconds,
                                    seed = seed,
                                    current = cur,
                                    previous = prv,
                                    fade = crossfade.value,
                                    extraBlur = extraBlur,
                                    beatBass = beatParams.bass,
                                    beatRms = beatParams.rms,
                                    beatOnset = beatParams.onset,
                                    beatIntensity = beatParams.intensity,
                                )
                            }
                        } else {
                            val copies = fluidCopies(size.width, size.height, seconds, seed)
                            val curImg = cur.asImageBitmap()
                            for (copy in copies) {
                                rotate(
                                    degrees = Math.toDegrees(copy.angle.toDouble()).toFloat(),
                                    pivot = Offset(copy.centreX, copy.copy(angle = copy.angle).centreY)
                                ) {
                                    drawImage(
                                        image = curImg,
                                        dstOffset = IntOffset(
                                            (copy.centreX - copy.side / 2f).toInt(),
                                            (copy.centreY - copy.side / 2f).toInt()
                                        ),
                                        dstSize = IntSize(copy.side.toInt(), copy.side.toInt())
                                    )
                                }
                            }
                        }
                    }
            )
        }
    }
}
