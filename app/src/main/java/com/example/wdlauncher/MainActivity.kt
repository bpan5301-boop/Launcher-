package com.example.wdlauncher

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ========================= ГЛОБАЛЬНОЕ СОСТОЯНИЕ =========================

class Th(g: Long, c: Long, r: Long) { val g = Color(g); val c = Color(c); val r = Color(r) }
val themes = mapOf(
    "green" to Th(0xFF00FF9C, 0xFF00E5FF, 0xFFFF2D55),
    "red" to Th(0xFFFF2D55, 0xFFFF8A00, 0xFF00E5FF),
    "blue" to Th(0xFF00B7FF, 0xFFB388FF, 0xFFFF2D55),
    "amber" to Th(0xFFFFB300, 0xFFFF6E40, 0xFF00E5FF),
    "purple" to Th(0xFFD66BFF, 0xFF00E5FF, 0xFFFF2D55)
)
var G by mutableStateOf(themes["green"]!!.g)
var C by mutableStateOf(themes["green"]!!.c)
var Warn by mutableStateOf(themes["green"]!!.r)
var glitch by mutableIntStateOf(0)
var launchFlash by mutableStateOf(false)
var launchFlashTick by mutableIntStateOf(0)
var animStyle by mutableStateOf("network")
var hapticsOn by mutableStateOf(true)
var soundsOn by mutableStateOf(true)
var weather by mutableStateOf("НЕТ ДАННЫХ // команда: weather <город>")
val Mono = FontFamily.Monospace
val animNames = listOf("network", "rain", "radar", "city")

object NotifBus { val items = mutableStateListOf<String>() }

object SFX {
    private var tg: ToneGenerator? = null
    fun beep() {
        if (!soundsOn) return
        runCatching {
            if (tg == null) tg = ToneGenerator(AudioManager.STREAM_SYSTEM, 60)
            tg?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
        }
    }
}

class CtosListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ex = sbn.notification.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val line = ("$title: $text").take(60)
        if (NotifBus.items.firstOrNull() != line) {
            NotifBus.items.add(0, line)
            while (NotifBus.items.size > 8) NotifBus.items.removeAt(NotifBus.items.size - 1)
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}

fun wmo(c: Int) = when (c) {
    0 -> "ЯСНО"
    1, 2, 3 -> "ОБЛАЧНО"
    45, 48 -> "ТУМАН"
    in 51..67 -> "ДОЖДЬ"
    in 71..77 -> "СНЕГ"
    in 80..82 -> "ЛИВЕНЬ"
    else -> "ГРОЗА"
}

suspend fun fetchWeather(city: String): String = withContext(Dispatchers.IO) {
    fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + Uri.encode(city)))
    val r = g.getJSONArray("results").getJSONObject(0)
    val w = JSONObject(get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code,wind_speed_10m&latitude=" +
        r.getDouble("latitude") + "&longitude=" + r.getDouble("longitude")))
    val c = w.getJSONObject("current")
    "${r.getString("name").uppercase()}  ${c.getDouble("temperature_2m").roundToInt()}°C  ${wmo(c.getInt("weather_code"))}  ВЕТЕР ${c.getDouble("wind_speed_10m").roundToInt()}км/ч"
}

suspend fun fetchNextEvent(ctx: Context): String? = withContext(Dispatchers.IO) {
    runCatching {
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now)
        ContentUris.appendId(b, now + 7L * 24 * 3600 * 1000)
        val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
        ctx.contentResolver.query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            if (c.moveToFirst()) {
                val title = c.getString(0) ?: "Событие"
                val df = SimpleDateFormat("dd.MM HH:mm", Locale.US)
                "$title — ${df.format(Date(c.getLong(1)))}"
            } else "Событий не найдено"
        }
    }.getOrNull()
}

fun nowPlaying(ctx: Context): MediaController? = runCatching {
    val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    val comp = ComponentName(ctx, CtosListener::class.java)
    msm.getActiveSessions(comp).firstOrNull { it.metadata != null }
}.getOrNull()

fun Modifier.brackets(col: Color, len: Float = 18f) = drawBehind {
    val w = size.width; val h = size.height
    fun l(a: Offset, b: Offset) = drawLine(col, a, b, 3f)
    l(Offset(0f, 0f), Offset(len, 0f)); l(Offset(0f, 0f), Offset(0f, len))
    l(Offset(w, 0f), Offset(w - len, 0f)); l(Offset(w, 0f), Offset(w, len))
    l(Offset(0f, h), Offset(len, h)); l(Offset(0f, h), Offset(0f, h - len))
    l(Offset(w, h), Offset(w - len, h)); l(Offset(w, h), Offset(w, h - len))
}

data class App(val label: String, val pkg: String)
data class Profile(val name: String, val theme: String, val anim: String)
data class Folder(val name: String, val pkgs: List<String>)

fun loadFolders(ctx: Context): List<Folder> = runCatching {
    val arr = JSONArray(prefs(ctx).getString("folders", "[]")!!)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val pk = o.getJSONArray("pkgs")
        Folder(o.getString("name"), (0 until pk.length()).map { pk.getString(it) })
    }
}.getOrDefault(emptyList())

fun saveFolders(ctx: Context, list: List<Folder>) {
    val arr = JSONArray()
    list.forEach { f -> arr.put(JSONObject().put("name", f.name).put("pkgs", JSONArray(f.pkgs))) }
    prefs(ctx).edit().putString("folders", arr.toString()).apply()
}

fun loadApps(ctx: Context): List<App> {
    val pm = ctx.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .filter { it.pkg != ctx.packageName }
        .sortedBy { it.label.lowercase() }
}

fun launch(ctx: Context, pkg: String): Boolean {
    val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
    prefs(ctx).edit().putInt("use_$pkg", prefs(ctx).getInt("use_$pkg", 0) + 1).apply()
    SFX.beep()
    launchFlashTick++
    ctx.startActivity(intent); return true
}

