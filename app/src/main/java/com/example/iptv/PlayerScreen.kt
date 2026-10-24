package com.example.iptv

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.imageLoader
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/** Mejor resolución que soporta la pantalla (lado largo x lado corto). */
data class DisplayInfo(val w: Int, val h: Int, val modeId: Int) {
    val is4k: Boolean get() = w >= 3800 && h >= 2100
}

@Suppress("DEPRECATION")
fun bestDisplayInfo(ctx: Context): DisplayInfo {
    val d = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    if (Build.VERSION.SDK_INT >= 23) {
        val m = d.supportedModes.maxWithOrNull(
            compareBy<Display.Mode>({ it.physicalWidth.toLong() * it.physicalHeight }, { it.refreshRate })
        )
        if (m != null) {
            return DisplayInfo(
                max(m.physicalWidth, m.physicalHeight),
                min(m.physicalWidth, m.physicalHeight),
                m.modeId
            )
        }
    }
    val dm = DisplayMetrics()
    d.getRealMetrics(dm)
    return DisplayInfo(max(dm.widthPixels, dm.heightPixels), min(dm.widthPixels, dm.heightPixels), 0)
}

/** Pide al sistema la resolución más alta de la pantalla (p. ej. 3840x2160 en una TV 4K). */
fun applyBestDisplayMode(act: Activity) {
    if (Build.VERSION.SDK_INT < 23) return
    val info = bestDisplayInfo(act)
    if (info.modeId == 0) return
    val lp = act.window.attributes
    lp.preferredDisplayModeId = info.modeId
    act.window.attributes = lp
}

/* ---------- Construcción del reproductor (compartida por pantalla completa y vista previa) ---------- */

private fun isSoftwareCodec(name: String): Boolean {
    val n = name.lowercase()
    return n.startsWith("omx.google.") || n.startsWith("c2.android.") ||
        n.startsWith("omx.ffmpeg.") || n.contains(".sw.") || n.endsWith(".sw")
}

/** Prefiere los decodificadores por hardware; si el equipo no tiene, usa los demás. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun hardwareFirst(): MediaCodecSelector = MediaCodecSelector { mime, secure, tunneling ->
    val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
    val hw = all.filter { !isSoftwareCodec(it.name) }
    if (hw.isEmpty()) all else hw
}

@androidx.annotation.OptIn(UnstableApi::class)
fun buildPlayer(ctx: Context, opts: PlayerOpts, preview: Boolean): ExoPlayer {
    val selector = DefaultTrackSelector(ctx)
    val params = selector.buildUponParameters()
        .setForceHighestSupportedBitrate(!preview)          // pantalla completa: siempre la mejor calidad
        .setAllowVideoMixedMimeTypeAdaptiveness(true)
        .setTunnelingEnabled(opts.tunnel && !preview)
    if (preview) params.setMaxVideoSize(1280, 720)           // la vista previa no necesita más de 720p
    selector.setParameters(params.build())

    val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
    if (opts.decoder == Decoder.HARDWARE) renderers.setMediaCodecSelector(hardwareFirst())

    val loadControl = if (preview) {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 8_000, 1_500, 3_000)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .build()
    } else {
        // Buffer acotado para TV boxes con poca RAM
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(opts.buffer.minMs, opts.buffer.maxMs, 2_500, 5_000)
            .setTargetBufferBytes(opts.buffer.mb * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
    }

    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    val sources = DefaultMediaSourceFactory(ctx)
        .setDataSourceFactory(http)
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

    return ExoPlayer.Builder(ctx, renderers)
        .setTrackSelector(selector)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(sources)
        .build()
}

/* ---------- Pantalla completa ---------- */

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(req: PlayRequest, epg: String?, onEnded: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val prefs = remember { Prefs(ctx) }
    val opts = remember { prefs.loadOpts() }
    val info = remember { bestDisplayInfo(ctx) }
    var res by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(true) }
    val currentOnEnded by rememberUpdatedState(onEnded)

    BackHandler(onBack = onBack)

    // Pantalla completa y horizontal mientras se reproduce
    DisposableEffect(Unit) {
        val controller = act?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val exo = remember {
        buildPlayer(ctx, opts, false).apply {
            val start = if (req.live) 0L else prefs.position(req.url)   // continúa donde se quedó
            setMediaItem(MediaItem.fromUri(req.url), start)
            playWhenReady = true
            prepare()
        }
    }

    fun savePos() {
        if (req.live) return
        val pos = exo.currentPosition
        val dur = exo.duration
        val done = exo.playbackState == Player.STATE_ENDED || (dur > 0 && pos > dur - 30_000)
        prefs.savePosition(req.url, if (done || pos < 15_000) 0L else pos)
    }

    // Libera la memoria de las imágenes del catálogo para dejarle toda la RAM al video (sobre todo en 4K)
    LaunchedEffect(Unit) { ctx.imageLoader.memoryCache?.clear() }

    // Guarda la posición cada 30 s por si el sistema cierra la app
    LaunchedEffect(exo) {
        while (true) {
            delay(30_000)
            savePos()
        }
    }

    // Muestra el título (y la guía) unos segundos
    LaunchedEffect(req.url, epg) {
        showInfo = true
        delay(6_000)
        showInfo = false
    }

    DisposableEffect(exo) {
        val handler = Handler(Looper.getMainLooper())
        var triedAlt = false
        var retries = 0
        // Si el formato elegido para Live falla, prueba el otro (.m3u8 <-> .ts)
        val alt = when {
            req.url.endsWith(".m3u8") -> req.url.removeSuffix(".m3u8") + ".ts"
            req.url.endsWith(".ts") -> req.url.removeSuffix(".ts") + ".m3u8"
            else -> null
        }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) {
                    val tag = if (videoSize.width >= 3800) " · 4K" else ""
                    res = "${videoSize.width}×${videoSize.height}$tag"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !req.live) currentOnEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                    return
                }
                if (req.live && !triedAlt && alt != null) {
                    triedAlt = true
                    exo.setMediaItem(MediaItem.fromUri(alt))
                    exo.prepare()
                    exo.play()
                } else if (req.live && retries < 5) {
                    // Reconexión automática si el canal se corta
                    retries++
                    handler.postDelayed({
                        exo.prepare()
                        exo.play()
                    }, 2_000)
                } else {
                    err = "No se pudo reproducir (${error.errorCodeName})"
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            handler.removeCallbacksAndMessages(null)
            exo.removeListener(listener)
            savePos()
            exo.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    post { requestFocus() }   // el control remoto (D-pad / teclas multimedia) actúa sobre el reproductor
                }
            }
        )
        if (showInfo) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(req.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (req.live && epg != null) {
                    Text(epg, color = Color.LightGray, fontSize = 13.sp)
                }
            }
        }
        if (res.isNotEmpty()) {
            Text(
                text = res + if (info.is4k) " · pantalla 4K" else "",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        err?.let {
            Text(
                text = it,
                color = Color(0xFFFF8A80),
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000))
                    .padding(16.dp)
            )
        }
    }
}

/* ---------- Vista previa pequeña (Live en columnas) ---------- */

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PreviewPlayer(url: String?, opts: PlayerOpts, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val exo = remember { buildPlayer(ctx, opts, true) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    LaunchedEffect(url) {
        failed = false
        if (url == null) {
            exo.stop()
        } else {
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    FocusBox(modifier.background(Color.Black), onClick) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                }
            }
        )
        if (url == null) {
            Text("Elige un canal", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        } else if (failed) {
            Text("Sin señal", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
