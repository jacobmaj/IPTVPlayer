package com.majano.iptv

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Muchos paneles IPTV bloquean user-agents desconocidos; este es el más aceptado. */
const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

class HttpError(val code: Int) : IOException("HTTP $code")

object Net {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun <T> stream(url: String, block: (InputStream) -> T): T {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code)
            val body = resp.body ?: throw IOException("Respuesta vacía del servidor")
            block(body.byteStream())
        }
    }

    fun getText(url: String): String = stream(url) { it.bufferedReader(Charsets.UTF_8).readText() }

    fun describe(e: Throwable): String = when (e) {
        is HttpError -> when (e.code) {
            401, 403 -> "El servidor rechazó el acceso (HTTP ${e.code}). Revisa usuario y contraseña; algunos proveedores también bloquean ciertas apps o IPs."
            404 -> "El servidor no encontró el servicio (HTTP 404). Revisa que el link sea el del servidor, por ejemplo http://servidor.com:8080"
            else -> "El servidor respondió con error HTTP ${e.code}."
        }
        is UnknownHostException -> "No se encontró el servidor. Revisa que el link esté bien escrito y que tengas internet."
        is SocketTimeoutException -> "El servidor tardó demasiado en responder."
        is SSLException -> "Error de certificado HTTPS. Prueba escribiendo el link con http:// en lugar de https://."
        is ConnectException -> "No se pudo abrir la conexión (puerto cerrado o bloqueado). Revisa el puerto del link."
        is IllegalArgumentException -> "El link no es válido. Ejemplo: http://servidor.com:8080"
        else -> "No se pudo conectar: ${e.message ?: e.javaClass.simpleName}"
    }
}

class ParsedLink(val base: String, val user: String?, val pass: String?, val directM3u: String?)

/**
 * Acepta: servidor simple (http://host:puerto), con o sin http://, o un link completo de
 * get.php?username=..&password=.. / lista .m3u. Extrae el servidor base y, si vienen, usuario y contraseña.
 */
fun parseLink(raw: String): ParsedLink? {
    var s = raw.trim().trim('"', '\'', ' ')
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "http://$s"
    val uri = try { Uri.parse(s) } catch (e: Exception) { return null }
    val host = uri.host
    if (host.isNullOrBlank()) return null
    val scheme = (uri.scheme ?: "http").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    var path = uri.path.orEmpty()
    val last = path.substringAfterLast('/')
    val isM3u = last.endsWith(".m3u", true) || last.endsWith(".m3u8", true)
    val isScript = last.endsWith(".php", true) || isM3u
    if (isScript) path = path.substringBeforeLast('/')
    path = path.trimEnd('/')
    return ParsedLink(
        base = "$scheme://$host$port$path",
        user = uri.getQueryParameter("username"),
        pass = uri.getQueryParameter("password"),
        directM3u = if (isM3u) s else null,
    )
}

sealed interface ConnectResult {
    class Ok(val session: Session, val info: String, val playlist: List<Entry>?) : ConnectResult
    class Fail(val reason: String) : ConnectResult
}

private fun enc(s: String): String = Uri.encode(s)

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

object Provider {

