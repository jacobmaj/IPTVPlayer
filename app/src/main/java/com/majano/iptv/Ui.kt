@file:OptIn(ExperimentalMaterial3Api::class)

package com.majano.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

val Bg = Color(0xFF090B10)
val Panel = Color(0xFF151923)
val Accent = Color(0xFFE50914)
val MainText = Color(0xFFF5F6F8)
val Muted = Color(0xFF9CA3AF)
private val ErrorColor = Color(0xFFFF6B6B)

enum class Section(val title: String, val short: String) {
    LIVE("TV en vivo", "TV"),
    MOVIES("Películas", "Películas"),
    SERIES("Series", "Series"),
    FAVORITES("Favoritos", "Favoritos"),
    ACCOUNT("Cuenta", "Cuenta"),
}

@Composable
fun IPTVRoot(model: AppModel, onImmersive: (Boolean) -> Unit) {
    LaunchedEffect(model.fullscreen) { onImmersive(model.fullscreen) }
    BackHandler(enabled = model.fullscreen) { model.fullscreen = false }
    if (model.loggedIn) MainScreen(model) else LoginScreen(model)
}

// ───────────────────────── Login ─────────────────────────

@Composable
fun LoginScreen(model: AppModel) {
    var link by remember { mutableStateOf(model.savedLink) }
    var user by remember { mutableStateOf(model.savedUser) }
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val canSubmit = !model.busy && link.isNotBlank()
    val submit = { if (canSubmit) model.login(link, user, pass) }

    Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("MAJANO", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("IPTV", color = MainText, fontSize = 14.sp, letterSpacing = 6.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = link, onValueChange = { link = it },
                label = { Text("Link del servidor") },
                placeholder = { Text("http://servidor.com:8080") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Usuario") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Contraseña") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Ocultar" else "Ver", color = Muted, fontSize = 12.sp) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Button(onClick = { submit() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (model.busy) "Conectando…" else "Entrar", fontSize = 16.sp)
            }
            if (model.busy) CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
            if (model.message.isNotBlank()) {
                Text(model.message, color = if (model.messageIsError) ErrorColor else Muted, fontSize = 13.sp)
            }
            Text("Usa únicamente datos de un servicio al que tengas acceso.", color = Muted, fontSize = 11.sp)
        }
    }
}

// ───────────────────────── Pantalla principal ─────────────────────────

@Composable
fun MainScreen(model: AppModel) {
    var section by remember { mutableStateOf(Section.LIVE) }
    val select: (Section) -> Unit = { s -> model.closeSeries(); section = s }

    if (model.fullscreen && model.hasMedia) {
        PlayerBox(model, Modifier.fillMaxSize())
    } else {
        BoxWithConstraints(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
            val wide = maxWidth >= 720.dp
            if (wide) {
                Row(Modifier.fillMaxSize().padding(16.dp)) {
                    SideNav(section, select)
                    Spacer(Modifier.width(16.dp))
                    if (section == Section.ACCOUNT) {
                        AccountPanel(model, Modifier.weight(1f).fillMaxHeight())
                    } else {
                        Browser(model, section, Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(16.dp))
                        PlayerPane(model, Modifier.weight(1.3f).fillMaxHeight())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (model.hasMedia && section != Section.ACCOUNT) PlayerPhone(model)
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (section == Section.ACCOUNT) AccountPanel(model, Modifier.fillMaxSize())
                        else Browser(model, section, Modifier.fillMaxSize())
                    }
                    BottomNav(section, select)
                }
            }
        }
    }
}

@Composable
private fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color(0xFF29202A) else Panel,
        border = if (focused) BorderStroke(2.dp, Accent) else if (selected) BorderStroke(1.dp, Accent.copy(alpha = 0.6f)) else null,
        interactionSource = interaction,
        content = content,
    )
}

