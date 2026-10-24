package com.example.iptv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
        vm.detailItem != null -> DetailScreen(vm)
        !vm.loggedIn -> LoginScreen(vm)
        !vm.inSection -> DashboardScreen(vm, isTv)
        vm.section == Section.LIVE && isTv -> LiveScreen(vm)
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
    focusRequester: FocusRequester? = null,
    content: @Composable BoxScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, Color(0xFF4FC3F7), shape) else Modifier)
            .clip(shape)
            // Tecla MENÚ del control remoto = acción larga (favorito, borrar…)
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
fun Chip(
    text: String,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    FocusBox(onClick = onClick, onLongClick = onLongClick, focusRequester = focusRequester) {
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

/* ---------- Inicio de sesión y usuarios guardados ---------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoginScreen(vm: AppVM) {
    var type by remember { mutableStateOf(vm.saved.type) }
    var server by remember { mutableStateOf(vm.saved.server) }
    var user by remember { mutableStateOf(vm.saved.user) }
    var pass by remember { mutableStateOf(vm.saved.pass) }
    var m3u by remember { mutableStateOf(vm.saved.m3uUrl) }
    var name by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("IPTV Player", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)

            if (vm.profiles.isNotEmpty()) {
                Text("Usuarios guardados", color = Color.LightGray)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vm.profiles.forEach { p ->
                        Chip(profileLabel(p), onLongClick = { vm.deleteProfile(p) }) {
                            vm.login(p.copy(mode = vm.saved.mode))
                        }
                    }
                }
                Text(
                    "Pulsa para entrar. Mantén pulsado (o MENÚ) para borrar un usuario.",
                    color = Color.Gray, fontSize = 11.sp
                )
            }

            Text("Agregar usuario", color = Color.LightGray)
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next)
                )
            } else {
                OutlinedTextField(
                    value = m3u, onValueChange = { m3u = it },
                    label = { Text("URL de la lista M3U / M3U8") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Nombre del usuario (opcional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Text("¿Dónde lo vas a ver?", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceMode.values().forEach { m -> Chip(m.label, vm.saved.mode == m) { vm.setMode(m) } }
            }

            Chip("Conectar") {
                vm.login(Settings(type, server, user, pass, m3u, vm.saved.mode, name.trim()))
            }

            if (vm.busy) CircularProgressIndicator()
            vm.error?.let { Text(it, color = Color(0xFFFF8A80)) }
        }
    }
}
