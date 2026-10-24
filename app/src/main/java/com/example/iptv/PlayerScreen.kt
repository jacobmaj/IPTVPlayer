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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(req: PlayRequest, epg: String?, onEnded: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as? Activity
    val prefs = remember { Prefs(ctx) }
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
        val selector = DefaultTrackSelector(ctx).also {
            it.setParameters(
                it.buildUponParameters()
                    .setForceHighestSupportedBitrate(true)          // siempre la pista de mayor calidad
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .build()
            )
        }
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // Buffer más generoso: menos cortes con conexiones inestables o streams 4K pesados
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
            .build()
        val sources = DefaultMediaSourceFactory(ctx)
            .setDataSourceFactory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .build()
            .apply {
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

    // Guarda la posición cada 30 s por si el sistema cierra la app
    LaunchedEffect(exo) {
        while (true) {
            delay(30_000)
            savePos()
        }
    }

    // Muestra el título (y la guía EPG) unos segundos
    LaunchedEffect(req.url, epg) {
        showInfo = true
        delay(6_000)
        showInfo = false
    }

    DisposableEffect(exo) {
        val handler = Handler(Looper.getMainLooper())
        var triedTs = false
        var retries = 0
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
                if (req.live && !triedTs && req.url.endsWith(".m3u8")) {
                    // Algunos servidores solo entregan Live en .ts
                    triedTs = true
                    exo.setMediaItem(MediaItem.fromUri(req.url.removeSuffix(".m3u8") + ".ts"))
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
                    Text("Ahora: $epg", color = Color.LightGray, fontSize = 13.sp)
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
