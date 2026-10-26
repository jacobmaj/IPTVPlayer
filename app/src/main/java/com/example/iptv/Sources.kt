package com.example.iptv

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Section(val label: String) { LIVE("Live"), MOVIES("Películas"), SERIES("Series") }

enum class DeviceMode(val label: String) { AUTO("Automático"), PHONE("Celular"), TV("TV") }

enum class SourceType(val label: String) { XTREAM("Usuario y contraseña"), M3U("Lista M3U (URL)") }

data class Settings(
    val type: SourceType = SourceType.XTREAM,
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val m3uUrl: String = "",
    val mode: DeviceMode = DeviceMode.AUTO,
    val name: String = ""
)

data class Item(
    val id: String,
    val name: String,
    val icon: String?,
    val added: Long,          // fecha (epoch en segundos) o posición en la lista M3U
    val categoryId: String,
    val ext: String,
    val section: Section,
    val url: String = ""      // URL directa (solo listas M3U)
)

data class Category(val id: String, val name: String)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val num: Int,
    val url: String,
    val image: String? = null,
    val plot: String = "",
    val duration: String = ""
)

/** Datos de la cuenta (solo disponibles con acceso por usuario y contraseña). */
data class AccountInfo(
    val user: String,
    val status: String,
    val expires: Long?,          // epoch en segundos; null = sin vencimiento
    val maxConnections: String,
    val activeConnections: String
)

/** Ficha de una película o serie. */
data class Detail(
    val plot: String = "",
    val genre: String = "",
    val year: String = "",
    val rating: String = "",
    val duration: String = "",
    val cast: String = "",
    val director: String = "",
    val image: String? = null
)

interface IptvSource {
    suspend fun login(): AccountInfo?
    suspend fun items(sec: Section, force: Boolean = false): List<Item>
    suspend fun categories(sec: Section, force: Boolean = false): List<Category>
    suspend fun detail(item: Item): Detail
    suspend fun episodes(item: Item): List<Episode>
    fun playUrl(item: Item, liveExt: String = "m3u8"): String
    suspend fun nowPlaying(item: Item): String? = null
    suspend fun schedule(item: Item): List<String> = emptyList()
    suspend fun search(query: String, hideAdult: Boolean): List<Item>
    suspend fun prefetch() {}
}

/* ---------- Utilidades de red y caché en disco ---------- */

private val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
}

// Las listas se reutilizan 20 horas (cada madrugada se renuevan solas)
private const val CACHE_TTL_MS = 20L * 60 * 60 * 1000

/** Descarga corta (respuestas pequeñas: login, episodios, guía). */
private fun download(url: String): String =
    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error("El servidor respondió HTTP ${r.code}")
        r.body?.string() ?: error("Respuesta vacía del servidor")
    }

/**
 * Descarga listas grandes directo a un archivo, sin cargarlas completas en memoria
 * (las listas de series pueden pesar decenas de MB).
 */
private suspend fun cachedFile(cacheDir: File, url: String, force: Boolean): File =
    withContext(Dispatchers.IO) {
        val f = File(cacheDir, "src_${url.hashCode()}.txt")
        if (!force && f.exists() && f.length() > 0 &&
            System.currentTimeMillis() - f.lastModified() < CACHE_TTL_MS
        ) {
            return@withContext f
        }
        val tmp = File(cacheDir, f.name + ".tmp")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("El servidor respondió HTTP ${r.code}")
            val body = r.body ?: error("Respuesta vacía del servidor")
            body.byteStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
        }
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) error("No se pudo guardar la lista en el dispositivo")
        f
    }

/**
 * Lee un arreglo JSON grande en streaming y construye cada elemento al vuelo.
 * Reutiliza un solo mapa para todos los objetos: no se guarda nada intermedio en memoria.
 */
