package com.example.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Dlg { NONE, MENU, SETTINGS, PIN }

fun sortItems(list: List<Item>, mode: SortMode): List<Item> {
    val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    return when (mode) {
        SortMode.NAME_ASC -> list.sortedWith(byName)
        SortMode.NAME_DESC -> list.sortedWith(byName).reversed()
        SortMode.DATE_DESC -> list.sortedByDescending { it.added }
        SortMode.DATE_ASC -> list.sortedBy { it.added }
    }
}

/* ---------- Inicio: tres mosaicos + datos de la cuenta ---------- */

@Composable
fun DashboardScreen(vm: AppVM, isTv: Boolean) {
    var dlg by remember { mutableStateOf(Dlg.NONE) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(300)
        runCatching { first.requestFocus() }   // en la TV queda listo para pulsar OK
    }

    Column(
        Modifier.fillMaxSize().padding(if (isTv) 32.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "IPTV Player", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Chip("Usuarios") { vm.logout() }
            Chip("Control parental") { dlg = Dlg.PIN }
            Chip("Ajustes") { dlg = Dlg.SETTINGS }
        }

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Tile("Live", "Canales en vivo", Color(0xFF1565C0), Modifier.weight(1f).fillMaxHeight(), first) {
                vm.select(Section.LIVE)
            }
            Tile("Películas", "Estrenos y catálogo", Color(0xFF6A1B9A), Modifier.weight(1f).fillMaxHeight()) {
                vm.select(Section.MOVIES)
            }
            Tile("Series", "Temporadas y episodios", Color(0xFF00695C), Modifier.weight(1f).fillMaxHeight()) {
                vm.select(Section.SERIES)
            }
        }

        AccountCard(vm.account)
    }

    AppDialogs(vm, isTv, dlg) { dlg = it }
}

@Composable
private fun Tile(
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    FocusBox(modifier, onClick, focusRequester = focusRequester) {
        Column(
            Modifier.fillMaxSize().background(color).padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xCCFFFFFF), fontSize = 13.sp)
        }
    }
}

@Composable
private fun AccountCard(a: AccountInfo?) {
    if (a == null) return   // lista M3U: sin datos de cuenta
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val exp = a.expires?.let { it * 1000 }
    val vence = if (exp == null || exp <= 0L) {
        "Sin vencimiento"
    } else {
        val days = (exp - System.currentTimeMillis()) / 86_400_000L
        fmt.format(Date(exp)) + "  (" + (if (days >= 0) "faltan $days días" else "vencida") + ")"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A212B))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Mi cuenta", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("Usuario: ${a.user}     Estado: ${a.status}", color = Color.LightGray, fontSize = 13.sp)
        Text("Vence: $vence", color = Color.LightGray, fontSize = 13.sp)
        if (a.maxConnections.isNotBlank()) {
            Text(
                "Conexiones: ${a.activeConnections.ifBlank { "0" }} de ${a.maxConnections}",
                color = Color.LightGray, fontSize = 13.sp
            )
        }
    }
}

/* ---------- Películas y Series: filas por categoría ---------- */

private class CatRow(val id: String, val name: String, val items: List<Item>)

private fun cardWidth(item: Item, isTv: Boolean) = when {
    item.section == Section.LIVE -> if (isTv) 150.dp else 120.dp
    else -> if (isTv) 116.dp else 96.dp
}

