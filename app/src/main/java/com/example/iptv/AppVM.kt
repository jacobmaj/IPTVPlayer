package com.example.iptv

import android.app.Application
import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

const val FAV = "__fav__"   // "categoría" especial de favoritos

private val ADULT = Regex("(?i)(adult|xxx|\\+18|18\\+|porn|erot|sexo|\\bhot\\b)")

/** Categorías que el control parental oculta mientras no se ponga el PIN. */
fun isAdult(name: String): Boolean = ADULT.containsMatchIn(name)

enum class SortMode(val label: String) {
    NAME_ASC("A → Z"),
    NAME_DESC("Z → A"),
    DATE_DESC("Más recientes"),
    DATE_ASC("Más antiguos")
}

enum class Decoder(val label: String) {
    HARDWARE("Hardware (recomendado)"),
    AUTO("Automático")
}

enum class BufferSize(val label: String, val mb: Int, val minMs: Int, val maxMs: Int) {
    SMALL("Pequeño", 32, 10_000, 20_000),
    NORMAL("Normal", 64, 15_000, 30_000),
    LARGE("Grande", 128, 25_000, 50_000)
}

enum class LiveFormat(val label: String, val ext: String) {
    HLS(".m3u8 (HLS)", "m3u8"),
    TS(".ts", "ts")
}

data class PlayerOpts(
    val decoder: Decoder = Decoder.HARDWARE,
    val tunnel: Boolean = false,
    val buffer: BufferSize = BufferSize.NORMAL,
    val live: LiveFormat = LiveFormat.HLS
)

data class PlayRequest(val title: String, val url: String, val live: Boolean)

fun profileLabel(s: Settings): String = s.name.ifBlank {
    if (s.type == SourceType.M3U) {
        "M3U · " + s.m3uUrl.substringAfter("://").substringBefore('/').take(24)
    } else {
        s.user + " · " + s.server.substringAfter("://").substringBefore('/').take(24)
    }
}