fun go(ctx: Context, i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
fun prefs(ctx: Context) = ctx.getSharedPreferences("wd", Context.MODE_PRIVATE)
fun loadCsv(ctx: Context, key: String) = prefs(ctx).getString(key, "")!!.split(",").filter { it.isNotBlank() }
fun saveCsv(ctx: Context, key: String, l: List<String>) = prefs(ctx).edit().putString(key, l.joinToString(",")).apply()

fun loadProfiles(ctx: Context): List<Profile> = runCatching {
    val arr = JSONArray(prefs(ctx).getString("profiles", "[]")!!)
    (0 until arr.length()).map { val o = arr.getJSONObject(it); Profile(o.getString("name"), o.getString("theme"), o.getString("anim")) }
}.getOrDefault(emptyList())

fun saveProfiles(ctx: Context, list: List<Profile>) {
    val arr = JSONArray()
    list.forEach { p -> arr.put(JSONObject().put("name", p.name).put("theme", p.theme).put("anim", p.anim)) }
    prefs(ctx).edit().putString("profiles", arr.toString()).apply()
}

fun exportSettings(ctx: Context): String {
    val o = JSONObject()
    o.put("theme", prefs(ctx).getString("theme", "green"))
    o.put("anim", prefs(ctx).getString("anim", "network"))
    o.put("haptics", prefs(ctx).getBoolean("haptics", true))
    o.put("sounds", prefs(ctx).getBoolean("sounds", true))
    o.put("city", prefs(ctx).getString("city", ""))
    o.put("pins", prefs(ctx).getString("pins", ""))
    o.put("hidden", prefs(ctx).getString("hidden", ""))
    return o.toString()
}

fun importSettings(ctx: Context, json: String): Boolean = runCatching {
    val o = JSONObject(json)
    val e = prefs(ctx).edit()
    if (o.has("theme")) e.putString("theme", o.getString("theme"))
    if (o.has("anim")) e.putString("anim", o.getString("anim"))
    if (o.has("haptics")) e.putBoolean("haptics", o.getBoolean("haptics"))
    if (o.has("sounds")) e.putBoolean("sounds", o.getBoolean("sounds"))
    if (o.has("city")) e.putString("city", o.getString("city"))
    if (o.has("pins")) e.putString("pins", o.getString("pins"))
    if (o.has("hidden")) e.putString("hidden", o.getString("hidden"))
    e.apply(); true
}.getOrDefault(false)

fun copyToClipboard(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ctos", text))
}
fun readClipboard(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}

fun battery(ctx: Context) =
    (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
fun charging(ctx: Context) = (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging

fun ramUsed(ctx: Context): Int {
    val mi = ActivityManager.MemoryInfo()
    (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
    return (100 * (mi.totalMem - mi.availMem) / mi.totalMem).toInt()
}

fun storageUsed(): Int {
    val s = StatFs(Environment.getDataDirectory().path)
    return (100 - 100 * s.availableBytes / s.totalBytes).toInt()
}

fun netType(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val c = cm.getNetworkCapabilities(cm.activeNetwork)
    return when {
        c == null -> "ОФЛАЙН"
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "МОБИЛЬНАЯ"
        else -> "ОНЛАЙН"
    }
}

fun uptime(): String {
    val m = SystemClock.elapsedRealtime() / 60000
    return "${m / 60}ч ${m % 60}м"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { Launcher() }
    }
}

// ========================= UI-КОМПОНЕНТЫ =========================

@Composable
fun Panel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Color.Black.copy(alpha = 0.55f)).brackets(C).padding(12.dp)) {
        Text("■ $title", color = C, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
fun Bar(p: Int, col: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).border(1.dp, col.copy(alpha = 0.5f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0, 100) / 100f).background(col))
    }
}

@Composable
fun Wave(modifier: Modifier) {
    val ph by rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        val path = Path()
        for (i in 0..40) {
            val x = size.width * i / 40
            val y = size.height / 2 + (sin(i * 0.5f + ph * 6.2832f) + 0.5f * sin(i * 1.3f - ph * 12.5664f)) * size.height / 3.2f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, G, style = Stroke(2.5f))
    }
}

@Composable
fun Widgets(appCount: Int, onCycleAnim: () -> Unit = {}) {
    val ctx = LocalContext.current
    val wscope = rememberCoroutineScope()
    var time by remember { mutableStateOf(Date()) }
    var bat by remember { mutableIntStateOf(0) }
    var chg by remember { mutableStateOf(false) }
    var ram by remember { mutableIntStateOf(0) }
    var sto by remember { mutableIntStateOf(0) }
    var net by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            time = Date(); bat = battery(ctx); chg = charging(ctx); ram = ramUsed(ctx)
            sto = runCatching { storageUsed() }.getOrDefault(0)
            net = runCatching { netType(ctx) }.getOrDefault("?")
            delay(1000)
        }
    }
    val pulse by rememberInfiniteTransition(label = "w").animateFloat(0.25f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel("// ЧАСЫ", Modifier.weight(1.4f)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onCycleAnim() }) }) {
                val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(time)
                Box {
                    if (glitch > 0) Text(t, color = Warn, fontFamily = Mono, fontSize = 30.sp, modifier = Modifier.offset(x = 3.dp))
                    Text(t, color = G, fontFamily = Mono, fontSize = 30.sp)
                }
                Text(SimpleDateFormat("EEE dd MMM yyyy", Locale.US).format(time).uppercase(), color = G.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 11.sp)
                Text("2x тап: $animStyle", color = C.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 9.sp)
            }
            Panel("// ПИТАНИЕ", Modifier.weight(1f)
                .clickable { runCatching { go(ctx, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) } }) {
                Text("${if (chg) "⚡" else "ЗАРЯД"} $bat%", color = G, fontFamily = Mono, fontSize = 13.sp)
                Bar(bat, if (bat < 20) Warn else G)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(G.copy(alpha = pulse)))
                    Text("  ОНЛАЙН", color = G, fontFamily = Mono, fontSize = 11.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel("// СИСТЕМА", Modifier.weight(1f)
                .clickable { runCatching { go(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } }) {
                Text("RAM $ram%", color = C, fontFamily = Mono, fontSize = 11.sp); Bar(ram, C)
                Text("ПАМЯТЬ $sto%", color = C, fontFamily = Mono, fontSize = 11.sp); Bar(sto, C)
            }
            Panel("// СЕТЬ", Modifier.weight(1f)
                .clickable { runCatching { go(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) } }) {
                Text(net, color = G, fontFamily = Mono, fontSize = 14.sp)
                Text("АКТИВНО ${uptime()}", color = C, fontFamily = Mono, fontSize = 11.sp)
                Text("ПРОГРАММ $appCount", color = C, fontFamily = Mono, fontSize = 11.sp)
            }
            Panel("// СИГНАЛ", Modifier.weight(1f)) { Wave(Modifier.fillMaxWidth().height(44.dp)) }
        }
        Panel("// ПОГОДА (нажми — обновить)", Modifier.fillMaxWidth().clickable {
            val city = prefs(ctx).getString("city", "")!!
            if (city.isNotBlank()) wscope.launch { weather = runCatching { fetchWeather(city) }.getOrDefault("ПОГОДА: НЕТ СИГНАЛА") }
        }) { Text(weather, color = G, fontFamily = Mono, fontSize = 13.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(label: String, ic: ImageBitmap?, onClick: () -> Unit, onLong: () -> Unit, size: Dp = 56.dp) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).brackets(C, if (size > 70.dp) 16f else 12f).padding(if (size > 70.dp) 12.dp else 8.dp)) {
            if (ic != null) Image(ic, null, Modifier.fillMaxSize())
        }
        Text(label, color = G, fontFamily = Mono, fontSize = if (size > 70.dp) 12.sp else 10.sp, maxLines = 1)
    }
}

