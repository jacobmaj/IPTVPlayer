package com.example.iptv

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun mb(bytes: Long) = bytes / (1024 * 1024)

@Suppress("DEPRECATION")
private fun diagnostics(ctx: Context): List<String> {
    val out = mutableListOf<String>()

    // Pantalla
    val info = bestDisplayInfo(ctx)
    out += "Pantalla: ${info.w}×${info.h}" + if (info.is4k) "  (4K)" else ""
    val display = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    if (Build.VERSION.SDK_INT >= 23) {
        val modes = display.supportedModes
            .map { "${max(it.physicalWidth, it.physicalHeight)}×${min(it.physicalWidth, it.physicalHeight)} a ${it.refreshRate.roundToInt()} Hz" }
            .distinct()
        out += "Modos de la pantalla: " + modes.joinToString(", ")
    }
    if (Build.VERSION.SDK_INT >= 24) {
        val hdr = display.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> "otro"
            }
        }.orEmpty()
        out += "HDR: " + if (hdr.isEmpty()) "no informado" else hdr.joinToString(", ")
    }

    // Memoria
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    out += "RAM del equipo: ${mb(mi.totalMem)} MB en total, ${mb(mi.availMem)} MB libres"
    out += "Límite de memoria para la app: ${mb(Runtime.getRuntime().maxMemory())} MB"

    // Decodificadores de video
    val codecs = listOf(
        "H.264" to "video/avc",
        "H.265 (HEVC)" to "video/hevc",
        "VP9" to "video/x-vnd.on2.vp9",
        "AV1" to "video/av01"
    )
    val all = runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
    for ((label, mime) in codecs) {
        val dec = all.filter { c -> !c.isEncoder && c.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        val hw = dec.filter { !isSoftwareCodec(it.name) }
        val uhd = hw.any { c ->
            runCatching {
                c.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(3840, 2160)
            }.getOrDefault(false)
        }
        out += "$label: " + when {
            dec.isEmpty() -> "no disponible"
            hw.isEmpty() -> "solo por software (puede ir lento)"
            uhd -> "por hardware, soporta 4K"
            else -> "por hardware, pero sin 4K"
        }
    }
    out += "Audio Dolby / DTS por HDMI: lo activa el reproductor solo si la TV o el receptor lo aceptan"
    return out
}

@Composable
fun DiagDialog(isTv: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember { diagnostics(ctx) }
    DialogShell(isTv, onClose) {
        Text("Diagnóstico del equipo", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        lines.forEach { Text(it, color = Color.LightGray, fontSize = 13.sp) }
        Chip("Cerrar") { onClose() }
    }
}
