package com.musicpaper.app

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MusicPaperTheme {
                Surface(Modifier.fillMaxSize()) { MainScreen(tick) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (granted && MusicListenerService.instance == null) rebindListener(this)
        tick++
    }
}

@Composable
fun MusicPaperTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(tick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var enabled by remember { mutableStateOf(prefs.enabled) }
    var apps by remember { mutableStateOf(prefs.apps) }
    var target by remember { mutableIntStateOf(prefs.target) }
    var effect by remember { mutableStateOf(prefs.effect) }
    var blur by remember { mutableFloatStateOf(prefs.blur.toFloat()) }
    var darken by remember { mutableFloatStateOf(prefs.darken.toFloat()) }
    var showArt by remember { mutableStateOf(prefs.showArt) }
    var artSize by remember { mutableFloatStateOf(prefs.artSize.toFloat()) }
    var artX by remember { mutableFloatStateOf(prefs.artX.toFloat()) }
    var artY by remember { mutableFloatStateOf(prefs.artY.toFloat()) }
    var delay by remember { mutableFloatStateOf(prefs.restoreDelay.toFloat()) }
    var hasRestore by remember { mutableStateOf(WallpaperApplier.hasRestoreImage(ctx)) }
    var showPicker by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(prefs.showHelp) }
    var bgBlur by remember { mutableStateOf(prefs.bgBlur) }
    var refresh by remember { mutableIntStateOf(0) }

    val listenerOn = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val batteryOk = remember(tick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    val liveOn = remember(tick) { LiveCdWallpaperService.isActive(ctx) }
    val svcOn = remember(tick, refresh) { MusicListenerService.instance != null }
    val dListener = remember(tick, refresh) { prefs.getDiag("listener") }
    val dTrack = remember(tick, refresh) { prefs.getDiag("track") }
    val dArt = remember(tick, refresh) { prefs.getDiag("art") }
    val dResult = remember(tick, refresh) { prefs.getDiag("result") }

    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) hasRestore = WallpaperApplier.saveRestoreImage(ctx, uri)
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MusicPaper", style = MaterialTheme.typography.headlineMedium)
        Text("La carátula de tu música como fondo de pantalla.")

        if (!listenerOn) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Primero: activa el permiso", style = MaterialTheme.typography.titleMedium)
                    Text("MusicPaper solo mira qué canción suena en tus apps de música. Android lo llama «acceso a notificaciones».")
                    if (Build.VERSION.SDK_INT >= 33) {
                        Text(
                            "Si el interruptor sale gris o dice «ajustes restringidos», es normal en apps instaladas fuera de la tienda. No es un virus. Haz esto:"
                        )
                        Text(
                            "1. Toca «Abrir Info de la app».\n" +
                                "2. Toca ⋮ (arriba a la derecha) y elige «Permitir ajustes restringidos».\n" +
                                "3. Vuelve aquí y toca «Activar acceso a notificaciones»."
                        )
                        Text(
                            "¿No ves el ⋮? Toca primero «Activar acceso a notificaciones», acepta el aviso y repite el paso 1.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { openAppDetails(ctx) }, modifier = Modifier.fillMaxWidth()) {
                            Text("1. Abrir Info de la app")
                        }
                    }
                    Button(onClick = { openListenerSettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (Build.VERSION.SDK_INT >= 33) "3. Activar acceso a notificaciones" else "Activar acceso a notificaciones")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permisos", style = MaterialTheme.typography.titleMedium)
                PermissionRow("Acceso a notificaciones", listenerOn) { openListenerSettings(ctx) }
                PermissionRow("Sin ahorro de batería", batteryOk) { requestIgnoreBattery(ctx) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Activar fondo con música", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it; prefs.enabled = it })
                }

                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (apps.isEmpty()) "Apps de música: todas" else "Apps de música: ${apps.size} elegidas")
                }

                Text("Aplicar en")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Ambas" to Prefs.TARGET_BOTH, "Bloqueo" to Prefs.TARGET_LOCK, "Inicio" to Prefs.TARGET_HOME)
                        .forEach { (label, value) ->
                            FilterChip(
                                selected = target == value,
                                onClick = { target = value; prefs.target = value },
                                label = { Text(label) }
                            )
                        }
                }

                Text("Efecto")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Blur" to Prefs.EFFECT_BLUR,
                        "Cover" to Prefs.EFFECT_COVER,
                        "CD" to Prefs.EFFECT_CD,
                        "Cuadrado" to Prefs.EFFECT_SQUARE,
                        "Glass Card" to Prefs.EFFECT_GLASS,
                        "CD giratorio" to Prefs.EFFECT_CD_SPIN
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = effect == value,
                            onClick = { effect = value; prefs.effect = value },
                            label = { Text(label) }
                        )
                    }
                }

                val hasBgChoice = effect == Prefs.EFFECT_CD || effect == Prefs.EFFECT_SQUARE || effect == Prefs.EFFECT_CD_SPIN
                val usesBlur = effect == Prefs.EFFECT_BLUR || effect == Prefs.EFFECT_GLASS || (hasBgChoice && bgBlur)
                val hasObject = effect != Prefs.EFFECT_COVER && (effect != Prefs.EFFECT_BLUR || showArt)
                val circle = effect == Prefs.EFFECT_CD || effect == Prefs.EFFECT_CD_SPIN

                if (hasBgChoice) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fondo desenfocado", Modifier.weight(1f))
                        Switch(checked = bgBlur, onCheckedChange = { bgBlur = it; prefs.bgBlur = it })
                    }
                }

                if (usesBlur) {
                    LabeledSlider("Desenfoque", blur, 0f..100f, { blur = it }) { prefs.blur = blur.toInt() }
                }
                if (effect == Prefs.EFFECT_BLUR) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mostrar álbum nítido encima", Modifier.weight(1f))
                        Switch(checked = showArt, onCheckedChange = { showArt = it; prefs.showArt = it })
                    }
                }
                if (hasObject) {
                    LabeledSlider("Tamaño (%)", artSize, 30f..100f, { artSize = it }) {
                        prefs.artSize = artSize.toInt()
                    }
                    Text("Arrastra el cuadro para colocarlo", style = MaterialTheme.typography.bodySmall)
                    ArtPositionPreview(
                        x = artX,
                        y = artY,
                        size = artSize,
                        circle = circle,
                        onMove = { nx, ny -> artX = nx; artY = ny },
                        onDone = { prefs.artX = artX.toInt(); prefs.artY = artY.toInt() }
                    )
                    TextButton(onClick = {
                        artX = 50f; artY = 50f
                        prefs.artX = 50; prefs.artY = 50
                    }) { Text("Centrar") }
                }

                if (effect == Prefs.EFFECT_CD_SPIN) {
                    Text("CD giratorio usa un papel tapiz animado.", style = MaterialTheme.typography.titleSmall)
                    if (liveOn) {
                        Text("✓ Papel tapiz animado activo. El CD gira mientras suena la música.")
                    } else {
                        Text("El papel tapiz animado no está activo: el CD no se verá hasta activarlo.")
                        Button(onClick = { openLiveWallpaper(ctx) }) { Text("Activar papel tapiz animado") }
                    }
                    Text(
                        "Con este efecto «Aplicar en» no cuenta: tu teléfono decide si va en bloqueo, inicio o ambas al activarlo.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (effect == Prefs.EFFECT_BLUR || effect == Prefs.EFFECT_COVER || effect == Prefs.EFFECT_GLASS || (hasBgChoice && bgBlur)) {
                    LabeledSlider("Oscurecer", darken, 0f..100f, { darken = it }) { prefs.darken = darken.toInt() }
                }
                LabeledSlider("Restaurar tras pausa (s)", delay, 0f..60f, { delay = it }) {
                    prefs.restoreDelay = delay.toInt()
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fondo al parar la música", style = MaterialTheme.typography.titleMedium)
                Text(if (hasRestore) "Imagen elegida" else "Sin imagen: se vuelve al fondo predeterminado del sistema")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pickImage.launch("image/*") }) { Text("Elegir imagen") }
                    if (hasRestore) {
                        TextButton(onClick = {
                            WallpaperApplier.clearRestoreImage(ctx)
                            hasRestore = false
                        }) { Text("Quitar") }
                    }
                }
            }
        }

        Button(
            onClick = {
                val svc = MusicListenerService.instance
                if (svc == null) {
                    Toast.makeText(ctx, "Activa primero el acceso a notificaciones", Toast.LENGTH_LONG).show()
                } else {
                    svc.reapply()
                    Toast.makeText(ctx, "Aplicando…", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Aplicar ahora") }

        Text(
            "Reproduce una canción y pulsa «Aplicar ahora» para ver los cambios. Código abierto (MIT).",
            style = MaterialTheme.typography.bodySmall
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Estado", style = MaterialTheme.typography.titleMedium)
                Text("Servicio: " + if (svcOn) "conectado ✓" else "no conectado ✗")
                if (dListener.isNotEmpty()) Text("Servicio (último aviso): $dListener", style = MaterialTheme.typography.bodySmall)
                Text("Canción: " + dTrack.ifEmpty { "—" })
                Text("Carátula: " + dArt.ifEmpty { "—" })
                Text("Resultado: " + dResult.ifEmpty { "—" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { rebindListener(ctx); refresh++ }) { Text("Reconectar") }
                    TextButton(onClick = { refresh++ }) { Text("Actualizar") }
                }
                Text(
                    "Si dice «no conectado», pulsa Reconectar. Si dice «sin carátula», esa app no entrega la imagen.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ayuda con los ajustes del teléfono (opcional)", Modifier.weight(1f))
                    Switch(checked = showHelp, onCheckedChange = { showHelp = it; prefs.showHelp = it })
                }
                if (showHelp) {
                    Text(
                        "Algunos teléfonos (Tecno, Honor, Xiaomi…) tienen funciones propias que bloquean el fondo de bloqueo o cierran la app. Abre estas pantallas y desactiva lo que estorbe:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { openAppDetails(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Info de la app (ajustes restringidos)")
                    }
                    OutlinedButton(onClick = { openListenerSettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Acceso a notificaciones")
                    }
                    OutlinedButton(
                        onClick = { startSafely(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ahorro de batería") }
                    OutlinedButton(
                        onClick = { startSafely(ctx, Intent(Settings.ACTION_DISPLAY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Pantalla y fondos") }
                    OutlinedButton(
                        onClick = { startSafely(ctx, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), null)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Fondos de pantalla del sistema") }
                    Text(
                        "• Honor / Huawei: desactiva «Magazine Unlock» y usa un fondo de bloqueo normal.\n" +
                            "• Tecno / Infinix / itel: permite el inicio automático (Phone Master o Administrador del teléfono), quita el ahorro de batería a MusicPaper y evita temas o fondos de bloqueo propios.\n" +
                            "• Xiaomi / Redmi / POCO: activa «Inicio automático» y pon «Sin restricciones» en el ahorro de batería.\n" +
                            "Los nombres cambian según el teléfono.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Text("MusicPaper v$version", style = MaterialTheme.typography.bodySmall)
    }

    if (showPicker) {
        AppPickerDialog(
            selected = apps,
            onDismiss = { showPicker = false },
            onSave = { apps = it; prefs.apps = it; showPicker = false }
        )
    }
}

@Composable
private fun ArtPositionPreview(
    x: Float,
    y: Float,
    size: Float,
    circle: Boolean,
    onMove: (Float, Float) -> Unit,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val dm = ctx.resources.displayMetrics
    val ratio = dm.heightPixels.toFloat() / dm.widthPixels.toFloat()
    val pw = 180.dp
    val pwPx = with(density) { pw.toPx() }
    val phPx = pwPx * ratio
    val ph = with(density) { phPx.toDp() }
    val curX by rememberUpdatedState(x)
    val curY by rememberUpdatedState(y)
    val side = pwPx * size / 100f
    val left = (x / 100f * pwPx - side / 2f).coerceIn(0f, (pwPx - side).coerceAtLeast(0f))
    val top = (y / 100f * phPx - side / 2f).coerceIn(0f, (phPx - side).coerceAtLeast(0f))

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(pw, ph)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, drag ->
                        change.consume()
                        onMove(
                            (curX + drag.x / pwPx * 100f).coerceIn(0f, 100f),
                            (curY + drag.y / phPx * 100f).coerceIn(0f, 100f)
                        )
                    }
                }
        ) {
            Box(
                Modifier
                    .offset { IntOffset(left.toInt(), top.toInt()) }
                    .size(with(density) { side.toDp() })
                    .clip(if (circle) CircleShape else RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Text("♪", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (ok) Text("✓ Listo") else Button(onClick = onClick) { Text("Activar") }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit
) {
    Column {
        Text("$label: ${value.toInt()}")
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range)
    }
}

private class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap)

@Composable
private fun AppPickerDialog(selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<AppInfo>?>(null) }
    var sel by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { ri ->
                    AppInfo(
                        ri.activityInfo.packageName,
                        ri.loadLabel(pm).toString(),
                        ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                    )
                }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apps de música") },
        text = {
            Column {
                Text("Sin ninguna elegida se usan todas.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val all = list
                if (all == null) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else {
                    val shown = all.filter { it.label.contains(query, ignoreCase = true) }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(shown, key = { it.pkg }) { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { sel = if (app.pkg in sel) sel - app.pkg else sel + app.pkg }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(app.label, Modifier.weight(1f))
                                Checkbox(checked = app.pkg in sel, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(sel) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun openListenerSettings(ctx: Context) {
    val detail = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(ctx, MusicListenerService::class.java).flattenToString()
        )
    } else null
    try {
        ctx.startActivity(detail ?: Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

private fun requestIgnoreBattery(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        )
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun startSafely(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, "Tu teléfono no permite abrir esa pantalla", Toast.LENGTH_SHORT).show()
    }
}

private fun openAppDetails(ctx: Context) {
    startSafely(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}

private fun rebindListener(ctx: Context) {
    try {
        NotificationListenerService.requestRebind(ComponentName(ctx, MusicListenerService::class.java))
    } catch (e: Exception) {
        // si no hay permiso todavía, no hay nada que reconectar
    }
}

private fun openLiveWallpaper(ctx: Context) {
    val cn = ComponentName(ctx, LiveCdWallpaperService::class.java)
    try {
        ctx.startActivity(
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, cn)
        )
    } catch (e: Exception) {
        startSafely(ctx, Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    }
}