    fun connect(rawLink: String, rawUser: String, rawPass: String): ConnectResult {
        val parsed = parseLink(rawLink)
            ?: return ConnectResult.Fail("El link no es válido. Ejemplo: http://servidor.com:8080")
        val user = rawUser.trim().ifBlank { parsed.user.orEmpty() }
        val pass = rawPass.trim().ifBlank { parsed.pass.orEmpty() }

        if (user.isBlank() || pass.isBlank()) {
            val direct = parsed.directM3u
                ?: return ConnectResult.Fail("Escribe el usuario y la contraseña.")
            return loadM3u(parsed.base, direct, user, pass)
        }

        // 1) Intento Xtream Codes (lo normal)
        try {
            val probe = Session(parsed.base, user, pass, "m3u8", true, "")
            val root = JSONObject(Net.getText(apiUrl(probe, null)))
            val info = root.optJSONObject("user_info")
            if (info != null) {
                if (info.optInt("auth", 0) != 1) {
                    return ConnectResult.Fail("Usuario o contraseña incorrectos (el servidor rechazó el acceso).")
                }
                val status = info.str("status")
                if (status.isNotBlank() && !status.equals("Active", true)) {
                    return ConnectResult.Fail("La cuenta no está activa (estado: $status).")
                }
                val formats = info.optJSONArray("allowed_output_formats")
                var hasM3u8 = formats == null
                var hasTs = false
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.optString(i)
                        if (f.equals("m3u8", true)) hasM3u8 = true
                        if (f.equals("ts", true)) hasTs = true
                    }
                }
                val ext = if (hasM3u8 || !hasTs) "m3u8" else "ts"
                val parts = ArrayList<String>()
                val exp = info.optLong("exp_date", 0L)
                if (exp > 0) parts.add("Vence: " + SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(exp * 1000)))
                val max = info.str("max_connections")
                if (max.isNotBlank()) parts.add("Conexiones permitidas: $max")
                return ConnectResult.Ok(
                    Session(parsed.base, user, pass, ext, true, ""),
                    parts.joinToString(" · ").ifBlank { "Cuenta activa" },
                    null,
                )
            }
            // JSON válido pero sin user_info: probablemente no es Xtream, probamos M3U
        } catch (e: HttpError) {
            if (e.code == 401 || e.code == 403) return ConnectResult.Fail(Net.describe(e))
        } catch (e: JSONException) {
            // La respuesta no es JSON: probamos M3U
        } catch (e: IOException) {
            return ConnectResult.Fail(Net.describe(e))
        } catch (e: IllegalArgumentException) {
            return ConnectResult.Fail(Net.describe(e))
        }

        // 2) Alternativa: lista M3U del mismo servidor
        val url = parsed.directM3u
            ?: "${parsed.base}/get.php?username=${enc(user)}&password=${enc(pass)}&type=m3u_plus&output=ts"
        return loadM3u(parsed.base, url, user, pass)
    }

    private fun loadM3u(base: String, url: String, user: String, pass: String): ConnectResult {
        return try {
            val list = Net.stream(url) { M3u.parse(it) }
            if (list.isEmpty()) {
                ConnectResult.Fail("El servidor respondió, pero no parece un servicio IPTV válido. Revisa que el link sea el del servidor (ej. http://servidor.com:8080) y tus datos.")
            } else {
                ConnectResult.Ok(
                    Session(base, user, pass, "m3u8", false, url),
                    "Lista M3U · ${list.size} elementos",
                    list,
                )
            }
        } catch (e: Exception) {
            ConnectResult.Fail(Net.describe(e))
        }
    }

    private fun apiUrl(s: Session, action: String?, extra: String = ""): String {
        val q = "username=${enc(s.user)}&password=${enc(s.pass)}"
        return "${s.base}/player_api.php?$q" + (if (action != null) "&action=$action" else "") + extra
    }

    fun categories(s: Session, kind: Kind): List<Category> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
        }
        val out = ArrayList<Category>()
        Net.stream(apiUrl(s, action)) { input ->
            readObjects(input) { m ->
                val id = m["category_id"].orEmpty()
                if (id.isNotBlank()) out.add(Category(id, m["category_name"].orEmpty().ifBlank { "Categoría $id" }))
            }
        }
        return out
    }

    fun entries(s: Session, kind: Kind): List<Entry> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            Kind.SERIES -> "get_series"
        }
        val u = enc(s.user)
        val p = enc(s.pass)
        val out = ArrayList<Entry>(4096)
        Net.stream(apiUrl(s, action)) { input ->
            readObjects(input) { m ->
                val e = toEntry(s, kind, m, u, p)
                if (e != null) out.add(e)
            }
        }
        return out
    }

    private fun toEntry(s: Session, kind: Kind, m: Map<String, String>, u: String, p: String): Entry? {
        val cat = m["category_id"].orEmpty()
        val name = m["name"].orEmpty().trim()
        return when (kind) {
            Kind.LIVE -> {
                val id = m["stream_id"].orEmpty()
                if (id.isBlank()) null
                else Entry(id, name.ifBlank { "Canal $id" }, cat, m["stream_icon"].orEmpty(), "${s.base}/live/$u/$p/$id.${s.liveExt}", Kind.LIVE)
            }
            Kind.MOVIE -> {
                val id = m["stream_id"].orEmpty()
                val ext = m["container_extension"].orEmpty().ifBlank { "mp4" }
                if (id.isBlank()) null
                else Entry(id, name.ifBlank { "Película $id" }, cat, m["stream_icon"].orEmpty(), "${s.base}/movie/$u/$p/$id.$ext", Kind.MOVIE)
            }
            Kind.SERIES -> {
                val id = m["series_id"].orEmpty()
                if (id.isBlank()) null
                else Entry(id, name.ifBlank { "Serie $id" }, cat, m["cover"].orEmpty(), "", Kind.SERIES)
            }
        }
    }

    fun episodes(s: Session, seriesId: String): List<Episode> {
        val text = Net.getText(apiUrl(s, "get_series_info", "&series_id=${enc(seriesId)}"))
        val root = JSONObject(text)
        val eps = root.opt("episodes")
        val u = enc(s.user)
        val p = enc(s.pass)
        val out = ArrayList<Episode>()

        fun addList(arr: JSONArray, seasonHint: Int) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("id")
                if (id.isBlank()) continue
                val ext = o.str("container_extension").ifBlank { "mp4" }
                val season = o.optInt("season", seasonHint)
                val num = o.optInt("episode_num", i + 1)
                val title = o.str("title").ifBlank { "Episodio $num" }
                out.add(Episode(id, season, num, title, "${s.base}/series/$u/$p/$id.$ext"))
            }
        }

        if (eps is JSONObject) {
            val keys = eps.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = eps.optJSONArray(k)
                if (arr != null) addList(arr, k.toIntOrNull() ?: 1)
            }
        } else if (eps is JSONArray) {
            for (i in 0 until eps.length()) {
                val arr = eps.optJSONArray(i)
                if (arr != null) addList(arr, i + 1)
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number }))
    }
}