private fun <T : Any> parseArray(file: File, wanted: Set<String>, build: (Map<String, String>) -> T?): List<T> {
    val out = ArrayList<T>()
    val m = HashMap<String, String>(16)
    JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { r ->
        r.isLenient = true
        if (r.peek() != JsonToken.BEGIN_ARRAY) return out
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue()
                continue
            }
            m.clear()
            r.beginObject()
            while (r.hasNext()) {
                val name = r.nextName()
                val t = r.peek()
                if (name in wanted && (t == JsonToken.STRING || t == JsonToken.NUMBER)) {
                    m[name] = r.nextString()
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
            build(m)?.let { out.add(it) }
        }
        r.endArray()
    }
    return out
}

private val ITEM_KEYS = setOf(
    "series_id", "stream_id", "name", "cover", "stream_icon",
    "added", "last_modified", "category_id", "container_extension"
)
private val CAT_KEYS = setOf("category_id", "category_name")

private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun b64(raw: String): String =
    runCatching { String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(raw)

/** Duración legible: acepta segundos ("2580") o texto ("00:43:15"). */
private fun prettyDuration(secs: String?, text: String?): String {
    val s = secs?.toLongOrNull() ?: 0L
    if (s > 0) return "${(s + 30) / 60} min"
    val parts = text?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
    if (parts != null && parts.size == 3) {
        val m = parts[0] * 60 + parts[1]
        if (m > 0) return "$m min"
    }
    return ""
}

/** Si la URL M3U trae usuario y clave de Xtream (get.php?username=..&password=..) devuelve (servidor, usuario, clave). */
fun xtreamFromM3u(url: String): Triple<String, String, String>? {
    return try {
        val u = URI(url.trim())
        if (u.path?.contains("get.php") != true) return null
        val q = (u.rawQuery ?: return null).split('&').associate {
            val p = it.split('=', limit = 2)
            p[0] to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
        }
        val user = q["username"] ?: return null
        val pass = q["password"] ?: return null
        val port = if (u.port > 0) ":${u.port}" else ""
        Triple("${u.scheme}://${u.host}$port", user, pass)
    } catch (e: Exception) {
        null
    }
}

/** Última versión publicada en GitHub (etiqueta "v12" → 12) y su enlace. */
suspend fun fetchLatestRelease(repo: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
    runCatching {
        val o = Json.parseToJsonElement(download("https://api.github.com/repos/$repo/releases/latest")) as JsonObject
        val tag = o.s("tag_name") ?: return@runCatching null
        val n = tag.filter { it.isDigit() }.toIntOrNull() ?: return@runCatching null
        n to (o.s("html_url") ?: "")
    }.getOrNull()
}

/* ---------- Xtream Codes ---------- */

class XtreamApi(
    server: String,
    private val user: String,
    private val pass: String,
    private val cacheDir: File
) : IptvSource {

    private val base = server.trimEnd('/')

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(action: String? = null, extra: String = ""): String =
        "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}" +
            (if (action != null) "&action=$action" else "") + extra

    private fun itemsAction(sec: Section) = when (sec) {
        Section.LIVE -> "get_live_streams"
        Section.MOVIES -> "get_vod_streams"
        Section.SERIES -> "get_series"
    }

    private fun catsAction(sec: Section) = when (sec) {
        Section.LIVE -> "get_live_categories"
        Section.MOVIES -> "get_vod_categories"
        Section.SERIES -> "get_series_categories"
    }

    /** Para respuestas pequeñas. */
    private suspend fun json(url: String): JsonElement {
        val text = withContext(Dispatchers.IO) { download(url) }
        return withContext(Dispatchers.Default) {
            try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                error("El servidor no devolvió datos válidos (revisa la URL)")
            }
        }
    }

    override suspend fun login(): AccountInfo? {
        val info = (json(api()) as? JsonObject)?.get("user_info") as? JsonObject
            ?: error("Respuesta inválida del servidor")
        if (info.s("auth") != "1") error("Usuario o contraseña incorrectos")
        return AccountInfo(
            user = info.s("username") ?: user,
            status = info.s("status") ?: "",
            expires = info.s("exp_date")?.toLongOrNull(),
            maxConnections = info.s("max_connections") ?: "",
            activeConnections = info.s("active_cons") ?: ""
        )
    }

    override suspend fun categories(sec: Section, force: Boolean): List<Category> {
        val f = cachedFile(cacheDir, api(catsAction(sec)), force)
        return withContext(Dispatchers.Default) {
            try {
                parseArray(f, CAT_KEYS) {
                    Category(it["category_id"] ?: "", it["category_name"] ?: "Sin nombre")
                }.distinctBy { it.id }
            } catch (e: Exception) {
                f.delete()
                error("El servidor no devolvió datos válidos (revisa la URL)")
            }
        }
    }

    private fun toItem(o: Map<String, String>, sec: Section): Item? {
        val id = (if (sec == Section.SERIES) o["series_id"] else o["stream_id"]) ?: return null
        return Item(
            id = id,
            name = o["name"] ?: "Sin nombre",
            icon = (if (sec == Section.SERIES) o["cover"] else o["stream_icon"])?.takeIf { it.isNotBlank() },
            added = (o["added"] ?: o["last_modified"])?.toLongOrNull() ?: 0L,
            categoryId = (o["category_id"] ?: "").intern(),   // textos repetidos: se comparte una sola copia
            ext = (o["container_extension"] ?: "mp4").intern(),
            section = sec
        )
    }

    override suspend fun items(sec: Section, force: Boolean): List<Item> {
        val f = cachedFile(cacheDir, api(itemsAction(sec)), force)
        return withContext(Dispatchers.Default) {
            try {
                parseArray(f, ITEM_KEYS) { toItem(it, sec) }
                    .distinctBy { it.id }   // algunos paneles repiten ids y eso rompía las listas
            } catch (e: Exception) {
                f.delete()
                error("El servidor no devolvió datos válidos (revisa la URL)")
            }
        }
    }

    /** Busca en los tres apartados leyendo los archivos guardados, sin cargar las listas en memoria. */
    override suspend fun search(query: String, hideAdult: Boolean): List<Item> {
        val out = ArrayList<Item>()
        for (sec in Section.values()) {
            val adult = if (hideAdult) {
                categories(sec, false).filter { isAdult(it.name) }.map { it.id }.toSet()
            } else {
                emptySet()
            }
            val f = cachedFile(cacheDir, api(itemsAction(sec)), false)
            val found = withContext(Dispatchers.Default) {
                parseArray(f, ITEM_KEYS) { o ->
                    val name = o["name"] ?: return@parseArray null
                    if (!name.contains(query, ignoreCase = true)) return@parseArray null
                    if ((o["category_id"] ?: "") in adult) return@parseArray null
                    toItem(o, sec)
                }.distinctBy { it.id }.take(60)
            }
            out.addAll(found)
        }
        return out
    }

    /** Descarga las listas sin analizarlas (para la actualización nocturna). */
    override suspend fun prefetch() {
        for (sec in Section.values()) {
            cachedFile(cacheDir, api(itemsAction(sec)), true)
            cachedFile(cacheDir, api(catsAction(sec)), true)
        }
    }

    private var lastSeries: Pair<String, JsonObject>? = null

    // La ficha y los episodios salen de la misma consulta: se guarda para no pedirla dos veces
    private suspend fun seriesInfo(id: String): JsonObject? {
        lastSeries?.let { if (it.first == id) return it.second }
        val o = json(api("get_series_info", "&series_id=$id")) as? JsonObject ?: return null
        lastSeries = id to o
        return o
    }

    override suspend fun detail(item: Item): Detail {
        val i: JsonObject? = when (item.section) {
            Section.MOVIES -> (json(api("get_vod_info", "&vod_id=${item.id}")) as? JsonObject)?.get("info") as? JsonObject
            Section.SERIES -> seriesInfo(item.id)?.get("info") as? JsonObject
            else -> null
        }
        if (i == null) return Detail()
        return Detail(
            plot = i.s("plot") ?: i.s("description") ?: "",
            genre = i.s("genre") ?: "",
            year = (i.s("releasedate") ?: i.s("release_date") ?: i.s("releaseDate") ?: "").take(4),
            rating = i.s("rating") ?: "",
            duration = i.s("duration")?.takeIf { it.isNotBlank() && it != "0" }
                ?: i.s("episode_run_time")?.takeIf { it.isNotBlank() && it != "0" }?.let { "$it min" } ?: "",
            cast = i.s("cast") ?: i.s("actors") ?: "",
            director = i.s("director") ?: "",
            image = i.s("movie_image") ?: i.s("cover_big") ?: i.s("cover")
        )
    }

    override suspend fun episodes(item: Item): List<Episode> {
        val o = seriesInfo(item.id) ?: return emptyList()
        val eps = o["episodes"]
        // Según el servidor, "episodes" viene como objeto {"1":[...]} o como arreglo de arreglos
        val groups: List<JsonElement> = when (eps) {
            is JsonObject -> eps.values.toList()
            is JsonArray -> eps.toList()
            else -> emptyList()
        }
        val out = mutableListOf<Episode>()
        for (g in groups) {
            (g as? JsonArray)?.forEach { e ->
                val eo = e as? JsonObject ?: return@forEach
                val id = eo.s("id") ?: return@forEach
                val ext = eo.s("container_extension") ?: "mp4"
                val inf = eo["info"] as? JsonObject
                out += Episode(
                    id = id,
                    title = eo.s("title") ?: "Episodio",
                    season = eo.s("season")?.toIntOrNull() ?: 1,
                    num = eo.s("episode_num")?.toIntOrNull() ?: 0,
                    url = "$base/series/$user/$pass/$id.$ext",
                    image = (inf?.s("movie_image") ?: inf?.s("cover_big"))?.takeIf { it.isNotBlank() },
                    plot = inf?.s("plot") ?: "",
                    duration = prettyDuration(inf?.s("duration_secs"), inf?.s("duration"))
                )
            }
        }
        return out.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.num }))
    }

    override fun playUrl(item: Item, liveExt: String): String = when (item.section) {
        Section.LIVE -> "$base/live/$user/$pass/${item.id}.$liveExt"
        else -> "$base/movie/$user/$pass/${item.id}.${item.ext}"
    }

    /** Programa actual y los dos siguientes (si el proveedor incluye guía). */
    override suspend fun nowPlaying(item: Item): String? {
        if (item.section != Section.LIVE) return null
        val o = json(api("get_short_epg", "&stream_id=${item.id}&limit=3")) as? JsonObject ?: return null
        val list = o["epg_listings"] as? JsonArray ?: return null
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val lines = list.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val raw = e.s("title") ?: return@mapNotNull null
            val title = b64(raw)
            val ts = e.s("start_timestamp")?.toLongOrNull()
            if (ts != null) "${fmt.format(Date(ts * 1000))}  $title" else title
        }
        return lines.joinToString("\n").ifBlank { null }
    }

    /** Programación completa del canal (lo que queda del día). */
    override suspend fun schedule(item: Item): List<String> {
        if (item.section != Section.LIVE) return emptyList()
        val o = json(api("get_simple_data_table", "&stream_id=${item.id}")) as? JsonObject ?: return emptyList()
        val list = o["epg_listings"] as? JsonArray ?: return emptyList()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = System.currentTimeMillis() / 1000
        val lines = ArrayList<String>()
        for (el in list) {
            val e = el as? JsonObject ?: continue
            val raw = e.s("title") ?: continue
            val start = e.s("start_timestamp")?.toLongOrNull() ?: continue
            val stop = e.s("stop_timestamp")?.toLongOrNull() ?: continue
            if (stop <= now) continue
            val prefix = if (start <= now) "AHORA   " else ""
            lines.add(prefix + fmt.format(Date(start * 1000)) + "   " + b64(raw))
            if (lines.size >= 16) break
        }
        return lines
    }
}

