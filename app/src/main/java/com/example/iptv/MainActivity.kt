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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Si la app se cierra por un error, lo guarda para mostrarlo la próxima vez que la abras
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences("iptv_crash", MODE_PRIVATE).edit().putString("last", describe(e)).commit()
            }
            previous?.uncaughtException(t, e)
        }
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

    // Aviso con el error de la última vez que la app se cerró
    val crashPrefs = remember { ctx.getSharedPreferences("iptv_crash", Context.MODE_PRIVATE) }
    var crash by remember { mutableStateOf(crashPrefs.getString("last", null)) }
    crash?.let { txt ->
        val close = {
            crashPrefs.edit().remove("last").apply()
            crash = null
        }
        Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2A1215),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("La app se cerró por un error la última vez", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(txt, color = Color(0xFFFFCDD2), fontSize = 11.sp)
                    Chip("Cerrar") { close() }
                }
            }
        }
    }
}

private fun describe(e: Throwable): String {
    val sb = StringBuilder()
    var c: Throwable? = e
    var depth = 0
    while (c != null && depth < 4) {
        sb.append(if (depth == 0) "" else "Causa: ").append(c.toString()).append('\n')
        c.stackTrace.take(8).forEach { sb.append("  at ").append(it.toString()).append('\n') }
        c = c.cause
        depth++
    }
    return sb.toString().take(2500)
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

/* ---------- Home: filas por categoría (estilo catálogo) ---------- */

private class CatRow(val id: String, val name: String, val items: List<Item>)

private fun sortItems(list: List<Item>, mode: SortMode): List<Item> {
    val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    return when (mode) {
        SortMode.NAME_ASC -> list.sortedWith(byName)
        SortMode.NAME_DESC -> list.sortedWith(byName).reversed()
        SortMode.DATE_DESC -> list.sortedByDescending { it.added }
        SortMode.DATE_ASC -> list.sortedBy { it.added }
    }
}

private fun cardWidth(item: Item, isTv: Boolean) = when {
    item.section == Section.LIVE -> if (isTv) 150.dp else 120.dp
    else -> if (isTv) 116.dp else 96.dp
}

@Composable
fun HomeScreen(vm: AppVM, isTv: Boolean) {
    var showMenu by remember { mutableStateOf(false) }
    val searching = vm.query.isNotBlank()
    val inCategory = vm.category != null

    // Atrás: sale de la categoría o de la búsqueda
    BackHandler(enabled = searching || inCategory) { vm.closeCategory() }

    val sorted = remember(vm.items, vm.sort) { sortItems(vm.items, vm.sort) }

    // Lista plana: categoría abierta, favoritos o resultados de búsqueda
    val flat = remember(sorted, vm.category, vm.query, vm.favs) {
        val cat = vm.category
        if (cat == null && vm.query.isBlank()) {
            emptyList()
        } else {
            sorted.filter {
                val okCat = when (cat) {
                    null -> true
                    FAV -> vm.isFav(it)
                    else -> it.categoryId == cat
                }
                okCat && (vm.query.isBlank() || it.name.contains(vm.query, ignoreCase = true))
            }
        }
    }

    // Filas: una por categoría, con favoritos primero si hay
    val rows = remember(sorted, vm.cats, vm.favs) {
        val byCat = sorted.groupBy { it.categoryId }
        val out = mutableListOf<CatRow>()
        val favItems = sorted.filter { vm.isFav(it) }
        if (favItems.isNotEmpty()) out.add(CatRow(FAV, "★ Favoritos", favItems))
        vm.cats.distinctBy { it.id }.forEach { c ->
            byCat[c.id]?.let { out.add(CatRow(c.id, c.name, it)) }
        }
        out
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = if (isTv) 24.dp else 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val title = when {
            searching -> "Resultados: ${vm.query}"
            vm.category == FAV -> "★ Favoritos"
            inCategory -> vm.cats.firstOrNull { it.id == vm.category }?.name ?: ""
            else -> vm.section.label
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (searching || inCategory) Chip("← Volver") { vm.closeCategory() }
            Text(
                title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Chip("☰ Menú") { showMenu = true }   // un solo botón con todas las opciones
        }

        if (vm.busy) CircularProgressIndicator()
        vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }

        if (searching || inCategory) {
            if (flat.isEmpty() && !vm.busy) Text("No hay elementos para mostrar", color = Color.Gray)
            val minW = if (vm.section == Section.LIVE) (if (isTv) 150.dp else 120.dp) else (if (isTv) 116.dp else 96.dp)
            LazyVerticalGrid(
                state = vm.gridState,
                columns = GridCells.Adaptive(minW),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(flat, key = { it.id }) { item ->
                    SmallCard(
                        item, Modifier.fillMaxWidth(), vm.isFav(item),
                        { vm.open(item, flat) }, { vm.toggleFav(item) }
                    )
                }
            }
        } else {
            if (rows.isEmpty() && !vm.busy && vm.error == null) {
                Text("No hay elementos para mostrar", color = Color.Gray)
            }
            RowsView(vm, rows, isTv, Modifier.weight(1f))
        }
    }

    if (showMenu) MenuDialog(vm, isTv) { showMenu = false }
}

@Composable
private fun RowsView(vm: AppVM, rows: List<CatRow>, isTv: Boolean, modifier: Modifier) {
    LazyColumn(
        state = vm.rowsState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(rows, key = { it.id }) { r ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Entrar a la categoría completa
                FocusBox(onClick = { vm.openCategory(r.id) }) {
                    Text(
                        "${r.name}   ›   ${r.items.size}",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    items(r.items.take(40), key = { it.id }) { item ->
                        SmallCard(
                            item, Modifier.width(cardWidth(item, isTv)), vm.isFav(item),
                            { vm.open(item, r.items) }, { vm.toggleFav(item) }
                        )
                    }
                    if (r.items.size > 40) {
                        item {
                            Box(Modifier.width(90.dp).height(120.dp), contentAlignment = Alignment.Center) {
                                Chip("Ver todo ›") { vm.openCategory(r.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SmallCard(item: Item, modifier: Modifier, fav: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val live = item.section == Section.LIVE
    FocusBox(modifier, onClick, onLongClick) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1A212B)).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AsyncImage(
                model = item.icon,
                contentDescription = null,
                contentScale = if (live) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (live) 1.5f else 0.67f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF11161D))
            )
            Text(
                item.name, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp).fillMaxWidth().height(28.dp)
            )
        }
        if (fav) {
            Text(
                "★", color = Color(0xFFFFC107), fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            )
        }
    }
}

/* ---------- Menú único (botón arriba a la derecha) ---------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuDialog(vm: AppVM, isTv: Boolean, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF161B22),
            modifier = Modifier.fillMaxWidth(if (isTv) 0.6f else 0.94f)
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Apartado", color = Color.LightGray)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Section.values().forEach { s ->
                        Chip(s.label, vm.section == s) { vm.select(s); onClose() }
                    }
                }

                Text("Ordenar por", color = Color.LightGray)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SortMode.values().forEach { m ->
                        Chip(m.label, vm.sort == m) { vm.changeSort(m); onClose() }
                    }
                }

                Text("Buscar", color = Color.LightGray)
                OutlinedTextField(
                    value = vm.query, onValueChange = { vm.query = it },
                    placeholder = { Text("Nombre de película, serie o canal…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                Text("Más opciones", color = Color.LightGray)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Chip("★ Favoritos") { vm.openCategory(FAV); onClose() }
                    Chip("Actualizar lista") { vm.refresh(); onClose() }
                    Chip("Cerrar sesión") { vm.logout(); onClose() }
                    Chip("Cerrar menú") { onClose() }
                }

                Text(
                    if (isTv) "Para marcar un favorito: mantén pulsado OK o pulsa MENÚ sobre el elemento"
                    else "Para marcar un favorito: mantén pulsado el elemento",
                    color = Color.Gray, fontSize = 11.sp
                )
            }
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
