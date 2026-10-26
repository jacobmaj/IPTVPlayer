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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Dlg { NONE, MENU, SETTINGS, PIN, DIAG, GUIDE }

fun sortItems(list: List<Item>, mode: SortMode): List<Item> {
    val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    return when (mode) {
        SortMode.NAME_ASC -> list.sortedWith(byName)
        SortMode.NAME_DESC -> list.sortedWith(byName).reversed()
        SortMode.DATE_DESC -> list.sortedByDescending { it.added }
        SortMode.DATE_ASC -> list.sortedBy { it.added }
    }
}

/** Elementos del apartado actual que se vieron hace poco (más reciente primero). */
fun historyItems(vm: AppVM, pool: List<Item>): List<Item> {
    val byId = pool.associateBy { it.id }
    val prefix = vm.section.name + ":"
    return vm.history.filter { it.startsWith(prefix) }.mapNotNull { byId[it.removePrefix(prefix)] }
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
        Modifier.fillMaxSize().padding(horizontal = if (isTv) 32.dp else 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NavBar(vm, null) { dlg = Dlg.MENU }

        vm.update?.let { msg ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF3E2723))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(msg, color = Color.White, fontSize = 13.sp)
                Chip("Cerrar aviso") { vm.update = null }
            }
        }

        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Tile("Live", "Canales en vivo", Color(0xFFE50914), Color(0xFF3B0508), Modifier.weight(1f).fillMaxHeight(), first) {
                vm.select(Section.LIVE)
            }
            Tile("Películas", "Estrenos y catálogo", Color(0xFF283593), Color(0xFF0A0D2E), Modifier.weight(1f).fillMaxHeight()) {
                vm.select(Section.MOVIES)
            }
            Tile("Series", "Temporadas y episodios", Color(0xFF00796B), Color(0xFF031F1B), Modifier.weight(1f).fillMaxHeight()) {
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
    top: Color,
    bottom: Color,
    modifier: Modifier,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    FocusBox(modifier, onClick, focusRequester = focusRequester, scaleOnFocus = true) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
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
            .background(Nf.card)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Mi cuenta", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text("Usuario: ${a.user}     Estado: ${a.status}", color = Nf.dim, fontSize = 13.sp)
        Text("Vence: $vence", color = Nf.dim, fontSize = 13.sp)
        if (a.maxConnections.isNotBlank()) {
            Text(
                "Conexiones: ${a.activeConnections.ifBlank { "0" }} de ${a.maxConnections}",
                color = Nf.dim, fontSize = 13.sp
            )
        }
    }
}

/* ---------- Películas y Series: banner destacado y filas por categoría ---------- */

private class CatRow(val id: String, val name: String, val items: List<Item>)