/* ---------- Lista M3U / M3U8 ---------- */

class M3uSource(private val url: String, private val cacheDir: File) : IptvSource {

    private class Parsed(
        val items: Map<Section, List<Item>>,
        val cats: Map<Section, List<Category>>,
        val episodes: Map<String, List<Episode>>
    )

    private var parsed: Parsed? = null

    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val epRegex = Regex("^(.*?)[\\s._\\-]*[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})")
    private val videoFile = Regex("\\.(mp4|mkv|avi|mov)(\\?.*)?$")

    private suspend fun ensure(force: Boolean): Parsed {
        val p = parsed
        if (p != null && !force) return p
        val f = cachedFile(cacheDir, url, force)
        val np = withContext(Dispatchers.Default) { f.useLines { parse(it) } }
        parsed = np
        return np
    }

    override suspend fun login(): AccountInfo? {
        val p = ensure(false)
        if (p.items.values.all { it.isEmpty() }) error("La lista M3U no contiene canales")
        return null
    }

    override suspend fun detail(item: Item): Detail = Detail()

    override suspend fun items(sec: Section, force: Boolean): List<Item> = ensure(force).items[sec] ?: emptyList()

    // Se usa siempre después de items(), así que ya está analizada
    override suspend fun categories(sec: Section, force: Boolean): List<Category> =
        ensure(false).cats[sec] ?: emptyList()