private fun JsonReader.scalar(): String = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.NULL -> { nextNull(); "" }
    else -> { skipValue(); "" }
}

/** Lee un array (o un objeto de objetos) JSON grande en streaming, sin cargar todo en memoria. */
private fun readObjects(input: InputStream, each: (Map<String, String>) -> Unit) {
    JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
        fun readOne() {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return }
            val m = HashMap<String, String>(16)
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                m[name] = r.scalar()
            }
            r.endObject()
            each(m)
        }
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) readOne()
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) { r.nextName(); readOne() }
                r.endObject()
            }
            else -> r.skipValue()
        }
    }
}

object M3u {
    private val ATTR = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    private fun lastCommaOutsideQuotes(line: String): Int {
        var inQuote = false
        var idx = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) idx = i
        }
        return idx
    }

    fun parse(input: InputStream): List<Entry> {
        val out = ArrayList<Entry>(4096)
        var name = ""
        var logo = ""
        var group = ""
        input.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val attrs = HashMap<String, String>()
                for (m in ATTR.findAll(line)) attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
                group = attrs["group-title"].orEmpty()
                logo = attrs["tvg-logo"].orEmpty()
                val comma = lastCommaOutsideQuotes(line)
                val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                name = title.ifBlank { attrs["tvg-name"].orEmpty() }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                val kind = when {
                    line.contains("/movie/") -> Kind.MOVIE
                    line.contains("/series/") -> Kind.SERIES
                    else -> Kind.LIVE
                }
                val finalName = name.ifBlank { "Canal ${out.size + 1}" }
                val finalGroup = group.ifBlank { "Sin categoría" }
                out.add(Entry("$finalName|$finalGroup", finalName, finalGroup, logo, line, kind))
                name = ""; logo = ""; group = ""
            }
        }
        return out
    }
}
