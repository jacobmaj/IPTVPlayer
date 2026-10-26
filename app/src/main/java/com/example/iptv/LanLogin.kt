package com.example.iptv

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Mini servidor que vive solo mientras el cuadro del QR está abierto: el celular abre una página
 * con un formulario y manda los datos a la TV por la red local. Exige un código al azar en el enlace.
 */
class LanServer(private val token: String, private val onForm: (Map<String, String>) -> Unit) {
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopped = false

    fun start(port: Int): Boolean = try {
        if (stopped) {
            false
        } else {
            val s = ServerSocket(port)
            if (stopped) {
                s.close()
                false
            } else {
                server = s
                running = true
                Thread { loop(s) }.apply { isDaemon = true }.start()
                true
            }
        }
    } catch (e: Exception) {
        false
    }

    fun stop() {
        stopped = true
        running = false
        runCatching { server?.close() }
    }

    private fun loop(s: ServerSocket) {
        while (running) {
            val c = try {
                s.accept()
            } catch (e: Exception) {
                break
            }
            try {
                handle(c)
            } catch (e: Exception) {
                // se ignora: una petición mala no debe tumbar el servidor
            } finally {
                runCatching { c.close() }
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun handle(c: Socket) {
        c.soTimeout = 5000
        val input = BufferedInputStream(c.getInputStream())
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) {
                length = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val parts = requestLine.split(' ')
        val method = parts[0]
        val path = parts.getOrElse(1) { "/" }
        val out = c.getOutputStream()

        if (method == "POST") {
            val size = length.coerceIn(0, 8192)
            val body = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = input.read(body, off, size - off)
                if (n < 0) break
                off += n
            }
            val form = parseForm(String(body, 0, off, Charsets.UTF_8))
            if (form["t"] == token) {
                respond(out, "200 OK", page("<h2>Listo</h2><p>Ya puedes mirar la TV: se está conectando.</p>"))
                onForm(form)
            } else {
                respond(out, "403 Forbidden", page("<p>Código incorrecto. Escanea de nuevo el QR de la TV.</p>"))
            }
        } else if (path.contains("t=$token")) {
            respond(out, "200 OK", page(formHtml()))
        } else {
            respond(out, "403 Forbidden", page("<p>Enlace inválido. Escanea de nuevo el QR de la TV.</p>"))
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()

    private fun respond(out: java.io.OutputStream, status: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun page(inner: String) =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>IPTV Player</title><style>body{font-family:sans-serif;background:#0e1116;color:#fff;padding:20px;max-width:480px;margin:auto}" +
            "input,select{width:100%;padding:12px;margin:6px 0 14px;font-size:16px;border-radius:8px;border:0;box-sizing:border-box}" +
            "button{width:100%;padding:14px;font-size:18px;background:#1e88e5;color:#fff;border:0;border-radius:8px}</style>" +
            "</head><body>$inner</body></html>"

    private fun formHtml() =
        "<h2>IPTV Player</h2><p>Escribe los datos de tu servicio:</p>" +
            "<form method=\"post\" action=\"/\"><input type=\"hidden\" name=\"t\" value=\"$token\">" +
            "<label>Tipo</label><select name=\"type\"><option value=\"xtream\">Usuario y contraseña</option>" +
            "<option value=\"m3u\">Lista M3U (URL)</option></select>" +
            "<label>Servidor (http://host:puerto)</label><input name=\"server\" autocapitalize=\"off\">" +
            "<label>Usuario</label><input name=\"user\" autocapitalize=\"off\">" +
            "<label>Contraseña</label><input name=\"pass\" type=\"password\">" +
            "<label>URL de la lista M3U (solo si elegiste M3U)</label><input name=\"m3u\" autocapitalize=\"off\">" +
            "<label>Nombre (opcional)</label><input name=\"name\">" +
            "<button type=\"submit\">Enviar a la TV</button></form>"
}

fun localIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

@Composable
fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(modifier.background(Color.White)) {
        val n = matrix.width
        val cell = size.width / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

@Composable
fun QrLoginDialog(vm: AppVM, onClose: () -> Unit) {
    val token = remember { (100000..999999).random().toString() }
    val ip = remember { localIp() }
    val port = 8765
    var started by remember { mutableStateOf(false) }
    val server = remember {
        LanServer(token) { form ->
            val s = Settings(
                type = if (form["type"] == "m3u") SourceType.M3U else SourceType.XTREAM,
                server = form["server"].orEmpty().trim(),
                user = form["user"].orEmpty().trim(),
                pass = form["pass"].orEmpty(),
                m3uUrl = form["m3u"].orEmpty().trim(),
                mode = vm.saved.mode,
                name = form["name"].orEmpty().trim()
            )
            // El servidor corre en otro hilo: se vuelve al hilo principal para entrar
            Handler(Looper.getMainLooper()).post {
                vm.login(s)
                onClose()
            }
        }
    }
    DisposableEffect(server) {
        Thread {
            val ok = server.start(port)
            Handler(Looper.getMainLooper()).post { started = ok }
        }.start()
        onDispose { server.stop() }
    }

    DialogShell(true, onClose) {
        Text("Entrar con el celular", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (ip == null) {
            Text("No se encontró la red (Wi-Fi o cable). Conecta la TV a la misma red que tu celular.", color = Color(0xFFFF8A80))
        } else if (!started) {
            Text("No se pudo abrir el puerto $port. Cierra este cuadro e inténtalo otra vez.", color = Color(0xFFFF8A80))
        } else {
            val url = "http://$ip:$port/?t=$token"
            Text("1. Conecta tu celular a la misma red Wi-Fi que la TV.", color = Color.LightGray, fontSize = 13.sp)
            Text("2. Escanea este código con la cámara del celular.", color = Color.LightGray, fontSize = 13.sp)
            Text("3. Escribe tus datos en la página y pulsa Enviar.", color = Color.LightGray, fontSize = 13.sp)
            QrCode(url, Modifier.size(220.dp).align(Alignment.CenterHorizontally))
            Text(url, color = Color.Gray, fontSize = 12.sp)
        }
        Chip("Cerrar") { onClose() }
    }
}