@Composable
fun Desktop(apps: List<App>, icons: Map<String, ImageBitmap>, pins: List<String>, pageIndex: Int,
    onUnpin: (String) -> Unit, onLongBackground: () -> Unit = {}, onCycleAnim: () -> Unit = {}) {
    val ctx = LocalContext.current
    val shown = pins.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
    val perPage = 7
    Column(Modifier.fillMaxSize()
        .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongBackground() }) },
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pageIndex == 0) Widgets(apps.size, onCycleAnim)
        if (shown.isEmpty()) {
            if (pageIndex == 0) Text("Долгое нажатие на приложение в меню APPS закрепляет его здесь (★ главное)", color = C.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 11.sp)
        } else {
            val primary = shown.first()
            val rest = shown.drop(1)
            val chunk = rest.drop(pageIndex * perPage).take(perPage)
            if (pageIndex == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AppIcon("★ ${primary.label}", icons[primary.pkg], { launch(ctx, primary.pkg) }, { onUnpin(primary.pkg) }, size = 88.dp)
                }
            }
            if (chunk.isNotEmpty()) {
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chunk, key = { it.pkg }) { a -> AppIcon(a.label, icons[a.pkg], { launch(ctx, a.pkg) }, { onUnpin(a.pkg) }) }
                }
            } else if (pageIndex > 0) {
                Text("Пустая страница", color = C.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun Hub(apps: List<App>, icons: Map<String, ImageBitmap>, nextEvent: String?, onRequestCalendar: () -> Unit) {
    val ctx = LocalContext.current
    var mc by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            notifGranted = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            if (notifGranted) {
                mc = nowPlaying(ctx)
                val md = mc?.metadata
                title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
                artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                playing = mc?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            }
            delay(2000)
        }
    }
    val favs = remember(apps) {
        apps.filter { prefs(ctx).getInt("use_" + it.pkg, 0) > 0 }
            .sortedByDescending { prefs(ctx).getInt("use_" + it.pkg, 0) }.take(4)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel("// ЧАСТО ИСПОЛЬЗУЕМЫЕ") {
            if (favs.isEmpty()) Text("Пока нет данных об использовании", color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 11.sp)
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                favs.forEach { a -> AppIcon(a.label, icons[a.pkg], { launch(ctx, a.pkg) }, {}) }
            }
        }
        Panel("// КАЛЕНДАРЬ") {
            if (nextEvent != null) Text(nextEvent, color = G, fontFamily = Mono, fontSize = 13.sp)
            else Text("Нет доступа — нажми, чтобы разрешить", color = C, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.clickable { onRequestCalendar() })
        }
        Panel("// СЕЙЧАС ИГРАЕТ") {
            if (!notifGranted) {
                Text("Нужен доступ к уведомлениям — нажми", color = C, fontFamily = Mono, fontSize = 12.sp,
                    modifier = Modifier.clickable { go(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            } else if (mc == null || title.isBlank()) {
                Text("Ничего не играет", color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 12.sp)
            } else {
                Text(title, color = G, fontFamily = Mono, fontSize = 13.sp, maxLines = 1)
                Text(artist, color = C, fontFamily = Mono, fontSize = 11.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("⏮", color = G, fontSize = 20.sp, modifier = Modifier.clickable { mc?.transportControls?.skipToPrevious() })
                    Text(if (playing) "⏸" else "▶", color = G, fontSize = 20.sp, modifier = Modifier.clickable {
                        if (playing) mc?.transportControls?.pause() else mc?.transportControls?.play()
                    })
                    Text("⏭", color = G, fontSize = 20.sp, modifier = Modifier.clickable { mc?.transportControls?.skipToNext() })
                }
            }
        }
        Panel("// УВЕДОМЛЕНИЯ") {
            if (!notifGranted) Text("Нужен доступ к уведомлениям — нажми", color = C, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.clickable { go(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            else if (NotifBus.items.isEmpty()) Text("Пока пусто", color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 11.sp)
            else NotifBus.items.forEach { Text(it, color = G, fontFamily = Mono, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

@Composable
fun Dock(apps: List<App>, icons: Map<String, ImageBitmap>, pins: List<String>) {
    val ctx = LocalContext.current
    val shown = pins.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.take(6)
    if (shown.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).brackets(C, 10f).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            shown.forEach { a -> AppIcon(a.label, icons[a.pkg], { launch(ctx, a.pkg) }, {}) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Drawer(apps: List<App>, icons: Map<String, ImageBitmap>, pins: List<String>, hidden: List<String>, folders: List<Folder>,
    onPin: (String) -> Unit, onCreateFolder: (String, List<String>) -> Unit, onDeleteFolder: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var folderMode by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var newFolderName by remember { mutableStateOf("") }
    var openFolder by remember { mutableStateOf<Folder?>(null) }
    val list = remember(q, apps, hidden) { apps.filter { it.label.contains(q, true) && it.pkg !in hidden }.sortedBy { it.label.lowercase() } }
    val grouped = remember(list) { list.groupBy { it.label.firstOrNull()?.uppercaseChar()?.toString() ?: "#" } }

    fun tapIcon(pkg: String) {
        if (folderMode) { if (pkg in selected) selected.remove(pkg) else selected.add(pkg) }
        else { launch(ctx, pkg); onClose() }
    }

    Column(Modifier.fillMaxSize().background(Color(0xF2020807)).systemBarsPadding().imePadding().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().border(1.dp, C).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("find> ", color = C, fontFamily = Mono, fontSize = 14.sp)
            BasicTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, cursorBrush = SolidColor(G),
                textStyle = TextStyle(color = G, fontFamily = Mono, fontSize = 14.sp))
            Text(if (folderMode) "📁✕" else "📁+", color = if (folderMode) Warn else C, fontSize = 16.sp,
                modifier = Modifier.clickable { folderMode = !folderMode; selected.clear() }.padding(start = 8.dp))
            Text("✕", color = Warn, fontSize = 18.sp, modifier = Modifier.clickable { onClose() }.padding(start = 8.dp))
        }
        if (folderMode) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(newFolderName, { newFolderName = it }, Modifier.weight(1f), singleLine = true, cursorBrush = SolidColor(G),
                    textStyle = TextStyle(color = G, fontFamily = Mono, fontSize = 13.sp))
                Text("СОЗДАТЬ (${selected.size})", color = if (selected.isNotEmpty() && newFolderName.isNotBlank()) G else C.copy(alpha = 0.4f),
                    fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.clickable {
                        if (selected.isNotEmpty() && newFolderName.isNotBlank()) {
                            onCreateFolder(newFolderName, selected.toList()); newFolderName = ""; selected.clear(); folderMode = false
                        }
                    }.padding(start = 8.dp))
            }
            Text("выбери приложения для папки, затем введи имя и нажми «создать»", color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 10.sp)
        } else {
            Text("долгое нажатие = закрепить (★) · 📁+ — создать папку · долгое нажатие на папке — удалить", color = C.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (!folderMode && folders.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { f ->
                    Column(Modifier.combinedClickable(onClick = { openFolder = f }, onLongClick = { onDeleteFolder(f.name) })
                        .brackets(C, 10f).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📁", fontSize = 22.sp)
                        Text(f.name, color = G, fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
                        Text("${f.pkgs.size}", color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 9.sp)
                    }
                }
            }
        }
        LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            grouped.toSortedMap().forEach { (letter, group) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("— $letter —", color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
                items(group, key = { it.pkg }) { a ->
                    AppIcon((if (a.pkg in pins) "★ " else "") + (if (a.pkg in selected) "✓ " else "") + a.label,
                        icons[a.pkg], { tapIcon(a.pkg) }, { if (!folderMode) onPin(a.pkg) })
                }
            }
        }
    }
    openFolder?.let { f ->
        Box(Modifier.fillMaxSize().background(Color(0xFA020807)).systemBarsPadding().imePadding().padding(12.dp)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("📁 ${f.name}", color = C, fontFamily = Mono, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Text("✕", color = Warn, fontSize = 18.sp, modifier = Modifier.clickable { openFolder = null })
                }
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(apps.filter { it.pkg in f.pkgs }, key = { it.pkg }) { a ->
                        AppIcon(a.label, icons[a.pkg], { launch(ctx, a.pkg); openFolder = null; onClose() }, {})
                    }
                }
            }
        }
    }
}

@Composable
fun Header() {
    val t by rememberInfiniteTransition(label = "eye").animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "e")
    val node = remember { Random.nextInt(1000, 9999) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 46.dp, height = 26.dp)) {
            val w = size.width; val h = size.height
            val p = Path()
            p.moveTo(0f, h / 2)
            p.cubicTo(w * 0.25f, -h * 0.2f, w * 0.75f, -h * 0.2f, w, h / 2)
            p.cubicTo(w * 0.75f, h * 1.2f, w * 0.25f, h * 1.2f, 0f, h / 2)
            drawPath(p, G, style = Stroke(3f))
            drawCircle(G, h * 0.26f, Offset(w / 2 + sin(t * 6.2832f) * w * 0.15f, h / 2))
        }
        Text("  ctOS", color = G, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 4.sp)
        Spacer(Modifier.weight(1f))
        Text("УЗЕЛ $node // ЗАЩИЩЕНО", color = C, fontFamily = Mono, fontSize = 10.sp)
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    var time by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { time = Date(); delay(1000) } }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -40f) onUnlock() } },
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(SimpleDateFormat("HH:mm", Locale.US).format(time), color = G, fontFamily = Mono, fontSize = 64.sp)
            Text("ctOS SECURE", color = C, fontFamily = Mono, fontSize = 14.sp, letterSpacing = 4.sp)
            Spacer(Modifier.height(40.dp))
            Text("▲ свайп вверх — разблокировать", color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 12.sp)
        }
    }
}

