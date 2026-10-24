package com.example.iptv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBestDisplayMode(this)   // pide la mayor resolución de la pantalla (4K si existe)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0E1116)) { App(vm) }
            }
        }
    }

    // CH+ / CH- (o RePág / AvPág) cambian de canal mientras se ve Live
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && vm.playing?.live == true) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    vm.zap(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    vm.zap(-1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        vm.playing = null   // detiene la reproducción al salir de la app
    }
}

fun isTelevision(ctx: Context): Boolean =
    (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

@Composable
fun App(vm: AppVM) {
    val ctx = LocalContext.current
    val autoTv = remember { isTelevision(ctx) }
    val isTv = when (vm.saved.mode) {
        DeviceMode.TV -> true
        DeviceMode.PHONE -> false
        DeviceMode.AUTO -> autoTv
    }
    val p = vm.playing
    when {
        p != null -> androidx.compose.runtime.key(p.url) {
            PlayerScreen(p, vm.epgNow, { vm.onEnded() }) { vm.playing = null }
        }
        vm.openSeries != null -> SeriesScreen(vm)
        !vm.loggedIn -> LoginScreen(vm)
        else -> HomeScreen(vm, isTv)
    }
}

/* ---------- Componentes con foco visible para control remoto ---------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color(0xFF4FC3F7), shape) else Modifier)
            .clip(shape)
            // Tecla MENÚ del control remoto = acción larga (favorito)
            .onKeyEvent { e ->
                if (onLongClick != null && e.key == Key.Menu && e.type == KeyEventType.KeyUp) {
                    onLongClick()
                    true
                } else {
                    false
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        content = content
    )
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    FocusBox(onClick = onClick) {
        Text(
            text,
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .background(if (selected) Color(0xFF1E88E5) else Color(0xFF232A35))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/* ---------- Login ---------- */

@Composable
fun LoginScreen(vm: AppVM) {
    var type by remember { mutableStateOf(vm.saved.type) }
    var server by remember { mutableStateOf(vm.saved.server) }
    var user by remember { mutableStateOf(vm.saved.user) }
    var pass by remember { mutableStateOf(vm.saved.pass) }
    var m3u by remember { mutableStateOf(vm.saved.m3uUrl) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("IPTV Player", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

            Text("Tipo de acceso", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceType.values().forEach { t -> Chip(t.label, type == t) { type = t } }
            }

            if (type == SourceType.XTREAM) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text("Servidor (http://host:puerto)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Usuario") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("Contraseña") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                )
            } else {
                OutlinedTextField(
                    value = m3u, onValueChange = { m3u = it },
                    label = { Text("URL de la lista M3U / M3U8") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
                )
            }

            Text("¿Dónde lo vas a ver?", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceMode.values().forEach { m -> Chip(m.label, vm.saved.mode == m) { vm.setMode(m) } }
            }

            Chip("Conectar") { vm.login(Settings(type, server, user, pass, m3u, vm.saved.mode)) }

            if (vm.busy) CircularProgressIndicator()
            vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }
        }
    }
}

/* ---------- Home: Live / Películas / Series ---------- */

@Composable
fun HomeScreen(vm: AppVM, isTv: Boolean) {
    val list = remember(vm.items, vm.category, vm.query, vm.sort, vm.favs) {
        val cat = vm.category
        val filtered = vm.items.filter {
            val okCat = when (cat) {
                null -> true
                FAV -> vm.isFav(it)
                else -> it.categoryId == cat
            }
            okCat && (vm.query.isBlank() || it.name.contains(vm.query, ignoreCase = true))
        }
        val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        when (vm.sort) {
            SortMode.NAME_ASC -> filtered.sortedWith(byName)
            SortMode.NAME_DESC -> filtered.sortedWith(byName).reversed()
            SortMode.DATE_DESC -> filtered.sortedByDescending { it.added }
            SortMode.DATE_ASC -> filtered.sortedBy { it.added }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(if (isTv) 32.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Apartados
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Section.values().forEach { s -> Chip(s.label, vm.section == s) { vm.select(s) } }
        }
        // Orden alfabético y por fecha + acciones
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SortMode.values()) { m -> Chip(m.label, vm.sort == m) { vm.setSort(m) } }
            item { Chip("Actualizar") { vm.refresh() } }
            item { Chip("Salir") { vm.logout() } }
        }
        // Categorías y favoritos
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("Todas", vm.category == null) { vm.category = null } }
            item { Chip("★ Favoritos", vm.category == FAV) { vm.category = FAV } }
            items(vm.cats) { c -> Chip(c.name, vm.category == c.id) { vm.category = c.id } }
        }
        OutlinedTextField(
            value = vm.query, onValueChange = { vm.query = it },
            placeholder = { Text("Buscar…") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (isTv) "Mantén pulsado OK o pulsa MENÚ sobre un elemento para marcarlo como favorito"
            else "Mantén pulsado un elemento para marcarlo como favorito",
            color = Color.Gray, fontSize = 11.sp
        )

        if (vm.busy) CircularProgressIndicator()
        vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }
        if (!vm.busy && vm.error == null && list.isEmpty()) {
            Text("No hay elementos para mostrar", color = Color.Gray)
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (isTv) 180.dp else 110.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(list, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    fav = vm.isFav(item),
                    onClick = { vm.open(item, list) },
                    onLongClick = { vm.toggleFav(item) }
                )
            }
        }
    }
}

@Composable
fun ItemCard(item: Item, fav: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val live = item.section == Section.LIVE
    FocusBox(Modifier.fillMaxWidth(), onClick, onLongClick) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1A212B)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = item.icon,
                contentDescription = null,
                contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (live) 1.5f else 0.67f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF11161D))
            )
            Text(
                item.name, color = Color.White, fontSize = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (fav) {
            Text(
                "★", color = Color(0xFFFFC107), fontSize = 20.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
        }
    }
}

/* ---------- Episodios de una serie ---------- */

@Composable
fun SeriesScreen(vm: AppVM) {
    BackHandler { vm.openSeries = null }
    val s = vm.openSeries ?: return
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Chip("← Volver") { vm.openSeries = null }
        if (vm.episodes.isEmpty() && vm.error == null) CircularProgressIndicator()
        vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.episodes, key = { it.id }) { e ->
                FocusBox(Modifier.fillMaxWidth(), { vm.playEpisode(e) }) {
                    Text(
                        "T${e.season} · E${e.num}   ${e.title}",
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A212B))
                            .padding(14.dp)
                    )
                }
            }
        }
    }
}