private fun cardWidth(item: Item, isTv: Boolean) = when {
    item.section == Section.LIVE -> if (isTv) 150.dp else 120.dp
    else -> if (isTv) 126.dp else 100.dp
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
    val histItems = remember(base, vm.history, vm.section) { historyItems(vm, base) }
    val newest = remember(base) { base.sortedByDescending { it.added }.take(200) }

    // Título destacado del banner: lo último que estabas viendo o uno reciente al azar
    val hero = remember(histItems, newest, sorted, vm.section) {
        if (vm.section == Section.LIVE) {
            null
        } else {
            histItems.firstOrNull() ?: newest.take(15).randomOrNull() ?: sorted.firstOrNull()
        }
    }
    LaunchedEffect(hero?.id) { vm.loadHero(hero) }

    // Lista plana: categoría abierta, Mi lista, recientes o resultados de búsqueda
    val flat = remember(sorted, histItems, newest, vm.category, vm.query, vm.favs) {
        val cat = vm.category
        if (cat == null && vm.query.isBlank()) {
            emptyList()
        } else {
            val pool = when (cat) {
                NEW -> newest
                RECENT -> histItems
                else -> sorted
            }
            pool.filter {
                val okCat = when (cat) {
                    null, NEW, RECENT -> true
                    FAV -> vm.isFav(it)
                    else -> it.categoryId == cat
                }
                okCat && (vm.query.isBlank() || it.name.contains(vm.query, ignoreCase = true))
            }
        }
    }

    // Filas: Mi lista, continuar viendo, agregado recientemente y una por categoría
    val rows = remember(sorted, vm.cats, vm.favs, hide, adultIds, histItems, newest, vm.section) {
        val byCat = sorted.groupBy { it.categoryId }
        val out = mutableListOf<CatRow>()
        val favItems = sorted.filter { vm.isFav(it) }
        if (favItems.isNotEmpty()) out.add(CatRow(FAV, "Mi lista", favItems))
        if (histItems.isNotEmpty()) {
            out.add(CatRow(RECENT, if (vm.section == Section.LIVE) "Canales recientes" else "Continuar viendo", histItems))
        }
        if (vm.section != Section.LIVE && newest.size >= 5 && newest.first().added > 0L) {
            out.add(CatRow(NEW, "Agregado recientemente", newest))
        }
        vm.cats.distinctBy { it.id }
            .filter { !(hide && it.id in adultIds) }
            .forEach { c -> byCat[c.id]?.let { out.add(CatRow(c.id, c.name, it)) } }
        out
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = if (isTv) 24.dp else 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NavBar(vm, vm.section) { dlg = Dlg.MENU }

        if (searching || inCategory) {
            val title = when {
                searching -> "Resultados: ${vm.query}"
                vm.category == FAV -> "Mi lista"
                vm.category == NEW -> "Agregado recientemente"
                vm.category == RECENT -> if (vm.section == Section.LIVE) "Canales recientes" else "Continuar viendo"
                else -> vm.cats.firstOrNull { it.id == vm.category }?.name ?: ""
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NfButton("← Volver") { vm.closeCategory() }
                Text(
                    title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
        }

        if (vm.busy) CircularProgressIndicator(color = Nf.red)
        vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }

        if (searching || inCategory) {
            if (flat.isEmpty() && !vm.busy) Text("No hay elementos para mostrar", color = Color.Gray)
            val minW = if (vm.section == Section.LIVE) (if (isTv) 150.dp else 120.dp) else (if (isTv) 126.dp else 100.dp)
            LazyVerticalGrid(
                state = vm.gridState,
                columns = GridCells.Adaptive(minW),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(4.dp),
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
            RowsView(vm, rows, hero, isTv, Modifier.weight(1f))
        }
    }

    AppDialogs(vm, isTv, dlg) { dlg = it }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroItem(vm: AppVM, item: Item, isTv: Boolean) {
    val d = vm.heroDetail
    HeroBanner(d?.image ?: item.icon, if (isTv) 280.dp else 220.dp) {
        Text(
            item.name, color = Color.White, fontSize = if (isTv) 30.sp else 22.sp,
            fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        val meta = listOf(
            d?.year.orEmpty(),
            d?.genre.orEmpty(),
            if (d != null && d.rating.isNotBlank() && d.rating != "0") "★ ${d.rating}" else ""
        ).filter { it.isNotBlank() }.joinToString("   •   ")
        if (meta.isNotBlank()) Text(meta, color = Nf.dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val plot = d?.plot.orEmpty()
        if (plot.isNotBlank()) {
            Text(plot, color = Color(0xFFE0E0E0), fontSize = 13.sp, maxLines = if (isTv) 3 else 2, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NfButton("Reproducir", primary = true) { vm.playNow(item) }
            NfButton("Más información") { vm.open(item) }
            NfButton(if (vm.isFav(item)) "En Mi lista" else "Mi lista") { vm.toggleFav(item) }
        }
    }
}

@Composable
private fun RowsView(vm: AppVM, rows: List<CatRow>, hero: Item?, isTv: Boolean, modifier: Modifier) {
    LazyColumn(
        state = vm.rowsState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (hero != null) {
            item(key = "hero") { HeroItem(vm, hero, isTv) }
        }
        items(rows, key = { it.id }) { r ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Entrar a la categoría completa
                FocusBox(onClick = { vm.openCategory(r.id) }) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(r.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Ver todo ›  ${r.items.size}", color = Nf.dim, fontSize = 12.sp)
                    }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
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
                                NfButton("Ver todo ›") { vm.openCategory(r.id) }
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
    FocusBox(modifier, onClick, onLongClick, scaleOnFocus = true) {
        Column(
            Modifier.fillMaxWidth().background(Nf.card).padding(3.dp),
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
                    .background(Color(0xFF181818))
            )
            Text(
                item.name, color = Color(0xFFE5E5E5), fontSize = 11.sp, lineHeight = 13.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp).fillMaxWidth().height(28.dp)
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

/* ---------- Búsqueda en Live, Películas y Series a la vez ---------- */

@Composable
fun SearchScreen(vm: AppVM) {
    BackHandler { vm.closeSearch() }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val res = vm.searchResults

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("← Volver") { vm.closeSearch() }
            Text("Buscar en todo", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.searchQuery, onValueChange = { vm.searchQuery = it },
                label = { Text("Película, serie o canal") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
                modifier = Modifier.weight(1f)
            )
            Chip("Buscar") { vm.runSearch() }
        }
        if (vm.searchBusy) CircularProgressIndicator()
        vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }
        if (res.isEmpty() && !vm.searchBusy) {
            Text(
                "Escribe lo que buscas (mínimo 2 letras) y pulsa Buscar. Se busca en Live, Películas y Series.",
                color = Color.Gray, fontSize = 12.sp
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section.values().forEach { sec ->
                val list = res.filter { it.section == sec }
                if (list.isNotEmpty()) {
                    item(key = sec.name) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${sec.label}   (${list.size})", color = Color.White,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                            ) {
                                items(list, key = { it.id }) { item ->
                                    SmallCard(
                                        item, Modifier.width(cardWidth(item, wide)), vm.isFav(item),
                                        { vm.open(item, list) }, { vm.toggleFav(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------- Ficha de película o serie (estilo catálogo) ---------- */

private fun seasonLabel(n: Int) = if (n == 0) "Especiales" else "Temporada $n"

@Composable
private fun Poster(url: String?, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(0.67f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF181818))
    )
}

@Composable
private fun TitleBlock(item: Item, d: Detail?) {
    Text(
        item.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black,
        maxLines = 3, overflow = TextOverflow.Ellipsis
    )
    if (d == null) {
        CircularProgressIndicator(color = Nf.red)
    } else {
        val meta = listOf(
            d.year,
            d.genre,
            if (d.rating.isNotBlank() && d.rating != "0") "★ ${d.rating}" else "",
            d.duration
        ).filter { it.isNotBlank() }.joinToString("   •   ")
        if (meta.isNotBlank()) Text(meta, color = Nf.dim, fontSize = 13.sp)
    }
}

@Composable
private fun PlotBlock(d: Detail?, plotLines: Int) {
    if (d == null) return
    if (d.plot.isNotBlank()) {
        Text(d.plot, color = Color(0xFFE0E0E0), fontSize = 13.sp, maxLines = plotLines, overflow = TextOverflow.Ellipsis)
    }
    if (d.director.isNotBlank()) {
        Text("Director: ${d.director}", color = Nf.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (d.cast.isNotBlank()) {
        Text("Reparto: ${d.cast}", color = Nf.dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EpisodeRow(e: Episode, seriesName: String, inProgress: Boolean, onClick: () -> Unit) {
    // Muchos proveedores ponen el nombre de la serie como título de cada episodio
    val title = if (e.title.isBlank() || e.title.equals(seriesName, ignoreCase = true)) "Episodio ${e.num}" else e.title
    FocusBox(Modifier.fillMaxWidth(), onClick) {
        Row(
            Modifier.fillMaxWidth().background(Nf.card).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${e.num}", color = Nf.dim, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
            AsyncImage(
                model = e.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(140.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF181818))
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                val sub = listOf(e.duration, if (inProgress) "En curso" else "").filter { it.isNotBlank() }.joinToString("   •   ")
                if (sub.isNotBlank()) Text(sub, color = Nf.dim, fontSize = 12.sp)
                if (e.plot.isNotBlank()) {
                    Text(e.plot, color = Color.Gray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SeasonDialog(
    seasons: List<Int>,
    counts: Map<Int, Int>,
    current: Int,
    isTv: Boolean,
    onPick: (Int) -> Unit,
    onClose: () -> Unit
) {
    DialogShell(isTv, onClose) {
        Text("${seasons.size} temporadas", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        seasons.forEach { s ->
            FocusBox(Modifier.fillMaxWidth(), { onPick(s) }) {
                Text(
                    "${seasonLabel(s)}   •   ${counts[s] ?: 0} episodios",
                    color = Color.White, fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (s == current) Nf.red else Nf.chip)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
        Chip("Cerrar") { onClose() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailButtons(
    vm: AppVM,
    item: Item,
    isSeries: Boolean,
    seasonEps: List<Episode>,
    resumeEp: Episode?,
    resumeMovie: Boolean,
    first: FocusRequester
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!isSeries) {
            NfButton(if (resumeMovie) "Continuar" else "Reproducir", true, first) { vm.playDetailMovie() }
        } else if (seasonEps.isNotEmpty()) {
            val ep = resumeEp ?: seasonEps.first()
            NfButton(
                (if (resumeEp != null) "Continuar" else "Reproducir") + " T${ep.season} E${ep.num}",
                true, first
            ) { vm.playEpisode(ep) }
        }
        NfButton(if (vm.isFav(item)) "En Mi lista (quitar)" else "Agregar a Mi lista") { vm.toggleFav(item) }
        NfButton("← Volver") { vm.closeDetail() }
    }
}

@Composable
fun DetailScreen(vm: AppVM) {
    BackHandler { vm.closeDetail() }
    val item = vm.detailItem ?: return
    val d = vm.detail
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val narrow = LocalConfiguration.current.screenWidthDp < 600
    val isSeries = item.section == Section.SERIES
    var showSeasons by remember { mutableStateOf(false) }

    val seasons = remember(vm.episodes) { vm.episodes.map { it.season }.distinct().sorted() }
    val counts = remember(vm.episodes) { vm.episodes.groupingBy { it.season }.eachCount() }
    val seasonEps = remember(vm.episodes, vm.season) { vm.episodes.filter { it.season == vm.season } }
    val resumeEp = remember(seasonEps) { seasonEps.firstOrNull { prefs.position(it.url) > 0L } }
    val resumeMovie = remember(item.id) { !isSeries && prefs.position(vm.urlOf(item)) > 0L }

    val first = remember { FocusRequester() }
    LaunchedEffect(item.id) {
        delay(300)
        runCatching { first.requestFocus() }
    }

    // Toda la ficha se desplaza junta: en el celular los episodios no quedan escondidos
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (narrow) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Poster(d?.image ?: item.icon, Modifier.width(100.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TitleBlock(item, d)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { PlotBlock(d, 6) }
            }
            item { DetailButtons(vm, item, isSeries, seasonEps, resumeEp, resumeMovie, first) }
        } else {
            // En pantallas grandes: banner con la carátula fundida con el fondo
            item {
                HeroBanner(d?.image ?: item.icon, 300.dp) {
                    TitleBlock(item, d)
                    PlotBlock(d, 4)
                    DetailButtons(vm, item, isSeries, seasonEps, resumeEp, resumeMovie, first)
                }
            }
        }
        if (isSeries) {
            item {
                if (vm.episodes.isEmpty()) {
                    val err = vm.error
                    if (err != null) Text(err, color = Color(0xFFFF8A80)) else CircularProgressIndicator(color = Nf.red)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Un solo botón: muestra la temporada actual y al tocarlo lista todas las temporadas
                        NfButton(seasonLabel(vm.season)) { showSeasons = true }
                        Text(
                            "${seasons.size} temporadas   •   ${seasonEps.size} episodios",
                            color = Nf.dim, fontSize = 13.sp
                        )
                    }
                }
            }
            items(seasonEps, key = { it.id }) { e ->
                EpisodeRow(e, item.name, prefs.position(e.url) > 0L) { vm.playEpisode(e) }
            }
        }
    }

    if (showSeasons) {
        SeasonDialog(seasons, counts, vm.season, !narrow, { vm.season = it; showSeasons = false }) {
            showSeasons = false
        }
    }
}

/* ---------- Diálogos: menú, ajustes, control parental, guía y diagnóstico ---------- */

@Composable
fun AppDialogs(vm: AppVM, isTv: Boolean, dlg: Dlg, onChange: (Dlg) -> Unit) {
    when (dlg) {
        Dlg.NONE -> {}
        Dlg.MENU -> MenuDialog(vm, isTv, onChange)
        Dlg.SETTINGS -> SettingsDialog(vm, isTv) { onChange(Dlg.NONE) }
        Dlg.PIN -> PinDialog(vm, isTv) { onChange(Dlg.NONE) }
        Dlg.DIAG -> DiagDialog(isTv) { onChange(Dlg.NONE) }
        Dlg.GUIDE -> GuideDialog(vm, isTv) { onChange(Dlg.NONE) }
    }
}

@Composable
fun DialogShell(isTv: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF181818),
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
            Section.values().forEach { s -> Chip(s.label, vm.inSection && vm.section == s) { vm.select(s); close() } }
        }

        if (vm.inSection) {
            OptionRow("Ordenar por", SortMode.values().toList(), vm.sort, { it.label }) {
                vm.changeSort(it)
                close()
            }

            Text("Buscar en este apartado", color = Color.LightGray)
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                placeholder = { Text("Nombre de película, serie o canal…") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }

        Text("Más opciones", color = Color.LightGray)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (vm.inSection) {
                Chip("Inicio") { vm.goHome(); close() }
                Chip("Mi lista") { vm.openCategory(FAV); close() }
                Chip("Actualizar lista") { vm.refresh(); close() }
            }
            Chip("Buscar en todo") { vm.openSearch(); close() }
            Chip("Control parental") { onChange(Dlg.PIN) }
            Chip("Ajustes del reproductor") { onChange(Dlg.SETTINGS) }
            Chip("Diagnóstico del equipo") { onChange(Dlg.DIAG) }
            Chip("Usuarios / cerrar sesión") { vm.logout(); close() }
            Chip("Cerrar menú") { close() }
        }

        Text(
            if (isTv) "Para agregar a Mi lista: mantén pulsado OK o pulsa MENÚ sobre el elemento"
            else "Para agregar a Mi lista: mantén pulsado el elemento",
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
        OptionRow(
            "Ajuste automático de fotogramas (la TV cambia a 24, 25, 30 o 60 Hz según el video)",
            listOf(true, false), vm.opts.frameRate, { if (it) "Activado" else "Desactivado" }
        ) {
            vm.updateOpts(vm.opts.copy(frameRate = it))
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

@Composable
private fun GuideDialog(vm: AppVM, isTv: Boolean, onClose: () -> Unit) {
    DialogShell(isTv, onClose) {
        Text(
            "Guía: ${vm.liveSel?.name ?: ""}", color = Color.White, fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        val lines = vm.guideLines
        if (lines == null) {
            CircularProgressIndicator()
        } else if (lines.isEmpty()) {
            Text("Este canal no tiene guía de programación.", color = Color.Gray)
        } else {
            lines.forEach { line ->
                Text(
                    line,
                    color = if (line.startsWith("AHORA")) Color.White else Color.LightGray,
                    fontSize = 14.sp
                )
            }
        }
        Chip("Cerrar") { onClose() }
    }
}