@Composable
fun SettingsPanel(
    pinsCount: Int, hiddenCount: Int, profiles: List<Profile>, desktopPages: Int, onSetPages: (Int) -> Unit,
    onTheme: (String) -> Unit, onAnim: (String) -> Unit, onHaptics: (Boolean) -> Unit, onSounds: (Boolean) -> Unit,
    onFakeLock: (Boolean) -> Unit, fakeLockOn: Boolean,
    onClearPins: () -> Unit, onCitySave: (String) -> Unit,
    onSaveProfile: (String) -> Unit, onUseProfile: (Profile) -> Unit, onDeleteProfile: (String) -> Unit,
    onExport: () -> Unit, onImport: () -> Unit, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    var city by remember { mutableStateOf(prefs(ctx).getString("city", "")!!) }
    var profName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color(0xF2020807)).systemBarsPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("■ НАСТРОЙКИ", color = C, fontFamily = Mono, fontSize = 16.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("✕", color = Warn, fontSize = 18.sp, modifier = Modifier.clickable { onClose() })
        }
        Panel("// ТЕМА") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                themes.keys.forEach { name ->
                    Box(Modifier.size(32.dp).background(themes[name]!!.g)
                        .border(width = if (name == prefs(ctx).getString("theme", "green")) 3.dp else 1.dp, color = Color.White.copy(alpha = 0.6f))
                        .clickable { onTheme(name) })
                }
            }
        }
        Panel("// АНИМАЦИЯ ФОНА") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("network" to "СЕТЬ", "rain" to "ДОЖДЬ", "radar" to "РАДАР", "city" to "ГОРОД").forEach { (k, label) ->
                    Text(label, color = if (animStyle == k) G else C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 12.sp,
                        modifier = Modifier.brackets(if (animStyle == k) G else C.copy(alpha = 0.3f), 8f).padding(8.dp).clickable { onAnim(k) })
                }
            }
        }
        Panel("// ОЩУЩЕНИЯ") {
            Text(if (hapticsOn) "ВИБРАЦИЯ: ВКЛ" else "ВИБРАЦИЯ: ВЫКЛ", color = G, fontFamily = Mono, fontSize = 13.sp,
                modifier = Modifier.clickable { onHaptics(!hapticsOn) })
            Text(if (soundsOn) "ЗВУКИ: ВКЛ" else "ЗВУКИ: ВЫКЛ", color = G, fontFamily = Mono, fontSize = 13.sp,
                modifier = Modifier.clickable { onSounds(!soundsOn) })
            Text(if (fakeLockOn) "ЭКРАН БЛОКИРОВКИ ПРИ ЗАПУСКЕ: ВКЛ" else "ЭКРАН БЛОКИРОВКИ ПРИ ЗАПУСКЕ: ВЫКЛ", color = G, fontFamily = Mono, fontSize = 13.sp,
                modifier = Modifier.clickable { onFakeLock(!fakeLockOn) })
            Text("косметический, не заменяет настоящую блокировку Android", color = C.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 9.sp)
        }
        Panel("// ГОРОД ДЛЯ ПОГОДЫ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(city, { city = it }, Modifier.weight(1f), singleLine = true, cursorBrush = SolidColor(G),
                    textStyle = TextStyle(color = G, fontFamily = Mono, fontSize = 13.sp))
                Text("OK", color = C, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.clickable { onCitySave(city) }.padding(start = 10.dp))
            }
        }
        Panel("// ПРИЛОЖЕНИЯ") {
            Text("Закреплено: $pinsCount", color = C, fontFamily = Mono, fontSize = 12.sp)
            Text("Скрыто: $hiddenCount (hide/unhide <имя> в терминале)", color = C, fontFamily = Mono, fontSize = 12.sp)
            Text("ОЧИСТИТЬ ЗАКРЕПЛЁННЫЕ", color = Warn, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.clickable { onClearPins() })
            Text("Папки создаются в меню приложений (кнопка 📁+)", color = C.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 9.sp)
        }
        Panel("// РАБОЧИЕ СТОЛЫ") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (1..3).forEach { n ->
                    Text("$n", color = if (desktopPages == n) G else C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 14.sp,
                        modifier = Modifier.brackets(if (desktopPages == n) G else C.copy(alpha = 0.3f), 8f).padding(10.dp).clickable { onSetPages(n) })
                }
            }
            Text("число страниц рабочего стола (значки распределяются по страницам)", color = C.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 9.sp)
        }
        Panel("// ДОСТУП К УВЕДОМЛЕНИЯМ") {
            val granted = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            Text(if (granted) "ПРЕДОСТАВЛЕН" else "ОТКРЫТЬ НАСТРОЙКИ ДОСТУПА", color = if (granted) G else C, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.clickable { go(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            Text("нужен для виджетов «сейчас играет» и уведомлений", color = C.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 9.sp)
        }
        Panel("// ПРОФИЛИ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(profName, { profName = it }, Modifier.weight(1f), singleLine = true, cursorBrush = SolidColor(G),
                    textStyle = TextStyle(color = G, fontFamily = Mono, fontSize = 13.sp))
                Text("СОХРАНИТЬ", color = C, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.clickable {
                    if (profName.isNotBlank()) { onSaveProfile(profName); profName = "" }
                }.padding(start = 10.dp))
            }
            profiles.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = G, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.weight(1f).clickable { onUseProfile(p) })
                    Text("✕", color = Warn, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.clickable { onDeleteProfile(p.name) })
                }
            }
        }
        Panel("// РЕЗЕРВНАЯ КОПИЯ") {
            Text("СКОПИРОВАТЬ НАСТРОЙКИ В БУФЕР", color = C, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.clickable { onExport() })
            Text("ВСТАВИТЬ ИЗ БУФЕРА", color = C, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.clickable { onImport() })
        }
        Text("ЖЕСТЫ: вниз — настройки · вверх — приложения · 2x тап по часам — анимация · долгое нажатие на столе — настройки · влево/вправо — страницы",
            color = C.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 10.sp)
    }
}

