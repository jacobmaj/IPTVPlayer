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
    val mode: DeviceMode = DeviceMode.AUTO
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

data class Episode(val id: String, val title: String, val season: Int, val num: Int, val url: String)

interface IptvSource {
    suspend fun login()
    suspend fun items(sec: Section, force: Boolean = false): List<Item>
    suspend fun categories(sec: Section, force: Boolean = false): List<Category>
    suspend fun episodes(item: Item): List<Episode>
    fun playUrl(item: Item): String
    suspend fun nowPlaying(item: Item): String? = null
}

/* ---------- Utilidades de red y caché en disco ---------- */

private val http: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
}

private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000   // las listas se reutilizan 6 horas

/** Descarga corta (respuestas pequeñas: login, episodios, guía). */
private fun download(url: String): String =
    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error("El servidor respondió HTTP ${r.code}")
        r.body?.string() ?: error("Respuesta vacía del servidor")
    }

/**
 * Descarga listas grandes directo a un archivo, sin cargarlas completas en memoria
 * (las listas de series pueden pesar decenas de MB y cerraban la app por falta de memoria).
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
 * Lee un arreglo JSON grande en streaming, guardando solo los campos pedidos de cada objeto.
 * Así no se construye todo el árbol JSON en memoria.
 */
private fun parseArray(file: File, wanted: Set<String>): List<Map<String, String>> {
    val out = ArrayList<Map<String, String>>()
    JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { r ->
        r.isLenient = true
        if (r.peek() != JsonToken.BEGIN_ARRAY) return out
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) {
                r.skipValue()
                continue
            }
            val m = HashMap<String, String>(8)
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
            out.add(m)
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

    override suspend fun login() {
        val info = (json(api()) as? JsonObject)?.get("user_info") as? JsonObject
            ?: error("Respuesta inválida del servidor")
        if (info.s("auth") != "1") error("Usuario o contraseña incorrectos")
    }

    override suspend fun categories(sec: Section, force: Boolean): List<Category> {
        val action = when (sec) {
            Section.LIVE -> "get_live_categories"
            Section.MOVIES -> "get_vod_categories"
            Section.SERIES -> "get_series_categories"
        }
        val f = cachedFile(cacheDir, api(action), force)
        return withContext(Dispatchers.Default) {
            try {
                parseArray(f, CAT_KEYS).map {
                    Category(it["category_id"] ?: "", it["category_name"] ?: "Sin nombre")
                }.distinctBy { it.id }
            } catch (e: Exception) {
                f.delete()
                error("El servidor no devolvió datos válidos (revisa la URL)")
            }
        }
    }

    override suspend fun items(sec: Section, force: Boolean): List<Item> {
        val action = when (sec) {
            Section.LIVE -> "get_live_streams"
            Section.MOVIES -> "get_vod_streams"
            Section.SERIES -> "get_series"
        }
        val f = cachedFile(cacheDir, api(action), force)
        return withContext(Dispatchers.Default) {
            try {
                parseArray(f, ITEM_KEYS).mapNotNull { o ->
                    val id = (if (sec == Section.SERIES) o["series_id"] else o["stream_id"])
                        ?: return@mapNotNull null
                    Item(
                        id = id,
                        name = o["name"] ?: "Sin nombre",
                        icon = (if (sec == Section.SERIES) o["cover"] else o["stream_icon"])
                            ?.takeIf { it.isNotBlank() },
                        added = (o["added"] ?: o["last_modified"])?.toLongOrNull() ?: 0L,
                        categoryId = o["category_id"] ?: "",
                        ext = o["container_extension"] ?: "mp4",
                        section = sec
                    )
                }.distinctBy { it.id }   // algunos paneles repiten ids y eso rompía las listas
            } catch (e: Exception) {
                f.delete()
                error("El servidor no devolvió datos válidos (revisa la URL)")
            }
        }
    }

    override suspend fun episodes(item: Item): List<Episode> {
        val o = json(api("get_series_info", "&series_id=${item.id}")) as? JsonObject ?: return emptyList()
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
                out += Episode(
                    id = id,
                    title = eo.s("title") ?: "Episodio",
                    season = eo.s("season")?.toIntOrNull() ?: 1,
                    num = eo.s("episode_num")?.toIntOrNull() ?: 0,
                    url = "$base/series/$user/$pass/$id.$ext"
                )
            }
        }
        return out.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.num }))
    }

    override fun playUrl(item: Item): String = when (item.section) {
        Section.LIVE -> "$base/live/$user/$pass/${item.id}.m3u8"
        else -> "$base/movie/$user/$pass/${item.id}.${item.ext}"
    }

    /** Programa que se está emitiendo (guía EPG), si el proveedor la incluye. */
    override suspend fun nowPlaying(item: Item): String? {
        if (item.section != Section.LIVE) return null
        val o = json(api("get_short_epg", "&stream_id=${item.id}&limit=1")) as? JsonObject ?: return null
        val first = (o["epg_listings"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val raw = first.s("title") ?: return null
        return runCatching { String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(raw)
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

    override suspend fun login() {
        val p = ensure(false)
        if (p.items.values.all { it.isEmpty() }) error("La lista M3U no contiene canales")
    }

    override suspend fun items(sec: Section, force: Boolean): List<Item> = ensure(force).items[sec] ?: emptyList()

    // Se usa siempre después de items(), así que ya está analizada
    override suspend fun categories(sec: Section, force: Boolean): List<Category> =
        ensure(false).cats[sec] ?: emptyList()

    override suspend fun episodes(item: Item): List<Episode> =
        (ensure(false).episodes[item.id] ?: emptyList()).sortedWith(compareBy({ it.season }, { it.num }))

    override fun playUrl(item: Item): String = item.url

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