@Composable
private fun SideNav(current: Section, onSelect: (Section) -> Unit) {
    Column(Modifier.width(190.dp).fillMaxHeight()) {
        Text("MAJANO", color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("IPTV", color = MainText, fontSize = 12.sp, letterSpacing = 4.sp)
        Spacer(Modifier.height(20.dp))
        Section.values().forEach { s ->
            Focusable(onClick = { onSelect(s) }, selected = s == current, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    s.title, color = if (s == current) MainText else Muted, fontSize = 14.sp,
                    fontWeight = if (s == current) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun BottomNav(current: Section, onSelect: (Section) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 4.dp, vertical = 4.dp)) {
        Section.values().forEach { s ->
            Focusable(onClick = { onSelect(s) }, selected = s == current, modifier = Modifier.weight(1f).padding(2.dp)) {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(s.short, color = if (s == current) MainText else Muted, fontSize = 11.sp, maxLines = 1,
                        fontWeight = if (s == current) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

// ───────────────────────── Reproductor ─────────────────────────

@Composable
fun PlayerBox(model: AppModel, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = model.player; useController = true } },
            modifier = Modifier.fillMaxSize(),
        )
        if (model.isBuffering && model.playerError.isBlank()) CircularProgressIndicator(color = Accent)
        if (!model.hasMedia) Text("Elige un canal, película o serie", color = Muted, fontSize = 13.sp)
        if (model.playerError.isNotBlank()) {
            Text(model.playerError, color = ErrorColor, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun PlayerPane(model: AppModel, modifier: Modifier) {
    Column(modifier) {
        PlayerBox(model, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.nowTitle.ifBlank { "Vista previa" }, color = MainText, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (model.hasMedia) OutlinedButton(onClick = { model.fullscreen = true }) { Text("⛶ Pantalla completa") }
        }
    }
}

@Composable
private fun PlayerPhone(model: AppModel) {
    Column {
        PlayerBox(model, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(model.nowTitle, color = MainText, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { model.fullscreen = true }) { Text("⛶ Completa", color = Accent) }
        }
    }
}

// ───────────────────────── Catálogo ─────────────────────────

@Composable
private fun Browser(model: AppModel, section: Section, modifier: Modifier) {
    val kind: Kind? = when (section) {
        Section.LIVE -> Kind.LIVE
        Section.MOVIES -> Kind.MOVIE
        Section.SERIES -> Kind.SERIES
        else -> null
    }

    LaunchedEffect(section) {
        if (kind != null) model.loadKind(kind)
        else Kind.values().forEach { model.loadKind(it) }
    }

    BackHandler(enabled = model.openedSeries != null && !model.fullscreen) { model.closeSeries() }

    if (model.openedSeries != null) {
        SeriesDetail(model, modifier)
        return
    }

    var query by remember(section) { mutableStateOf("") }
    var catId by remember(section) { mutableStateOf<String?>(null) }

    val loading = if (kind != null) model.isLoading(kind) else Kind.values().any { model.isLoading(it) }
    val cats: List<Category> = if (kind != null) model.cats[kind].orEmpty() else emptyList()
    val liveItems = model.items[Kind.LIVE]
    val movieItems = model.items[Kind.MOVIE]
    val seriesItems = model.items[Kind.SERIES]
    val favKeys = model.favorites.toList()

    val base: List<Entry> = remember(section, liveItems, movieItems, seriesItems, favKeys) {
        when (kind) {
            Kind.LIVE -> liveItems.orEmpty()
            Kind.MOVIE -> movieItems.orEmpty()
            Kind.SERIES -> seriesItems.orEmpty()
            null -> (liveItems.orEmpty() + movieItems.orEmpty() + seriesItems.orEmpty()).filter { favKeys.contains("${it.kind.name}:${it.id}") }
        }
    }
    val filtered: List<Entry> = remember(base, catId, query) {
        base.filter { (catId == null || it.categoryId == catId) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(section.title, color = MainText, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${filtered.size} elementos", color = Muted, fontSize = 12.sp)
            }
            if (kind != null) OutlinedButton(onClick = { model.loadKind(kind, force = true) }, enabled = !loading) { Text("↻ Actualizar") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Buscar") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Search),
        )
        if (cats.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            CategoryPicker(cats, catId) { catId = it }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading && base.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(8.dp))
                    Text("Cargando catálogo del proveedor…", color = Muted, fontSize = 12.sp)
                    Text("Con listas grandes puede tardar un momento.", color = Muted, fontSize = 11.sp)
                }
                filtered.isEmpty() -> Text(
                    if (section == Section.FAVORITES) "Aún no tienes favoritos. Toca ☆ en cualquier elemento." else "No hay contenido para mostrar.",
                    color = Muted, modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered) { e ->
                        EntryRow(model, e, selected = model.hasMedia && model.nowTitle == e.name) { model.open(e, filtered) }
                    }
                }
            }
        }
        if (model.message.isNotBlank()) {
            Text(model.message, color = if (model.messageIsError) ErrorColor else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun EntryRow(model: AppModel, e: Entry, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick = onClick, selected = selected, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(e.image, e.kind)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(e.name, color = MainText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                if (e.kind == Kind.SERIES && e.url.isBlank()) Text("Ver temporadas", color = Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { model.toggleFav(e) }) {
                Text(if (model.isFav(e)) "★" else "☆", color = Accent, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun Poster(url: String, kind: Kind) {
    val w = if (kind == Kind.LIVE) 56.dp else 44.dp
    val h = if (kind == Kind.LIVE) 40.dp else 64.dp
    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Color(0xFF222838)), contentAlignment = Alignment.Center) {
        if (url.startsWith("http")) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CategoryPicker(cats: List<Category>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = cats.firstOrNull { it.id == selected }?.name ?: "Todas las categorías"
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label  ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            DropdownMenuItem(text = { Text("Todas las categorías") }, onClick = { onSelect(null); open = false })
            cats.forEach { c ->
                DropdownMenuItem(text = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { onSelect(c.id); open = false })
            }
        }
    }
}

@Composable
private fun SeriesDetail(model: AppModel, modifier: Modifier) {
    val series = model.openedSeries
    if (series == null) return
    val seasons = remember(model.episodes) { model.episodes.map { it.season }.distinct().sorted() }
    var season by remember(model.episodes) { mutableStateOf(seasons.firstOrNull() ?: 1) }
    var open by remember { mutableStateOf(false) }
    val list = model.episodes.filter { it.season == season }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.closeSeries() }) { Text("← Volver", color = Accent) }
            Text(series.name, color = MainText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (seasons.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { open = true }) { Text("Temporada $season  ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    seasons.forEach { s -> DropdownMenuItem(text = { Text("Temporada $s") }, onClick = { season = s; open = false }) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model.episodesLoading) {
                CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(list) { ep ->
                        Focusable(onClick = { model.playUrl("${series.name} · T${ep.season} E${ep.number}", ep.url, false) }, modifier = Modifier.fillMaxWidth()) {
                            Text("E${ep.number}. ${ep.title}", color = MainText, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
        if (model.message.isNotBlank()) {
            Text(model.message, color = if (model.messageIsError) ErrorColor else Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AccountPanel(model: AppModel, modifier: Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(16.dp)).padding(18.dp).verticalScroll(rememberScrollState())) {
        Text("Cuenta", color = MainText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(model.accountInfo.ifBlank { "Conectado" }, color = Muted, fontSize = 13.sp)
        Text("Servidor: ${model.session?.base.orEmpty()}", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { model.logout() }) { Text("Cerrar sesión") }
    }
}