    override suspend fun episodes(item: Item): List<Episode> =
        (ensure(false).episodes[item.id] ?: emptyList()).sortedWith(compareBy({ it.season }, { it.num }))

    override fun playUrl(item: Item, liveExt: String): String = item.url

    override suspend fun search(query: String, hideAdult: Boolean): List<Item> {
        val p = ensure(false)
        val out = ArrayList<Item>()
        for (sec in Section.values()) {
            val adult = if (hideAdult) {
                (p.cats[sec] ?: emptyList()).filter { isAdult(it.name) }.map { it.id }.toSet()
            } else {
                emptySet()
            }
            out.addAll(
                (p.items[sec] ?: emptyList())
                    .filter { it.name.contains(query, ignoreCase = true) && it.categoryId !in adult }
                    .take(60)
            )
        }
        return out
    }

    override suspend fun prefetch() {
        cachedFile(cacheDir, url, true)
    }

    private fun parse(lines: Sequence<String>): Parsed {
        val items = Section.values().associateWith { mutableListOf<Item>() }
        val cats = Section.values().associateWith { linkedSetOf<String>() }
        val seriesMap = linkedMapOf<String, Item>()
        val episodes = hashMapOf<String, MutableList<Episode>>()
        val seen = HashSet<String>()

        var name = ""
        var logo: String? = null
        var group = "Sin categoría"
        var idx = 0L

        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                name = line.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"] ?: "Sin nombre" }
                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                group = attrs["group-title"]?.trim().orEmpty().ifBlank { "Sin categoría" }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                if (!seen.add(line)) continue      // evita duplicados exactos
                idx++
                val lower = line.lowercase()
                val g = group.lowercase()
                val ep = epRegex.find(name)
                val looksSeries = lower.contains("/series/") || g.contains("serie")
                val looksMovie = lower.contains("/movie/") || g.contains("pelicula") || g.contains("película") ||
                    g.contains("movie") || g.contains("vod") || g.contains("cine") || videoFile.containsMatchIn(lower)
                val section = when {
                    ep != null && (looksSeries || looksMovie) -> Section.SERIES
                    looksSeries || looksMovie -> Section.MOVIES
                    else -> Section.LIVE
                }
                if (section == Section.SERIES) {
                    val e = ep!!
                    val title = e.groupValues[1].trim().ifBlank { name }
                    val key = title.lowercase()
                    val sid = "s${key.hashCode()}"
                    val prev = seriesMap[key]
                    seriesMap[key] = (prev ?: Item(sid, title, logo, idx, group, "", Section.SERIES))
                        .copy(added = idx, icon = prev?.icon ?: logo)
                    if (prev == null) cats[Section.SERIES]!!.add(group)
                    episodes.getOrPut(sid) { mutableListOf() }.add(
                        Episode("${sid}_${line.hashCode()}", name, e.groupValues[2].toInt(), e.groupValues[3].toInt(), line)
                    )
                } else {
                    items[section]!!.add(Item("m${line.hashCode()}", name.ifBlank { "Sin nombre" }, logo, idx, group, "", section, line))
                    cats[section]!!.add(group)
                }
                name = ""
                logo = null
            }
        }
        items[Section.SERIES]!!.addAll(seriesMap.values)
        return Parsed(items, cats.mapValues { en -> en.value.map { Category(it, it) } }, episodes)
    }
}