// ========================= ГЛАВНЫЙ ЭКРАН =========================

@Composable
fun Launcher() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hap = LocalHapticFeedback.current
    var apps by remember { mutableStateOf(emptyList<App>()) }
    val icons = remember { mutableStateMapOf<String, ImageBitmap>() }
    val pins = remember { mutableStateListOf<String>().apply { addAll(loadCsv(ctx, "pins")) } }
    val hidden = remember { mutableStateListOf<String>().apply { addAll(loadCsv(ctx, "hidden")) } }
    val profiles = remember { mutableStateListOf<Profile>().apply { addAll(loadProfiles(ctx)) } }
    val folders = remember { mutableStateListOf<Folder>().apply { addAll(loadFolders(ctx)) } }
    var desktopPages by remember { mutableIntStateOf(prefs(ctx).getInt("desktopPages", 1).coerceIn(1, 3)) }
    val log = remember { mutableStateListOf<String>() }
    val hist = remember { mutableListOf<String>() }
    var input by remember { mutableStateOf("") }
    var drawer by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fakeLockOn by remember { mutableStateOf(prefs(ctx).getBoolean("fakelock", false)) }
    var locked by remember { mutableStateOf(false) }
    var nextEvent by remember { mutableStateOf<String?>(null) }
    val nodes = remember { List(10) { Offset(Random.nextFloat(), Random.nextFloat()) } }
    val listState = rememberLazyListState()
    val pager = rememberPagerState { desktopPages + 2 }
    val scan by rememberInfiniteTransition(label = "fx").animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "scan")

    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { nextEvent = fetchNextEvent(ctx) }
    }

    LaunchedEffect(Unit) {
        themes[prefs(ctx).getString("theme", "green")!!]?.let { G = it.g; C = it.c; Warn = it.r }
        animStyle = prefs(ctx).getString("anim", "network")!!
        hapticsOn = prefs(ctx).getBoolean("haptics", true)
        soundsOn = prefs(ctx).getBoolean("sounds", true)
        locked = fakeLockOn
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
            nextEvent = fetchNextEvent(ctx)
        val pm = ctx.packageManager
        val loaded = withContext(Dispatchers.Default) { loadApps(ctx) }
        apps = loaded
        withContext(Dispatchers.Default) {
            loaded.forEach { a -> runCatching { icons[a.pkg] = pm.getApplicationIcon(a.pkg).toBitmap(96, 96).asImageBitmap() } }
        }
    }
    LaunchedEffect(Unit) {
        listOf("ctOS v7.0 ЗАГРУЗКА...", "ядро ........ OK", "обход файрвола .... OK", "СОЕДИНЕНИЕ УСТАНОВЛЕНО", "введите 'help'")
            .forEach { log.add(it); delay(220) }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(Random.nextLong(1500, 4000)); glitch = Random.nextInt(1, 4); delay(160); glitch = 0 }
    }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex) }
    LaunchedEffect(launchFlashTick) { if (launchFlashTick > 0) { launchFlash = true; delay(220); launchFlash = false } }
    LaunchedEffect(Unit) {
        while (true) {
            val city = prefs(ctx).getString("city", "")!!
            if (city.isNotBlank()) weather = runCatching { fetchWeather(city) }.getOrDefault("ПОГОДА: НЕТ СИГНАЛА")
            delay(900_000)
        }
    }
    BackHandler(drawer) { drawer = false }
    BackHandler(settingsOpen) { settingsOpen = false }
    BackHandler(!drawer && !settingsOpen) { if (pager.currentPage != 0) scope.launch { pager.animateScrollToPage(0) } }

    fun find(q: String): App? =
        if (q.isBlank()) null else q.toIntOrNull()?.let { apps.getOrNull(it) } ?: apps.firstOrNull { it.label.contains(q, true) }
    fun togglePin(pkg: String) { if (pkg in pins) pins.remove(pkg) else pins.add(pkg); saveCsv(ctx, "pins", pins) }
    fun buzz() { if (hapticsOn) hap.performHapticFeedback(HapticFeedbackType.LongPress) }
    fun setAnim(a: String) { animStyle = a; prefs(ctx).edit().putString("anim", a).apply() }
    fun cycleAnim() { setAnim(animNames[(animNames.indexOf(animStyle) + 1) % animNames.size]) }
    fun setHaptics(b: Boolean) { hapticsOn = b; prefs(ctx).edit().putBoolean("haptics", b).apply() }
    fun setSounds(b: Boolean) { soundsOn = b; prefs(ctx).edit().putBoolean("sounds", b).apply() }
    fun setFakeLock(b: Boolean) { fakeLockOn = b; prefs(ctx).edit().putBoolean("fakelock", b).apply() }
    fun setDesktopPages(n: Int) { desktopPages = n.coerceIn(1, 3); prefs(ctx).edit().putInt("desktopPages", desktopPages).apply() }
    fun createFolder(name: String, pkgs: List<String>) { folders.add(Folder(name, pkgs)); saveFolders(ctx, folders) }
    fun deleteFolder(name: String) { folders.removeAll { it.name == name }; saveFolders(ctx, folders) }
    fun saveCity(c: String) {
        prefs(ctx).edit().putString("city", c).apply()
        scope.launch { weather = runCatching { fetchWeather(c) }.getOrDefault("ПОГОДА: НЕТ СИГНАЛА") }
    }
    fun doExport() { copyToClipboard(ctx, exportSettings(ctx)) }
    fun doImport() {
        val txt = readClipboard(ctx)
        if (txt != null && importSettings(ctx, txt)) {
            themes[prefs(ctx).getString("theme", "green")!!]?.let { G = it.g; C = it.c; Warn = it.r }
            animStyle = prefs(ctx).getString("anim", "network")!!
            hapticsOn = prefs(ctx).getBoolean("haptics", true)
            soundsOn = prefs(ctx).getBoolean("sounds", true)
            pins.clear(); pins.addAll(loadCsv(ctx, "pins"))
            hidden.clear(); hidden.addAll(loadCsv(ctx, "hidden"))
        }
    }

    fun run(cmd: String) {
        buzz()
        log.add("> $cmd")
        if (cmd.isNotBlank()) hist.add(cmd)
        val p = cmd.trim().split(" ", limit = 2)
        val arg = p.getOrNull(1) ?: ""
        when (p[0].lowercase()) {
            "help" -> {
                log.add("apps list open hide unhide hidden pin unpin pins")
                log.add("battery ram disk net uptime sysinfo time history")
                log.add("search wifi settings config theme <green|red|blue|amber|purple>")
                log.add("weather profile sound <on|off> lock anim <network|rain|radar|city>")
                log.add("pages <1|2|3> backup restore desktop hub term scan echo whoami clear")
            }
            "apps" -> drawer = true
            "list" -> apps.forEachIndexed { i, a -> log.add("[$i] ${a.label}") }
            "open" -> {
                val a = find(arg)
                if (a != null && launch(ctx, a.pkg)) log.add("ЗАПУСК ${a.label}...") else log.add("ОШИБКА: не найдено")
            }
            "pin" -> find(arg)?.let { if (it.pkg !in pins) togglePin(it.pkg); log.add("ЗАКРЕПЛЕНО ${it.label}") } ?: log.add("ОШИБКА: не найдено")
            "unpin" -> find(arg)?.let { if (it.pkg in pins) togglePin(it.pkg); log.add("ОТКРЕПЛЕНО ${it.label}") } ?: log.add("ОШИБКА: не найдено")
            "pins" -> if (pins.isEmpty()) log.add("нет закреплённых") else pins.forEach { pk -> log.add("* ${apps.firstOrNull { it.pkg == pk }?.label ?: pk}") }
            "hide" -> find(arg)?.let { if (it.pkg !in hidden) { hidden.add(it.pkg); saveCsv(ctx, "hidden", hidden) }; log.add("СКРЫТО ${it.label}") } ?: log.add("ОШИБКА: не найдено")
            "unhide" -> find(arg)?.let { if (it.pkg in hidden) { hidden.remove(it.pkg); saveCsv(ctx, "hidden", hidden) }; log.add("ПОКАЗАНО ${it.label}") } ?: log.add("ОШИБКА: не найдено")
            "hidden" -> if (hidden.isEmpty()) log.add("нет скрытых") else hidden.forEach { pk -> log.add("* ${apps.firstOrNull { it.pkg == pk }?.label ?: pk}") }
            "battery" -> log.add("ЗАРЯД: ${battery(ctx)}%${if (charging(ctx)) " (ЗАРЯЖАЕТСЯ)" else ""}")
            "ram" -> log.add("ПАМЯТЬ: ${ramUsed(ctx)}%")
            "disk" -> log.add("ХРАНИЛИЩЕ: ${storageUsed()}%")
            "net" -> log.add("СЕТЬ: ${netType(ctx)}")
            "uptime" -> log.add("АКТИВНОСТЬ: ${uptime()}")
            "sysinfo" -> {
                log.add("${Build.MANUFACTURER} ${Build.MODEL}")
                log.add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                log.add("ядер CPU: ${Runtime.getRuntime().availableProcessors()}")
            }
            "time" -> log.add(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(Date()))
            "history" -> hist.forEachIndexed { i, h -> log.add("${i + 1}  $h") }
            "echo" -> log.add(arg)
            "sound" -> when (arg.lowercase()) {
                "on" -> { setSounds(true); log.add("ЗВУК: ВКЛ") }
                "off" -> { setSounds(false); log.add("ЗВУК: ВЫКЛ") }
                else -> log.add("sound on | off")
            }
            "lock" -> { locked = true; log.add("ЭКРАН ЗАБЛОКИРОВАН") }
            "pages" -> arg.toIntOrNull()?.let { if (it in 1..3) { setDesktopPages(it); log.add("РАБОЧИХ СТОЛОВ: $it") } else log.add("pages: 1 | 2 | 3") } ?: log.add("pages: 1 | 2 | 3")
            "backup" -> { doExport(); log.add("НАСТРОЙКИ СКОПИРОВАНЫ В БУФЕР") }
            "restore" -> { doImport(); log.add("НАСТРОЙКИ ВОССТАНОВЛЕНЫ ИЗ БУФЕРА") }
            "profile" -> if (profiles.isEmpty()) log.add("нет сохранённых профилей") else profiles.forEach { log.add("* ${it.name}") }
            "weather" -> if (arg.isBlank()) log.add("usage: weather <город>") else {
                saveCity(arg); log.add("СИНХРОНИЗАЦИЯ ПОГОДЫ...")
            }
            "whoami" -> log.add("AIDEN // оператор ctOS")
            "search" -> if (arg.isBlank()) log.add("usage: search <текст>") else go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(arg))))
            "wifi" -> go(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))
            "settings" -> go(ctx, Intent(Settings.ACTION_SETTINGS))
            "config" -> settingsOpen = true
            "anim" -> if (arg.lowercase() in animNames) { setAnim(arg.lowercase()); log.add("АНИМАЦИЯ: $arg") } else log.add("anim: ${animNames.joinToString(" | ")}")
            "desktop" -> scope.launch { pager.animateScrollToPage(0) }
            "hub" -> scope.launch { pager.animateScrollToPage(desktopPages) }
            "term" -> scope.launch { pager.animateScrollToPage(desktopPages + 1) }
            "theme" -> {
                val t = themes[arg.lowercase()]
                if (t != null) {
                    G = t.g; C = t.c; Warn = t.r
                    prefs(ctx).edit().putString("theme", arg.lowercase()).apply(); log.add("ТЕМА: $arg")
                } else log.add("themes: ${themes.keys.joinToString()}")
            }
            "clear" -> log.clear()
            "scan" -> scope.launch {
                log.add(""); val i = log.lastIndex
                for (n in 0..10) { log[i] = "СКАНИРОВАНИЕ СЕТИ [${"#".repeat(n)}${".".repeat(10 - n)}] ${n * 10}%"; delay(150) }
                log.add("НАЙДЕНО 3 УСТРОЙСТВА // ДОСТУП: ЗАПРЕЩЁН")
            }
            "" -> {}
            else -> {
                val a = apps.firstOrNull { it.label.equals(cmd.trim(), true) }
                if (a != null && launch(ctx, a.pkg)) log.add("ЗАПУСК ${a.label}...") else log.add("ОШИБКА: неизвестная команда")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF020807))
        .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d > 26f && !drawer && !settingsOpen && !locked) { settingsOpen = true } } }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            var x = 0f; while (x < w) { drawLine(G.copy(alpha = 0.05f), Offset(x, 0f), Offset(x, h)); x += 60f }
            var y = 0f; while (y < h) { drawLine(G.copy(alpha = 0.05f), Offset(0f, y), Offset(w, y)); y += 60f }
            repeat(14) { i ->
                val rx = w * (i + 0.5f) / 14
                val ry = ((scan * (1 + i % 3) + i * 0.37f) % 1f) * h
                drawLine(G.copy(alpha = 0.3f), Offset(rx, ry - 70f), Offset(rx, ry), 2f)
            }
            when (animStyle) {
                "rain" -> {
                    val cols = 16
                    repeat(cols) { i ->
                        val cx = w * (i + 0.5f) / cols
                        val speed = 0.6f + (i % 5) * 0.18f
                        val head = ((scan * speed * 5f + i * 0.17f) % 1.3f) * h
                        for (k in 0 until 7) {
                            val yy = head - k * 24f
                            if (yy in 0f..h) drawCircle(G.copy(alpha = (1f - k / 7f).coerceIn(0f, 1f)), 3f, Offset(cx, yy))
                        }
                    }
                }
                "radar" -> {
                    val cx = w / 2; val cy = h / 2; val rad = minOf(w, h) * 0.42f
                    for (ring in 1..3) drawCircle(C.copy(alpha = 0.18f), rad * ring / 3f, Offset(cx, cy), style = Stroke(1.5f))
                    val ang = scan * 6.2832f * 2f
                    drawLine(G.copy(alpha = 0.7f), Offset(cx, cy), Offset(cx + rad * cos(ang), cy + rad * sin(ang)), 2f)
                    drawArc(Brush.sweepGradient(listOf(Color.Transparent, G.copy(alpha = 0.22f))),
                        Math.toDegrees(ang.toDouble()).toFloat() - 50f, 50f, false,
                        topLeft = Offset(cx - rad, cy - rad), size = Size(rad * 2, rad * 2))
                }
                "city" -> {
                    repeat(10) { i ->
                        val depth = (scan * 0.5f + i.toFloat() / 10) % 1f
                        val yy = depth * depth * h
                        drawLine(C.copy(alpha = (1f - depth) * 0.3f), Offset(0f, yy), Offset(w, yy), 1.5f)
                    }
                    val cx = w / 2
                    for (k in -4..4) drawLine(G.copy(alpha = 0.12f), Offset(cx, h), Offset(cx + k * w * 0.15f, 0f), 1f)
                }
                else -> {
                    val pts = nodes.mapIndexed { i, a ->
                        val t = scan * 6.2832f + i
                        Offset((a.x + 0.03f * sin(t)) * w, (a.y + 0.03f * cos(t)) * h)
                    }
                    pts.forEachIndexed { i, pa ->
                        for (j in i + 1 until pts.size) if ((pa - pts[j]).getDistance() < w * 0.45f) drawLine(C.copy(alpha = 0.15f), pa, pts[j])
                        drawCircle(C.copy(alpha = 0.3f + 0.7f * abs(sin(scan * 12.5664f + i))), 4f, pa)
                    }
                }
            }
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, G.copy(alpha = 0.18f), Color.Transparent),
                startY = scan * h - 80f, endY = scan * h + 80f))
            repeat(glitch) { drawRect(Warn.copy(alpha = 0.25f), Offset(0f, Random.nextFloat() * h), Size(w, 6f + Random.nextFloat() * 14f)) }
            repeat(glitch * 6) { drawRect(C.copy(alpha = 0.35f), Offset(Random.nextFloat() * w, Random.nextFloat() * h), Size(12f + Random.nextFloat() * 60f, 4f)) }
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Header()
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                when {
                    page < desktopPages -> Desktop(apps, icons, pins, page, { togglePin(it) }, { settingsOpen = true; drawer = false }, { cycleAnim() })
                    page == desktopPages -> Hub(apps, icons, nextEvent) { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
                    else -> Panel("// ТЕРМИНАЛ", Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f), state = listState) {
                            items(log) { Text(it, color = if (it.startsWith("ОШИБКА")) Warn else G, fontFamily = Mono, fontSize = 13.sp) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("root@ctos:~$ ", color = C, fontFamily = Mono, fontSize = 13.sp)
                            BasicTextField(input, { input = it }, Modifier.weight(1f), singleLine = true, cursorBrush = SolidColor(G),
                                textStyle = TextStyle(color = G, fontFamily = Mono, fontSize = 13.sp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { run(input); input = "" }))
                        }
                    }
                }
            }
            if (pager.currentPage >= desktopPages) Dock(apps, icons, pins)
            Text(
                (0 until desktopPages + 2).joinToString("") { if (it == pager.currentPage) "●" else "○" } + "    ▲  A P P S",
                color = C, fontFamily = Mono, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { drawer = true }
                    .pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d < -20f) drawer = true } }
                    .padding(8.dp))
        }

        AnimatedVisibility(drawer, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Drawer(apps, icons, pins, hidden, folders, { togglePin(it) }, { name, pkgs -> createFolder(name, pkgs) }, { deleteFolder(it) }) { drawer = false }
        }
        AnimatedVisibility(launchFlash, enter = fadeIn(tween(60)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().background(G.copy(alpha = 0.28f)))
        }
        AnimatedVisibility(settingsOpen, enter = fadeIn() + slideInVertically { -it / 3 }, exit = fadeOut() + slideOutVertically { -it / 3 }) {
            SettingsPanel(
                pins.size, hidden.size, profiles, desktopPages, { setDesktopPages(it) },
                { name -> themes[name]?.let { G = it.g; C = it.c; Warn = it.r }; prefs(ctx).edit().putString("theme", name).apply() },
                { setAnim(it) }, { setHaptics(it) }, { setSounds(it) },
                { setFakeLock(it) }, fakeLockOn,
                { pins.clear(); saveCsv(ctx, "pins", pins) }, { saveCity(it) },
                { name -> val pr = Profile(name, prefs(ctx).getString("theme", "green")!!, animStyle); profiles.add(pr); saveProfiles(ctx, profiles) },
                { pr -> themes[pr.theme]?.let { G = it.g; C = it.c; Warn = it.r }; prefs(ctx).edit().putString("theme", pr.theme).apply(); setAnim(pr.anim) },
                { name -> profiles.removeAll { it.name == name }; saveProfiles(ctx, profiles) },
                { doExport() }, { doImport() }, { settingsOpen = false }
            )
        }
        if (locked) LockScreen { locked = false }
    }
}
