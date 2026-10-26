package com.example.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Paleta de la app (negro y rojo, como las plataformas de streaming). */
object Nf {
    val bg = Color(0xFF0B0B0B)
    val card = Color(0xFF1F1F1F)
    val chip = Color(0xFF2B2B2B)
    val red = Color(0xFFE50914)
    val dim = Color(0xFFB3B3B3)
}

/** Botón grande: el principal es blanco con letras negras; el secundario, gris translúcido. */
@Composable
fun NfButton(
    text: String,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    FocusBox(onClick = onClick, focusRequester = focusRequester) {
        Text(
            text,
            color = if (primary) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(if (primary) Color.White else Color(0x99555555))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun NavTab(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                color = if (selected) Color.White else Nf.dim,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .height(3.dp)
                    .width(if (selected) 24.dp else 0.dp)
                    .background(Nf.red)
            )
        }
    }
}

/**
 * Barra superior: logo y pestañas Inicio / Live / Películas / Series, y a la derecha Buscar y Menú.
 * [current] es el apartado abierto (null = pantalla de inicio).
 */
@Composable
fun NavBar(vm: AppVM, current: Section?, onMenu: () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 700
    val rowModifier = if (wide) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "IPTV", color = Nf.red, fontSize = 26.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.padding(end = 10.dp)
        )
        NavTab("Inicio", current == null) { vm.goHome() }
        Section.values().forEach { s -> NavTab(s.label, current == s) { vm.select(s) } }
        if (wide) Spacer(Modifier.weight(1f)) else Spacer(Modifier.width(12.dp))
        NavTab("Buscar", false) { vm.openSearch() }
        NavTab("Menú", false, onMenu)
    }
}

/** Banner grande: carátula a la derecha que se funde con el fondo y el contenido a la izquierda. */
@Composable
fun HeroBanner(imageUrl: String?, height: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Nf.card)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.6f)
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Nf.bg, Color(0xE60B0B0B), Color(0x990B0B0B), Color(0x1A0B0B0B))
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.62f)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}