fun profileKey(s: Settings): String =
    if (s.type == SourceType.M3U) "m3u|${s.m3uUrl.trim()}" else "x|${s.server.trim().trimEnd('/')}|${s.user.trim()}"

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val pos = ctx.getSharedPreferences("iptv_pos", Context.MODE_PRIVATE)

    fun load() = Settings(
        type = runCatching { SourceType.valueOf(sp.getString("type", "XTREAM") ?: "XTREAM") }
            .getOrDefault(SourceType.XTREAM),
        server = sp.getString("server", "") ?: "",
        user = sp.getString("user", "") ?: "",
        pass = sp.getString("pass", "") ?: "",
        m3uUrl = sp.getString("m3u", "") ?: "",
        mode = runCatching { DeviceMode.valueOf(sp.getString("mode", "AUTO") ?: "AUTO") }
            .getOrDefault(DeviceMode.AUTO),
        name = sp.getString("name", "") ?: ""
    )

    fun save(s: Settings) {
        sp.edit()
            .putString("type", s.type.name)
            .putString("server", s.server)
            .putString("user", s.user)
            .putString("pass", s.pass)
            .putString("m3u", s.m3uUrl)
            .putString("mode", s.mode.name)
            .putString("name", s.name)
            .apply()
    }

    /* ----- usuarios guardados ----- */

    fun profiles(): List<Settings> {
        val raw = sp.getString("profiles", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Settings(
                    type = runCatching { SourceType.valueOf(o.optString("type", "XTREAM")) }
                        .getOrDefault(SourceType.XTREAM),
                    server = o.optString("server"),
                    user = o.optString("user"),
                    pass = o.optString("pass"),
                    m3uUrl = o.optString("m3u"),
                    name = o.optString("name")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(list: List<Settings>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("type", s.type.name)
                    .put("server", s.server)
                    .put("user", s.user)
                    .put("pass", s.pass)
                    .put("m3u", s.m3uUrl)
                    .put("name", s.name)
            )
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }

    /* ----- orden, favoritos, posición ----- */

    fun loadSort(): SortMode =
        runCatching { SortMode.valueOf(sp.getString("sort", "NAME_ASC") ?: "NAME_ASC") }
            .getOrDefault(SortMode.NAME_ASC)

    fun saveSort(m: SortMode) = sp.edit().putString("sort", m.name).apply()

    fun favorites(): Set<String> = sp.getStringSet("fav", emptySet())?.toSet() ?: emptySet()

    fun saveFavorites(f: Set<String>) = sp.edit().putStringSet("fav", HashSet(f)).apply()

    /** Posición guardada (ms) para continuar películas y episodios donde se quedaron. */
    fun position(url: String): Long = pos.getLong(url.hashCode().toString(), 0L)

    fun savePosition(url: String, ms: Long) {
        val k = url.hashCode().toString()
        if (ms <= 0L) pos.edit().remove(k).apply() else pos.edit().putLong(k, ms).apply()
    }

    /* ----- opciones del reproductor y control parental ----- */

    fun loadOpts() = PlayerOpts(
        decoder = runCatching { Decoder.valueOf(sp.getString("dec", "HARDWARE") ?: "HARDWARE") }
            .getOrDefault(Decoder.HARDWARE),
        tunnel = sp.getBoolean("tunnel", false),
        buffer = runCatching { BufferSize.valueOf(sp.getString("buf", "NORMAL") ?: "NORMAL") }
            .getOrDefault(BufferSize.NORMAL),
        live = runCatching { LiveFormat.valueOf(sp.getString("livefmt", "HLS") ?: "HLS") }
            .getOrDefault(LiveFormat.HLS)
    )

    fun saveOpts(o: PlayerOpts) {
        sp.edit()
            .putString("dec", o.decoder.name)
            .putBoolean("tunnel", o.tunnel)
            .putString("buf", o.buffer.name)
            .putString("livefmt", o.live.name)
            .apply()
    }

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) {
            sp.edit().putString("pin", v).apply()
        }
}

private fun normalize(server: String): String {
    val t = server.trim().trimEnd('/')
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
}

