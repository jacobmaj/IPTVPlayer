package com.majano.iptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppModel(
    private val store: Store,
    private val scope: CoroutineScope,
    val player: ExoPlayer,
) {
    var loggedIn by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var messageIsError by mutableStateOf(false)
    var accountInfo by mutableStateOf("")
    var session: Session? = null
        private set

    val savedLink: String get() = store.link
    val savedUser: String get() = store.user

    val cats = mutableStateMapOf<Kind, List<Category>>()
    val items = mutableStateMapOf<Kind, List<Entry>>()
    private val loadingKinds = mutableStateListOf<Kind>()
    fun isLoading(k: Kind): Boolean = loadingKinds.contains(k)

    val favorites = mutableStateListOf<String>().apply { addAll(store.favorites()) }

    // Reproductor
    var nowTitle by mutableStateOf("")
    var hasMedia by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var playerError by mutableStateOf("")
    var fullscreen by mutableStateOf(false)
    private var queue: List<Entry> = emptyList()
    private var queueIndex = -1
    private var currentLive = false
    private var altTried = false
    private var currentUrl = ""

    // Series
    var openedSeries by mutableStateOf<Entry?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    var episodesLoading by mutableStateOf(false)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                val alt = if (!altTried) altUrl(currentUrl) else null
                if (alt != null && currentLive) {
                    altTried = true
                    currentUrl = alt
                    player.setMediaItem(MediaItem.fromUri(alt))
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                playerError = "No se pudo reproducir (${error.errorCodeName}). El canal puede estar caído o tu plan no permite esta salida."
            }
        })
        if (store.auto && store.link.isNotBlank() && (store.pass.isNotBlank() || parseLink(store.link)?.pass != null)) {
            login(store.link, store.user, store.pass)
        }
    }

    private fun altUrl(url: String): String? = when {
        url.endsWith(".m3u8") -> url.dropLast(5) + ".ts"
        url.endsWith(".ts") -> url.dropLast(3) + ".m3u8"
        else -> null
    }

    private fun setInfo(text: String) { message = text; messageIsError = false }
    private fun setError(text: String) { message = text; messageIsError = true }

    fun login(link: String, user: String, pass: String) {
        if (busy) return
        busy = true
        setInfo("Conectando con el proveedor…")
        scope.launch {
            val r: ConnectResult = try {
                withContext(Dispatchers.IO) { Provider.connect(link, user, pass) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectResult.Fail(Net.describe(e))
            }
            when (r) {
                is ConnectResult.Fail -> {
                    setError(r.reason)
                    busy = false
                }
                is ConnectResult.Ok -> {
                    store.saveLogin(link, user, pass)
                    resetCatalog()
                    session = r.session
                    accountInfo = r.info
                    val playlist = r.playlist
                    if (playlist != null) applyPlaylist(playlist)
                    loggedIn = true
                    busy = false
                    setInfo("")
                    loadKind(Kind.LIVE)
                }
            }
        }
    }

    private fun applyPlaylist(list: List<Entry>) {
        for (k in Kind.values()) {
            val l = list.filter { it.kind == k }
            items[k] = l
            cats[k] = l.map { it.categoryId }.distinct().map { Category(it, it) }
        }
    }

    private fun resetCatalog() {
        cats.clear()
        items.clear()
        loadingKinds.clear()
        openedSeries = null
        episodes = emptyList()
        stopPlayback()
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        hasMedia = false
        fullscreen = false
        playerError = ""
        nowTitle = ""
        queue = emptyList()
        queueIndex = -1
    }

    fun logout() {
        store.clearLogin()
        resetCatalog()
        session = null
        loggedIn = false
        setInfo("")
    }

    fun loadKind(kind: Kind, force: Boolean = false) {
        val s = session ?: return
        if (!force && items.containsKey(kind)) return
        if (loadingKinds.contains(kind)) return
        if (!s.isXtream) {
            if (force) { login(store.link, store.user, store.pass); return }
            if (!items.containsKey(kind)) items[kind] = emptyList()
            return
        }
        loadingKinds.add(kind)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Pair(Provider.categories(s, kind), Provider.entries(s, kind))
                }
                cats[kind] = result.first
                items[kind] = result.second
                if (result.second.isEmpty()) setInfo("El proveedor no devolvió contenido en esta sección.") else setInfo("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(Net.describe(e))
            } finally {
                loadingKinds.remove(kind)
            }
        }
    }

    // Favoritos
    private fun key(e: Entry) = "${e.kind.name}:${e.id}"
    fun isFav(e: Entry): Boolean = favorites.contains(key(e))
    fun toggleFav(e: Entry) {
        val k = key(e)
        if (!favorites.remove(k)) favorites.add(k)
        store.saveFavorites(favorites.toSet())
    }

    // Reproducción
    fun open(e: Entry, from: List<Entry>) {
        if (e.kind == Kind.SERIES && e.url.isBlank()) {
            openSeries(e)
            return
        }
        queue = from
        queueIndex = from.indexOfFirst { it === e }
        playUrl(e.name, e.url, e.kind == Kind.LIVE)
    }

    fun playUrl(title: String, url: String, live: Boolean) {
        nowTitle = title
        currentUrl = url
        currentLive = live
        altTried = false
        playerError = ""
        hasMedia = true
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    /** Cambio de canal con las teclas CH+/CH- del control remoto. */
    fun zap(delta: Int): Boolean {
        if (!loggedIn || !hasMedia || !currentLive || queue.isEmpty() || queueIndex < 0) return false
        val n = queue.size
        queueIndex = ((queueIndex + delta) % n + n) % n
        val e = queue[queueIndex]
        playUrl(e.name, e.url, true)
        return true
    }

    fun openSeries(e: Entry) {
        val s = session ?: return
        openedSeries = e
        episodes = emptyList()
        episodesLoading = true
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Provider.episodes(s, e.id) }
                if (openedSeries === e) {
                    episodes = list
                    if (list.isEmpty()) setError("Esta serie no tiene episodios disponibles.") else setInfo("")
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                setError(Net.describe(ex))
            } finally {
                episodesLoading = false
            }
        }
    }

    fun closeSeries() {
        openedSeries = null
    }
}
