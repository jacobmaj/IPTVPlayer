package com.example.iptv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

const val FAV = "__fav__"   // "categoría" especial de favoritos

enum class SortMode(val label: String) {
    NAME_ASC("A → Z"),
    NAME_DESC("Z → A"),
    DATE_DESC("Más recientes"),
    DATE_ASC("Más antiguos")
}

data class PlayRequest(val title: String, val url: String, val live: Boolean)

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
            .getOrDefault(DeviceMode.AUTO)
    )

    fun save(s: Settings) {
        sp.edit()
            .putString("type", s.type.name)
            .putString("server", s.server)
            .putString("user", s.user)
            .putString("pass", s.pass)
            .putString("m3u", s.m3uUrl)
            .putString("mode", s.mode.name)
            .apply()
    }

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
    var loggedIn by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var section by mutableStateOf(Section.LIVE)
    var sort by mutableStateOf(prefs.loadSort())
    var category by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var items by mutableStateOf<List<Item>>(emptyList())
    var cats by mutableStateOf<List<Category>>(emptyList())
    var favs by mutableStateOf(prefs.favorites())

    var playing by mutableStateOf<PlayRequest?>(null)
    var epgNow by mutableStateOf<String?>(null)
    var openSeries by mutableStateOf<Item?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())

    private var currentItem: Item? = null
    private var currentEp: Episode? = null
    private var zapList: List<Item> = emptyList()

    init {
        // Reconexión automática si ya había datos guardados
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
    }

    /* ----- favoritos ----- */

    private fun favKey(i: Item) = "${i.section.name}:${i.id}"

    fun isFav(i: Item) = favKey(i) in favs

    fun toggleFav(i: Item) {
        val k = favKey(i)
        favs = if (k in favs) favs - k else favs + k
        prefs.saveFavorites(favs)
    }

    /* ----- sesión y listas ----- */

    private fun buildSource(s: Settings): IptvSource {
        val cacheDir = getApplication<Application>().cacheDir
        if (s.type == SourceType.M3U) {
            val url = normalize(s.m3uUrl)
            // Si el enlace M3U trae usuario/clave Xtream, se usa la API completa (mejor: fechas, guía, series)
            val x = xtreamFromM3u(url)
            return if (x != null) XtreamApi(x.first, x.second, x.third, cacheDir) else M3uSource(url, cacheDir)
        }
        return XtreamApi(normalize(s.server), s.user.trim(), s.pass, cacheDir)
    }

    fun login(s: Settings) {
        saved = s
        prefs.save(s)
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val a = buildSource(s)
                a.login()
                api = a
                cache.clear()
                loggedIn = true
                section = Section.LIVE
                category = null
                query = ""
                busy = false
                load(Section.LIVE)
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
        loggedIn = false
    }

    fun select(sec: Section) {
        section = sec
        category = null
        query = ""
        error = null
        viewModelScope.launch { load(sec) }
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
            if (section == sec) {
                items = i
                cats = cs
            }
        } catch (e: Exception) {
            error = e.message ?: "Error al cargar la lista"
        }
        busy = false
    }

    /* ----- reproducción ----- */

    fun open(item: Item, list: List<Item> = emptyList()) {
        val a = api ?: return
        if (item.section == Section.SERIES) {
            openSeries = item
            episodes = emptyList()
            error = null
            viewModelScope.launch {
                try {
                    episodes = a.episodes(item)
                } catch (e: Exception) {
                    error = e.message ?: "No se pudieron cargar los episodios"
                }
            }
        } else {
            zapList = list
            startPlay(item)
        }
    }

    private fun startPlay(item: Item) {
        val a = api ?: return
        currentItem = item
        currentEp = null
        epgNow = null
        val live = item.section == Section.LIVE
        playing = PlayRequest(item.name, a.playUrl(item), live)
        if (live) {
            viewModelScope.launch {
                val t = runCatching { a.nowPlaying(item) }.getOrNull()
                if (currentItem?.id == item.id) epgNow = t
            }
        }
    }

    /** Cambio de canal con CH+ / CH- (o RePág / AvPág). */
    fun zap(delta: Int) {
        if (playing?.live != true) return
        val cur = currentItem ?: return
        val l = zapList
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.id == cur.id }
        if (i < 0) return
        startPlay(l[(i + delta + l.size) % l.size])
    }

    fun playEpisode(e: Episode) {
        currentEp = e
        currentItem = null
        epgNow = null
        playing = PlayRequest("${openSeries?.name ?: ""} · T${e.season}E${e.num}", e.url, false)
    }

    /** Al terminar un episodio pasa al siguiente automáticamente; si no hay más, vuelve a la lista. */
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
