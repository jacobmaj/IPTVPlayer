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
    val channels = remember(sorted, vm.category, vm.query, vm.favs) {
        val cat = vm.category
        sorted.filter {
            val okCat = when (cat) {
                null -> true
                FAV -> vm.isFav(it)
                else -> it.categoryId == cat
            }
            okCat && (vm.query.isBlank() || it.name.contains(vm.query, ignoreCase = true))
        }
    }

    Row(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Columna 1: categorías
        LazyColumn(
            Modifier.width(220.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { ListChip("← Inicio", false) { vm.goHome() } }
            item { ListChip("Menú", false) { dlg = Dlg.MENU } }
            item { ListChip("Todos", vm.category == null && !searching) { vm.closeCategory() } }
            item { ListChip("★ Favoritos", vm.category == FAV) { vm.category = FAV } }
            items(cats, key = { it.id }) { c ->
                ListChip(c.name, vm.category == c.id) { vm.category = c.id }
            }
        }

        // Columna 2: canales
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val head = when {
                searching -> "Resultados: ${vm.query}   (${channels.size})"
                vm.category == FAV -> "★ Favoritos   (${channels.size})"
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
                                // Primer OK: vista previa. OK sobre el canal ya elegido: pantalla completa.
                                if (vm.liveSel?.id == ch.id) vm.fullscreenLive() else vm.selectLive(ch, channels)
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
            PreviewPlayer(
                url = sel?.let { vm.urlOf(it) },
                opts = vm.opts,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            ) { vm.fullscreenLive() }
            if (sel != null) {
                Text(sel.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                vm.epgNow?.let { Text(it, color = Color.LightGray, fontSize = 13.sp) }
            }
            Text(
                "OK sobre el canal elegido o sobre el video: pantalla completa.  CH+ / CH-: cambiar de canal.  " +
                    "Mantén pulsado OK o pulsa MENÚ: favorito.",
                color = Color.Gray, fontSize = 11.sp
            )
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
                .background(if (selected) Color(0xFF1E88E5) else Color(0xFF232A35))
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
                .background(if (selected) Color(0xFF1E88E5) else Color(0xFF1A212B))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AsyncImage(
                model = ch.icon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(56.dp).height(36.dp).background(Color(0xFF11161D))
            )
            Text(
                ch.name, color = Color.White, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (fav) Text("★", color = Color(0xFFFFC107), fontSize = 16.sp)
        }
    }
}