class AppVM(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private var api: IptvSource? = null
    private val cache = mutableMapOf<Section, Pair<List<Item>, List<Category>>>()

    var saved by mutableStateOf(prefs.load())
    var profiles by mutableStateOf(prefs.profiles())
    var account by mutableStateOf<AccountInfo?>(null)
    var loggedIn by mutableStateOf(false)
    var inSection by mutableStateOf(false)      // false = pantalla de inicio con los tres mosaicos
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var section by mutableStateOf(Section.LIVE)
    var sort by mutableStateOf(prefs.loadSort())
    var category by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var items by mutableStateOf<List<Item>>(emptyList())
    var cats by mutableStateOf<List<Category>>(emptyList())
    var favs by mutableStateOf(prefs.favorites())

    var opts by mutableStateOf(prefs.loadOpts())
    var pinSet by mutableStateOf(prefs.pin.isNotEmpty())
    var unlocked by mutableStateOf(false)

    // El scroll se guarda aquí para no perder el lugar al volver del reproductor
    val rowsState = LazyListState()
    val gridState = LazyGridState()

    var playing by mutableStateOf<PlayRequest?>(null)
    var epgNow by mutableStateOf<String?>(null)
    var liveSel by mutableStateOf<Item?>(null)       // canal elegido en la vista previa de Live
    var detailItem by mutableStateOf<Item?>(null)
    var detail by mutableStateOf<Detail?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())

    private var currentItem: Item? = null
    private var currentEp: Episode? = null
    private var zapList: List<Item> = emptyList()

    init {
        // Reconexión automática con el último usuario
        val s = saved
        val hasData = if (s.type == SourceType.M3U) s.m3uUrl.isNotBlank() else s.server.isNotBlank() && s.user.isNotBlank()
        if (hasData) login(s)
    }

    /* ----- ajustes ----- */

    fun setMode(m: DeviceMode) {
        saved = saved.copy(mode = m)
        prefs.save(saved)
    }

    fun changeSort(m: SortMode) {
        sort = m
        prefs.saveSort(m)
        resetScroll()
    }

    fun updateOpts(o: PlayerOpts) {
        opts = o
        prefs.saveOpts(o)
    }

    private fun resetScroll(rows: Boolean = true) {
        viewModelScope.launch {
            if (rows) runCatching { rowsState.scrollToItem(0) }
            runCatching { gridState.scrollToItem(0) }
        }
    }

    /** Abre una categoría completa (o los favoritos) en cuadrícula. */
    fun openCategory(id: String) {
        category = id
        resetScroll(rows = false)
    }

    fun closeCategory() {
        category = null
        query = ""
    }

    /* ----- control parental ----- */

    fun setPin(p: String) {
        prefs.pin = p
        pinSet = true
        unlocked = false
    }

    fun tryUnlock(p: String): Boolean {
        val ok = p == prefs.pin
        if (ok) unlocked = true
        return ok
    }

    fun lock() {
        unlocked = false
    }

    fun clearPin(p: String): Boolean {
        if (p != prefs.pin) return false
        prefs.pin = ""
        pinSet = false
        unlocked = false
        return true
    }

    /* ----- favoritos ----- */

    private fun favKey(i: Item) = "${i.section.name}:${i.id}"

    fun isFav(i: Item) = favKey(i) in favs

    fun toggleFav(i: Item) {
        val k = favKey(i)
        favs = if (k in favs) favs - k else favs + k
        prefs.saveFavorites(favs)
    }

    /* ----- sesión, usuarios y listas ----- */

    private fun buildSource(s: Settings): IptvSource {
        val cacheDir = getApplication<Application>().cacheDir
        if (s.type == SourceType.M3U) {
            val url = normalize(s.m3uUrl)
            // Si el enlace M3U trae usuario/clave Xtream, se usa la API completa (fechas, guía, series, cuenta)
            val x = xtreamFromM3u(url)
            return if (x != null) XtreamApi(x.first, x.second, x.third, cacheDir) else M3uSource(url, cacheDir)
        }
        return XtreamApi(normalize(s.server), s.user.trim(), s.pass, cacheDir)
    }

    private fun saveProfile(s: Settings) {
        val key = profileKey(s)
        val list = profiles.filterNot { profileKey(it) == key } + s.copy(mode = DeviceMode.AUTO)
        profiles = list
        prefs.saveProfiles(list)
    }

    fun deleteProfile(s: Settings) {
        val key = profileKey(s)
        val list = profiles.filterNot { profileKey(it) == key }
        profiles = list
        prefs.saveProfiles(list)
    }

    fun login(s: Settings) {
        saved = s
        prefs.save(s)
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val a = buildSource(s)
                val info = a.login()
                api = a
                account = info
                cache.clear()
                items = emptyList()
                cats = emptyList()
                liveSel = null
                loggedIn = true
                inSection = false
                section = Section.LIVE
                category = null
                query = ""
                saveProfile(s)
                busy = false
            } catch (e: Exception) {
                loggedIn = false
                busy = false
                error = e.message ?: "No se pudo conectar"
            }
        }
    }

    fun logout() {
        api = null
        cache.clear()
        items = emptyList()
        cats = emptyList()
        account = null
        liveSel = null
        detailItem = null
        loggedIn = false
        inSection = false
    }

    /** Entra a un apartado (desde el inicio o desde el menú). */
    fun select(sec: Section) {
        section = sec
        category = null
        query = ""
        error = null
        inSection = true
        resetScroll()
        viewModelScope.launch { load(sec) }
    }

    fun goHome() {
        inSection = false
        category = null
        query = ""
    }

    /** Vuelve a descargar la lista del apartado actual, ignorando la caché. */
    fun refresh() {
        error = null
        viewModelScope.launch { load(section, force = true) }
    }

    private suspend fun load(sec: Section, force: Boolean = false) {
        val a = api ?: return
        if (!force) {
            val c = cache[sec]
            if (c != null) {
                items = c.first
                cats = c.second
                return
            }
        }
        busy = true
        items = emptyList()
        cats = emptyList()
        try {
            val i = a.items(sec, force)
            val cs = a.categories(sec, force)
            cache[sec] = i to cs
            // En equipos con poca RAM, si las listas son muy grandes solo se conserva el apartado actual
            if (cache.values.sumOf { it.first.size } > 40_000) cache.keys.retainAll(setOf(sec))
            if (section == sec) {
                items = i
                cats = cs
            }
        } catch (e: OutOfMemoryError) {
            error = "La lista es demasiado grande para la memoria de este dispositivo"
        } catch (e: Exception) {
            error = e.message ?: "Error al cargar la lista"
        }
        busy = false
    }

    /* ----- abrir contenido ----- */

    fun urlOf(item: Item): String = api?.playUrl(item, opts.live.ext) ?: ""

    /** Live: reproduce a pantalla completa. Películas y series: abre su ficha. */
    fun open(item: Item, list: List<Item> = emptyList()) {
        if (item.section == Section.LIVE) {
            zapList = list
            startPlay(item)
        } else {
            openDetail(item)
        }
    }

    private fun openDetail(item: Item) {
        val a = api ?: return
        detailItem = item
        detail = null
        episodes = emptyList()
        error = null
        viewModelScope.launch {
            detail = runCatching { a.detail(item) }.getOrDefault(Detail())
            if (item.section == Section.SERIES) {
                try {
                    episodes = a.episodes(item)
                } catch (e: Exception) {
                    error = e.message ?: "No se pudieron cargar los episodios"
                }
            }
        }
    }

    fun closeDetail() {
        detailItem = null
    }

    fun playDetailMovie() {
        detailItem?.let { startPlay(it) }
    }

    private fun startPlay(item: Item) {
        val a = api ?: return
        currentItem = item
        currentEp = null
        epgNow = null
        val live = item.section == Section.LIVE
        playing = PlayRequest(item.name, a.playUrl(item, opts.live.ext), live)
        if (live) {
            viewModelScope.launch {
                val t = runCatching { a.nowPlaying(item) }.getOrNull()
                if (currentItem?.id == item.id) epgNow = t
            }
        }
    }

    /** Live en columnas: elegir un canal lo muestra en la vista previa. */
    fun selectLive(item: Item, list: List<Item>) {
        zapList = list
        liveSel = item
        epgNow = null
        val a = api ?: return
        viewModelScope.launch {
            val t = runCatching { a.nowPlaying(item) }.getOrNull()
            if (liveSel?.id == item.id) epgNow = t
        }
    }

    fun fullscreenLive() {
        liveSel?.let { startPlay(it) }
    }

    /** Cambio de canal con CH+ / CH- (o RePág / AvPág). */
    fun zap(delta: Int) {
        if (playing?.live != true) return
        val cur = currentItem ?: return
        val l = zapList
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.id == cur.id }
        if (i < 0) return
        val next = l[(i + delta + l.size) % l.size]
        liveSel = next
        startPlay(next)
    }

    fun playEpisode(e: Episode) {
        currentEp = e
        currentItem = null
        epgNow = null
        playing = PlayRequest("${detailItem?.name ?: ""} · T${e.season}E${e.num}", e.url, false)
    }

    /** Al terminar un episodio pasa al siguiente automáticamente; si no hay más, vuelve a la ficha. */
    fun onEnded() {
        val p = playing ?: return
        if (p.live) return
        val ep = currentEp
        if (ep != null) {
            val i = episodes.indexOfFirst { it.id == ep.id }
            if (i >= 0 && i + 1 < episodes.size) {
                playEpisode(episodes[i + 1])
                return
            }
        }
        playing = null
    }
}
