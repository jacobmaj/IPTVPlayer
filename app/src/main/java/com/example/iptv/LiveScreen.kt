package com.example.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Live para TV: categorías | canales | vista previa pequeña con la guía del canal elegido. */
@Composable
fun LiveScreen(vm: AppVM) {
    var dlg by remember { mutableStateOf(Dlg.NONE) }
    val searching = vm.query.isNotBlank()

    BackHandler { if (searching) vm.query = "" else vm.goHome() }

    // Control parental
    val hide = vm.pinSet && !vm.unlocked
    val adultIds = remember(vm.cats) { vm.cats.filter { isAdult(it.name) }.map { it.id }.toSet() }
    val cats = remember(vm.cats, hide, adultIds) {
        vm.cats.distinctBy { it.id }.filter { !(hide && it.id in adultIds) }
    }
    val base = remember(vm.items, hide, adultIds) {
        if (hide && adultIds.isNotEmpty()) vm.items.filter { it.categoryId !in adultIds } else vm.items
    }
    val sorted = remember(base, vm.sort) { sortItems(base, vm.sort) }
    val recent = remember(base, vm.history, vm.section) { historyItems(vm, base) }
    val channels = remember(sorted, recent, vm.category, vm.query, vm.favs) {
        val cat = vm.category
        val pool = if (cat == RECENT) recent else sorted
        pool.filter {
            val okCat = when (cat) {
                null, RECENT -> true
                FAV -> vm.isFav(it)
                else -> it.categoryId == cat
            }
            okCat && (vm.query.isBlank() || it.name.contains(vm.query, ignoreCase = true))
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    NavBar(vm, Section.LIVE) { dlg = Dlg.MENU }
    Row(
        Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Columna 1: categorías
        LazyColumn(
            Modifier.width(220.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { ListChip("Todos", vm.category == null && !searching) { vm.closeCategory() } }
            item { ListChip("Mi lista", vm.category == FAV) { vm.category = FAV } }
            item { ListChip("Recientes", vm.category == RECENT) { vm.category = RECENT } }
            items(cats, key = { it.id }) { c ->
                ListChip(c.name, vm.category == c.id) { vm.category = c.id }
            }
        }

        // Columna 2: canales
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val head = when {
                searching -> "Resultados: ${vm.query}   (${channels.size})"
                vm.category == FAV -> "Mi lista   (${channels.size})"
                vm.category == RECENT -> "Canales recientes   (${channels.size})"
                vm.category != null -> (cats.firstOrNull { it.id == vm.category }?.name ?: "") + "   (${channels.size})"
                else -> "Todos los canales   (${channels.size})"
            }
            Text(head, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (vm.busy) CircularProgressIndicator()
            vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }
            if (channels.isEmpty() && !vm.busy && vm.error == null) {
                Text("No hay canales para mostrar", color = Color.Gray)
            }
            // Al cambiar de categoría la lista vuelve al inicio
            key(vm.category ?: "all", vm.query) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(channels, key = { it.id }) { ch ->
                        ChannelRow(
                            ch,
                            selected = vm.liveSel?.id == ch.id,
                            fav = vm.isFav(ch),
                            onClick = {
                                if (!vm.previewAllowed) {
                                    // Cuenta de una sola conexión: directo a pantalla completa
                                    vm.liveSel = ch
                                    vm.open(ch, channels)
                                } else if (vm.liveSel?.id == ch.id) {
                                    // OK sobre el canal ya elegido: pantalla completa
                                    vm.fullscreenLive()
                                } else {
                                    // Primer OK: vista previa
                                    vm.selectLive(ch, channels)
                                }
                            },
                            onLongClick = { vm.toggleFav(ch) }
                        )
                    }
                }
            }
        }

        // Columna 3: vista previa y guía
        Column(
            Modifier.width(400.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sel = vm.liveSel
            if (vm.previewAllowed) {
                PreviewPlayer(
                    url = sel?.let { vm.urlOf(it) },
                    opts = vm.opts,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                ) { vm.fullscreenLive() }
            } else {
                Text(
                    "Tu cuenta permite una sola conexión, así que la vista previa está desactivada. " +
                        "Pulsa OK sobre un canal para verlo en pantalla completa.",
                    color = Color.Gray, fontSize = 12.sp
                )
            }
            if (sel != null) {
                Text(sel.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                vm.epgNow?.let { Text(it, color = Color.LightGray, fontSize = 13.sp) }
                Chip("Guía del canal") {
                    vm.loadGuide()
                    dlg = Dlg.GUIDE
                }
            }
            Text(
                "OK sobre el canal elegido o sobre el video: pantalla completa.  CH+ / CH-: cambiar de canal.  " +
                    "Mantén pulsado OK o pulsa MENÚ: agregar a Mi lista.",
                color = Color.Gray, fontSize = 11.sp
            )
        }
    }
    }

    AppDialogs(vm, true, dlg) { dlg = it }
}

@Composable
private fun ListChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(Modifier.fillMaxWidth(), onClick) {
        Text(
            text, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) Color(0xFFE50914) else Color(0xFF2B2B2B))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ChannelRow(ch: Item, selected: Boolean, fav: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusBox(Modifier.fillMaxWidth(), onClick, onLongClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Color(0xFFE50914) else Color(0xFF1F1F1F))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AsyncImage(
                model = ch.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(56.dp).height(36.dp).background(Color(0xFF181818))
            )
            Text(
                ch.name, color = Color.White, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (fav) Text("★", color = Color(0xFFFFC107), fontSize = 16.sp)
        }
    }
}