@Composable
fun HomeScreen(vm: AppVM, isTv: Boolean) {
    var dlg by remember { mutableStateOf(Dlg.NONE) }
    val searching = vm.query.isNotBlank()
    val inCategory = vm.category != null

    // Atrás: sale de la categoría o búsqueda y, si no, vuelve al inicio
    BackHandler { if (searching || inCategory) vm.closeCategory() else vm.goHome() }

    // Control parental: con PIN puesto y bloqueado, se ocultan las categorías para adultos
    val hide = vm.pinSet && !vm.unlocked
    val adultIds = remember(vm.cats) { vm.cats.filter { isAdult(it.name) }.map { it.id }.toSet() }
    val base = remember(vm.items, hide, adultIds) {
        if (hide && adultIds.isNotEmpty()) vm.items.filter { it.categoryId !in adultIds } else vm.items
    }
    val sorted = remember(base, vm.sort) { sortItems(base, vm.sort) }

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
    val rows = remember(sorted, vm.cats, vm.favs, hide, adultIds) {
        val byCat = sorted.groupBy { it.categoryId }
        val out = mutableListOf<CatRow>()
        val favItems = sorted.filter { vm.isFav(it) }
        if (favItems.isNotEmpty()) out.add(CatRow(FAV, "★ Favoritos", favItems))
        vm.cats.distinctBy { it.id }
            .filter { !(hide && it.id in adultIds) }
            .forEach { c -> byCat[c.id]?.let { out.add(CatRow(c.id, c.name, it)) } }
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
            if (searching || inCategory) {
                Chip("← Volver") { vm.closeCategory() }
            } else {
                Chip("← Inicio") { vm.goHome() }
            }
            Text(
                title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Chip("Menú") { dlg = Dlg.MENU }   // un solo botón con todas las opciones
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

    AppDialogs(vm, isTv, dlg) { dlg = it }
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

/* ---------- Ficha de película o serie ---------- */

@Composable
fun DetailScreen(vm: AppVM) {
    BackHandler { vm.closeDetail() }
    val item = vm.detailItem ?: return
    val d = vm.detail
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val resume = remember(item.id) { item.section == Section.MOVIES && prefs.position(vm.urlOf(item)) > 0L }
    val first = remember { FocusRequester() }
    LaunchedEffect(item.id) {
        delay(300)
        runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(
                model = d?.image ?: item.icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(140.dp)
                    .aspectRatio(0.67f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF11161D))
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (d == null) {
                    CircularProgressIndicator()
                } else {
                    val meta = listOf(
                        d.year,
                        d.genre,
                        if (d.rating.isNotBlank() && d.rating != "0") "★ ${d.rating}" else "",
                        d.duration
                    ).filter { it.isNotBlank() }.joinToString("   •   ")
                    if (meta.isNotBlank()) Text(meta, color = Color.LightGray, fontSize = 13.sp)
                    if (d.plot.isNotBlank()) {
                        Text(d.plot, color = Color.White, fontSize = 13.sp, maxLines = 7, overflow = TextOverflow.Ellipsis)
                    }
                    if (d.director.isNotBlank()) {
                        Text("Director: ${d.director}", color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (d.cast.isNotBlank()) {
                        Text("Reparto: ${d.cast}", color = Color.Gray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.section == Section.MOVIES) {
                        Chip(if (resume) "Continuar" else "Reproducir", focusRequester = first) { vm.playDetailMovie() }
                    }
                    Chip(
                        if (vm.isFav(item)) "Quitar de favoritos" else "Agregar a favoritos",
                        focusRequester = if (item.section == Section.SERIES) first else null
                    ) { vm.toggleFav(item) }
                    Chip("← Volver") { vm.closeDetail() }
                }
            }
        }

        if (item.section == Section.SERIES) {
            vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }
            if (vm.episodes.isEmpty() && vm.error == null) CircularProgressIndicator()
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
}

/* ---------- Diálogos: menú, ajustes del reproductor y control parental ---------- */

@Composable
fun AppDialogs(vm: AppVM, isTv: Boolean, dlg: Dlg, onChange: (Dlg) -> Unit) {
    when (dlg) {
        Dlg.NONE -> {}
        Dlg.MENU -> MenuDialog(vm, isTv, onChange)
        Dlg.SETTINGS -> SettingsDialog(vm, isTv) { onChange(Dlg.NONE) }
        Dlg.PIN -> PinDialog(vm, isTv) { onChange(Dlg.NONE) }
    }
}

@Composable
private fun DialogShell(isTv: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF161B22),
            modifier = Modifier.fillMaxWidth(if (isTv) 0.6f else 0.94f)
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit
) {
    Text(title, color = Color.LightGray)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { o -> Chip(label(o), o == selected) { onPick(o) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuDialog(vm: AppVM, isTv: Boolean, onChange: (Dlg) -> Unit) {
    val close = { onChange(Dlg.NONE) }
    DialogShell(isTv, close) {
        Text("Apartado", color = Color.LightGray)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Section.values().forEach { s -> Chip(s.label, vm.section == s) { vm.select(s); close() } }
        }

        OptionRow("Ordenar por", SortMode.values().toList(), vm.sort, { it.label }) {
            vm.changeSort(it)
            close()
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
            Chip("Inicio") { vm.goHome(); close() }
            Chip("★ Favoritos") { vm.openCategory(FAV); close() }
            Chip("Actualizar lista") { vm.refresh(); close() }
            Chip("Control parental") { onChange(Dlg.PIN) }
            Chip("Ajustes del reproductor") { onChange(Dlg.SETTINGS) }
            Chip("Cerrar sesión") { vm.logout(); close() }
            Chip("Cerrar menú") { close() }
        }

        Text(
            if (isTv) "Para marcar un favorito: mantén pulsado OK o pulsa MENÚ sobre el elemento"
            else "Para marcar un favorito: mantén pulsado el elemento",
            color = Color.Gray, fontSize = 11.sp
        )
    }
}

@Composable
private fun SettingsDialog(vm: AppVM, isTv: Boolean, onClose: () -> Unit) {
    DialogShell(isTv, onClose) {
        Text("Ajustes del reproductor", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        OptionRow("Decodificador de video", Decoder.values().toList(), vm.opts.decoder, { it.label }) {
            vm.updateOpts(vm.opts.copy(decoder = it))
        }
        OptionRow(
            "Modo túnel de video (en algunas cajas mejora el 4K; si hay fallos, desactívalo)",
            listOf(false, true), vm.opts.tunnel, { if (it) "Activado" else "Desactivado" }
        ) {
            vm.updateOpts(vm.opts.copy(tunnel = it))
        }
        OptionRow("Tamaño del buffer", BufferSize.values().toList(), vm.opts.buffer, { "${it.label} (${it.mb} MB)" }) {
            vm.updateOpts(vm.opts.copy(buffer = it))
        }
        OptionRow("Formato de Live", LiveFormat.values().toList(), vm.opts.live, { it.label }) {
            vm.updateOpts(vm.opts.copy(live = it))
        }

        Text("Los cambios se aplican al abrir el siguiente video.", color = Color.Gray, fontSize = 11.sp)
        Chip("Cerrar") { onClose() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PinDialog(vm: AppVM, isTv: Boolean, onClose: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    DialogShell(isTv, onClose) {
        Text("Control parental", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Con un PIN puesto, las categorías para adultos (adult, xxx, +18…) quedan ocultas hasta que las desbloquees.",
            color = Color.Gray, fontSize = 12.sp
        )

        if (!vm.pinSet) {
            Text("Crea un PIN de 4 dígitos", color = Color.LightGray)
        } else {
            Text(if (vm.unlocked) "Estado: desbloqueado" else "Estado: bloqueado", color = Color.LightGray)
        }
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
            label = { Text("PIN") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!vm.pinSet) {
                Chip("Guardar PIN") {
                    if (pin.length == 4) {
                        vm.setPin(pin)
                        onClose()
                    } else {
                        msg = "El PIN debe tener 4 dígitos"
                    }
                }
            } else {
                Chip("Desbloquear") { if (vm.tryUnlock(pin)) onClose() else msg = "PIN incorrecto" }
                Chip("Bloquear") { vm.lock(); onClose() }
                Chip("Quitar PIN") { if (vm.clearPin(pin)) onClose() else msg = "PIN incorrecto" }
            }
            Chip("Cerrar") { onClose() }
        }
        msg?.let { Text(it, color = Color(0xFFFF8A80)) }
    }
}
