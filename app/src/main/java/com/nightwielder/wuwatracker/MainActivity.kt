package com.nightwielder.wuwatracker

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.nightwielder.wuwatracker.data.*
import com.nightwielder.wuwatracker.model.*
import com.nightwielder.wuwatracker.ui.*
import com.nightwielder.wuwatracker.ui.banners.*
import com.nightwielder.wuwatracker.ui.intel.*
import com.nightwielder.wuwatracker.worker.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

val Context.dataStore by preferencesDataStore("wuwa_tracker")
const val CHANNEL_ID = "wuwa_reminders"
const val RESET_ALARM = 1001
const val FEED_WORK = "wuwa_feed_sync"
const val GITHUB_FEED_URL = "https://the-nightwielder.github.io/WuWa-Tracker/feed.json"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        setContent { WuWaTrackerRoot() }
    }

    private fun createNotificationChannel() =
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WuWa reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Daily reset and information reminders"
            }
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WuWaTrackerRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UiState()) }
    var now by remember { mutableStateOf(Instant.now()) }
    var tab by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf("") }
    var showServerPicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showServerPicker = !state.serverSelected
    }

    LaunchedEffect(Unit) {
        state = loadState(context).copy(feedUrl = GITHUB_FEED_URL)
        saveState(context, state)
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showServerPicker = !state.serverSelected
        }
        refreshGithubFeed(context, scope, { syncing = it }, { message -> syncMessage = message }, { fresh -> state = fresh })
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }

    var dailyResetServer by remember { mutableStateOf<Server?>(null) }
    LaunchedEffect(resetKey(state.server, now)) {
        val serverChanged = dailyResetServer != null && dailyResetServer != state.server
        dailyResetServer = state.server
        if (!serverChanged && (state.dailyDone || state.luniteDone)) {
            state = state.copy(dailyDone = false, luniteDone = false)
            saveState(context, state)
        }
    }

    var weeklyResetServer by remember { mutableStateOf<Server?>(null) }
    LaunchedEffect(weeklyResetKey(state.server, now)) {
        val serverChanged = weeklyResetServer != null && weeklyResetServer != state.server
        weeklyResetServer = state.server
        if (!serverChanged && state.weeklyDone) {
            state = state.copy(weeklyDone = false)
            saveState(context, state)
        }
    }

    LaunchedEffect(state.server, state.reminderEnabled, state.reminderLead) {
        if (state.reminderEnabled && state.reminderLead >= 0) scheduleResetAlarm(state.server, state.reminderLead) else cancelResetAlarm()
    }
    LaunchedEffect(state.customReminderAt, state.reminderEnabled, state.reminderLead) {
        if (state.reminderEnabled && state.reminderLead == -1) state.customReminderAt?.let { scheduleCustomReminder(it) }
        else cancelCustomReminder()
    }

    val update: ((UiState) -> UiState) -> Unit = { transform ->
        state = transform(state)
        scope.launch { saveState(context, state) }
    }
    val onRefreshFeed = {
        if (!syncing) refreshGithubFeed(context, scope, { syncing = it }, { message -> syncMessage = message }, { fresh -> state = fresh })
    }

    var monthlyResetServer by remember { mutableStateOf<Server?>(null) }
    LaunchedEffect(cycleKey(state.server, now)) {
        val serverChanged = monthlyResetServer != null && monthlyResetServer != state.server
        monthlyResetServer = state.server
        if (!serverChanged && (state.monthlyTower || state.monthlyWastes || state.monthlyMatrix)) {
            state = state.copy(monthlyTower = false, monthlyWastes = false, monthlyMatrix = false)
            saveState(context, state)
        }
    }

    val reset = nextReset(state.server, now)
    val localReset = reset.withZoneSameInstant(ZoneId.systemDefault())

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("WuWa Tracker", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xD916131D))
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xEE16131D)) {
                    listOf("Home", "Endgame", "Banners", "Intel", "Settings").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(listOf("⌂", "⚔", "🚩", "📰", "⚙")[i]) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeScreen(state, now, durationText(Duration.between(now, reset.toInstant())), localReset, update, Modifier.padding(padding))
                1 -> EndgameScreen(state, now, Modifier.padding(padding))
                2 -> BannersScreen(state, now, Modifier.padding(padding))
                3 -> IntelScreen(state, now, syncing, syncMessage, onRefreshFeed, { key -> update { st -> st.copy(collapsedSections = if (key in st.collapsedSections) st.collapsedSections - key else st.collapsedSections + key) } }, Modifier.padding(padding))
                else -> SettingsScreen(state, update, modifier = Modifier.padding(padding))
            }
        }

        if (showServerPicker) AlertDialog(
            onDismissRequest = {},
            title = { Text("Choose your game server") },
            text = {
                Column {
                    Text("Reset countdowns and reminders use this server’s daily reset.")
                    Spacer(Modifier.height(12.dp))
                    Server.entries.forEach { server ->
                        TextButton(onClick = {
                            update { it.copy(server = server, serverSelected = true) }
                            showServerPicker = false
                        }) {
                            Text(server.label)
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

fun cycleKey(server: Server, now: Instant = Instant.now()) = server.name + "-" + YearMonth.from(now.atZone(ZoneId.of(server.zone)))
fun resetKey(server: Server, now: Instant = Instant.now()) = "${server.name}-${nextReset(server, now).minusNanos(1).toLocalDate()}"
fun weeklyResetKey(server: Server, now: Instant = Instant.now()): String {
    val zdt = now.atZone(ZoneId.of(server.zone))
    val monday = zdt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate()
    return "${server.name}-$monday"
}

fun durationText(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    val days = s / 86400
    val hours = (s % 86400) / 3600
    val mins = (s % 3600) / 60
    val secs = s % 60
    return if (days > 0) "%dd %02dh %02dm".format(days, hours, mins) else "%02dh %02dm %02ds".format(hours, mins, secs)
}

fun UiState.withFeed(f: FeedResult) = copy(feedItems = f.items, events = mergeEvents(defaultEvents(), f.events), versions = f.versions, banners = f.banners, resonators = f.resonators, version = f.latestVersion ?: version, lastSync = Instant.now().toString())

suspend fun loadState(context: Context): UiState {
    val p = context.dataStore.data.first()
    val server = runCatching { Server.valueOf(p[stringPreferencesKey("server")] ?: Server.SEA.name) }.getOrDefault(Server.SEA)
    val key = resetKey(server)
    val weeklyKey = weeklyResetKey(server)
    val stored = p[stringPreferencesKey("reset_key")]
    val storedWeekly = p[stringPreferencesKey("weekly_reset_key")]
    val raw = p[stringPreferencesKey("cached_feed")]
    val f = runCatching { parseFeed(raw) }.getOrDefault(FeedResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null))
    return UiState(
        server = server,
        dailyDone = if (stored == key) (p[booleanPreferencesKey("daily")] ?: false) else false,
        luniteDone = if (stored == key) (p[booleanPreferencesKey("lunite")] ?: false) else false,
        weeklyDone = if (storedWeekly == weeklyKey) (p[booleanPreferencesKey("weekly")] ?: false) else false,
        weeklyDoneDate = p[stringPreferencesKey("weekly_done_date")] ?: "",
        reminderEnabled = p[booleanPreferencesKey("reminder")] ?: true,
        reminderLead = p[intPreferencesKey("lead")]?.let { if (it == -1) -1 else it.coerceIn(0, 60) } ?: 0,
        version = p[stringPreferencesKey("version")] ?: "Unknown",
        events = mergeEvents(defaultEvents(), f.events),
        feedItems = f.items,
        versions = f.versions,
        banners = f.banners,
        resonators = f.resonators,
        feedUrl = GITHUB_FEED_URL,
        lastSync = p[stringPreferencesKey("last_sync")] ?: "",
        serverSelected = p[booleanPreferencesKey("server_selected")] ?: false,
        customReminderAt = p[stringPreferencesKey("custom_reminder_at")]?.let { instant(it) },
        collapsedSections = if (p[stringPreferencesKey("collapse_migration")] != "expanded-default-v2") emptySet() else p[stringPreferencesKey("collapsed_sections")]?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
        monthlyTower = p[stringPreferencesKey("cycle_key")] == cycleKey(server) && (p[booleanPreferencesKey("monthly_tower")] ?: false),
        monthlyWastes = p[stringPreferencesKey("cycle_key")] == cycleKey(server) && (p[booleanPreferencesKey("monthly_wastes")] ?: false),
        monthlyMatrix = p[stringPreferencesKey("cycle_key")] == cycleKey(server) && (p[booleanPreferencesKey("monthly_matrix")] ?: false)
    )
}

suspend fun saveState(context: Context, state: UiState) {
    context.dataStore.edit { p ->
        p[stringPreferencesKey("server")] = state.server.name
        p[stringPreferencesKey("reset_key")] = resetKey(state.server)
        p[stringPreferencesKey("weekly_reset_key")] = weeklyResetKey(state.server)
        p[booleanPreferencesKey("daily")] = state.dailyDone
        p[booleanPreferencesKey("lunite")] = state.luniteDone
        p[booleanPreferencesKey("weekly")] = state.weeklyDone
        p[stringPreferencesKey("weekly_done_date")] = state.weeklyDoneDate
        p[stringPreferencesKey("reminder")] = state.reminderEnabled.toString()
        p[booleanPreferencesKey("reminder")] = state.reminderEnabled
        p[intPreferencesKey("lead")] = state.reminderLead
        p[stringPreferencesKey("feed_url")] = state.feedUrl
        p[stringPreferencesKey("last_sync")] = state.lastSync
        p[stringPreferencesKey("version")] = state.version
        p[stringPreferencesKey("cached_feed")] = stateToJson(state).toString()
        p[booleanPreferencesKey("server_selected")] = state.serverSelected
        p[stringPreferencesKey("collapsed_sections")] = state.collapsedSections.joinToString(",")
        p[stringPreferencesKey("collapse_migration")] = "expanded-default-v2"
        p[stringPreferencesKey("cycle_key")] = cycleKey(state.server)
        p[booleanPreferencesKey("monthly_tower")] = state.monthlyTower
        p[booleanPreferencesKey("monthly_wastes")] = state.monthlyWastes
        p[booleanPreferencesKey("monthly_matrix")] = state.monthlyMatrix
        state.customReminderAt?.let { p[stringPreferencesKey("custom_reminder_at")] = it.toString() } ?: p.remove(stringPreferencesKey("custom_reminder_at"))
    }
}

fun stateToJson(state: UiState) = JSONObject().apply {
    put("latestVersion", state.version)
    put("news", JSONArray().apply { state.feedItems.forEach { put(JSONObject().apply { put("title", it.title); put("url", it.url); put("summary", it.summary); put("status", it.status); put("sourceLabel", it.source); put("publishedAt", it.publishedAt); put("confidence", it.confidence); if (it.targetVersion != null) put("targetVersion", it.targetVersion) }) } })
    put("events", JSONArray().apply { state.events.filter { it.start != Instant.EPOCH }.forEach { put(JSONObject().apply { put("title", it.title); put("type", it.type); put("startAt", it.start.toString()); if (it.end != null) put("endAt", it.end.toString()); put("note", it.note); put("sourceUrl", it.sourceUrl); put("status", it.status); put("confidence", it.confidence) }) } })
    put("versions", JSONArray().apply { state.versions.forEach { put(JSONObject().apply { put("version", it.version); put("status", it.status); put("confidence", it.confidence); put("sourceUrls", JSONArray(it.sourceUrls)) }) } })
    put("banners", JSONArray().apply { state.banners.forEach { put(JSONObject().apply { put("title", it.title); put("version", it.version); put("phase", it.phase); put("startAt", it.startAt?.toString()); put("endAt", it.endAt?.toString()); put("status", it.status); put("scheduleStatus", it.scheduleStatus); put("confidence", it.confidence); put("sourceLabel", it.sourceLabel); put("weapon", it.weaponClaim); put("resonatorImages", JSONObject(it.resonatorImages)); put("weaponImages", JSONObject(it.weaponImages)); put("serverTimes", JSONObject().apply { it.serverTimes.forEach { (key, time) -> put(key, JSONObject().apply { put("startAt", time.startAt?.toString()); put("endAt", time.endAt?.toString()) }) } }); put("sourceUrls", JSONArray(it.sourceUrls)) }) } })
    put("resonators", JSONArray().apply { state.resonators.forEach { put(JSONObject().apply { put("name", it.name); put("version", it.version); put("phase", it.phase); put("element", it.element); put("weapon", it.weapon); put("status", it.status); put("confidence", it.confidence); put("sourceUrls", JSONArray(it.sourceUrls)) }) } })
}

private const val MAX_FEED_BYTES = 5 * 1024 * 1024
private const val MAX_FEED_ARRAY_ITEMS = 500

fun parseFeed(raw: String?): FeedResult {
    require(!raw.isNullOrBlank()) { "Feed is empty" }
    require(raw.length <= MAX_FEED_BYTES) { "Feed is too large" }
    val root = try { JSONObject(raw) } catch (error: Exception) { throw IllegalArgumentException("Feed JSON is invalid", error) }
    val fieldNames = root.keys()
    while (fieldNames.hasNext()) {
        val value = root.opt(fieldNames.next())
        require(value !is JSONArray || value.length() <= MAX_FEED_ARRAY_ITEMS) { "Feed contains too many records" }
    }
    val bannerRows = parseBanners(root.optJSONArray("scheduleSnapshots") ?: JSONArray()) +
            parseBanners(root.optJSONArray("banners") ?: JSONArray()) +
            parseBanners(root.optJSONArray("activeBanners") ?: JSONArray()) +
            parseBanners(root.optJSONArray("upcomingBanners") ?: JSONArray())
    val banners = bannerRows.groupBy { it.title.lowercase(Locale.ROOT) + it.version + it.phase }
        .values.mapNotNull { rows -> rows.maxByOrNull { (if (it.weaponClaim.isNullOrBlank()) 0 else 10 + it.weaponClaim.length) + it.weaponImages.size * 3 + it.resonatorImages.size + it.serverTimes.size * 100 + (if (it.scheduleStatus.equals("TEASED", true)) 20 else 0) } }
    return FeedResult(
        parseNews(root.optJSONArray("news") ?: root.optJSONArray("items") ?: JSONArray()),
        parseEvents(root.optJSONArray("events") ?: JSONArray()),
        parseVersions(root.optJSONArray("versions") ?: JSONArray()),
        banners,
        parseResonators(root.optJSONArray("resonators") ?: JSONArray()),
        root.optString("latestVersion").takeIf { it.isNotBlank() } ?: root.optString("version").takeIf { it.isNotBlank() }
    )
}

fun refreshGithubFeed(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    setLoading: (Boolean) -> Unit,
    setMessage: (String) -> Unit,
    setState: (UiState) -> Unit
) {
    setLoading(true)
    scope.launch {
        try {
            val feed = fetchFeedHelper(GITHUB_FEED_URL)
            val fresh = loadState(context).copy(feedUrl = GITHUB_FEED_URL).withFeed(feed)
            saveState(context, fresh)
            setState(fresh)
            setMessage(if (feed.items.isEmpty()) "GitHub feed refreshed; it contains no matching reports yet." else "GitHub feed refreshed · ${feed.items.size} feed entries downloaded")
        } catch (error: Exception) {
            setMessage("GitHub feed refresh failed · ${error.message ?: "check your connection"}")
        } finally {
            setLoading(false)
        }
    }
}

private fun sourceLabel(type: String, name: String, id: String): String = when {
    type.startsWith("X") -> if (name.isNotBlank() && name != id) "X · $name" else "X (indexed)"
    type == "Reddit" -> if (name.isNotBlank() && name != id) "Reddit · $name" else "Reddit"
    type == "Official website" -> if (name.isNotBlank() && name != id) "Official website · $name" else "Official website"
    type == "Website" -> if (name.isNotBlank() && name != id) "Website · $name" else "Website"
    id.startsWith("x-") -> "X (indexed)"
    id.startsWith("reddit-") -> "Reddit"
    else -> "Website · $id"
}

private fun firstEvidenceLabel(o: JSONObject): String {
    val evidence = o.optJSONArray("sources")?.optJSONObject(0) ?: return "Website"
    return sourceLabel(evidence.optString("sourceType"), evidence.optString("sourceName"), evidence.optString("sourceId"))
}

private fun parseNews(a: JSONArray): List<FeedItem> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val id = o.optString("sourceId", "unknown")
        val storedLabel = o.optString("sourceLabel")
        val source = storedLabel.takeIf { it.isNotBlank() } ?: sourceLabel(o.optString("sourceType"), o.optString("sourceName"), id)
        add(FeedItem(o.optString("title"), o.optString("url"), o.optString("summary"), o.optString("status", "COMMUNITY"), source, o.optString("publishedAt"), o.optDouble("confidence", 0.0), o.optString("targetVersion").takeIf { it.isNotBlank() && it != "null" }))
    }
}

private fun parseEvents(a: JSONArray): List<TrackerEvent> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val start = runCatching { Instant.parse(o.optString("startAt", o.optString("start"))) }.getOrNull() ?: continue
        val end = runCatching { Instant.parse(o.optString("endAt", o.optString("end"))) }.getOrNull()
        add(TrackerEvent(o.optString("title"), o.optString("type", "Event"), start, end, o.optString("note", "Source-derived event; verify exact timing in-game."), o.optString("sourceUrl"), o.optString("status", "COMMUNITY"), o.optDouble("confidence", 0.0)))
    }
}

private fun parseVersions(a: JSONArray): List<VersionIntel> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        add(VersionIntel(o.optString("version"), o.optString("status", "COMMUNITY"), o.optDouble("confidence", 0.0), strings(o.optJSONArray("sourceUrls") ?: o.optJSONArray("sources"))))
    }
}

private fun parseBanners(a: JSONArray): List<BannerIntel> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        add(
            BannerIntel(
                o.optString("title"),
                o.optString("version").takeIf { it.isNotBlank() && it != "null" },
                if (o.isNull("phase")) null else o.optInt("phase"),
                instant(o.optString("startAt")),
                instant(o.optString("endAt")),
                o.optString("status", "COMMUNITY"),
                o.optDouble("confidence", 0.0),
                strings(o.optJSONArray("sourceUrls") ?: o.optJSONArray("sources")),
                (o.optString("sourceLabel").takeIf { it.isNotBlank() && it != "null" } ?: firstEvidenceLabel(o)),
                o.optString("weapon").takeIf { it.isNotBlank() && it != "null" },
                stringMap(o.optJSONObject("resonatorImages")),
                stringMap(o.optJSONObject("weaponImages")),
                o.optJSONObject("serverTimes")?.let { times -> buildMap { val keys = times.keys(); while (keys.hasNext()) { val key = keys.next(); val value = times.optJSONObject(key) ?: continue; put(key, BannerServerTiming(instant(value.optString("startAt")), instant(value.optString("endAt")))) } } }.orEmpty(),
                o.optString("scheduleStatus").takeIf { it.isNotBlank() && it != "null" }
            )
        )
    }
}

private fun parseResonators(a: JSONArray): List<ResonatorIntel> = buildList {
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        add(
            ResonatorIntel(
                o.optString("name"),
                o.optString("version").takeIf { it.isNotBlank() && it != "null" },
                if (o.isNull("phase")) null else o.optInt("phase"),
                o.optString("element").takeIf { it.isNotBlank() && it != "null" },
                o.optString("weapon").takeIf { it.isNotBlank() && it != "null" },
                o.optString("status", "COMMUNITY"),
                o.optDouble("confidence", 0.0),
                strings(o.optJSONArray("sourceUrls") ?: o.optJSONArray("sources")),
                (o.optString("sourceLabel").takeIf { it.isNotBlank() && it != "null" } ?: firstEvidenceLabel(o)),
                o.optString("weapon").takeIf { it.isNotBlank() && it != "null" }
            )
        )
    }
}

private fun instant(s: String?) = runCatching { if (s.isNullOrBlank() || s == "null") null else Instant.parse(s) }.getOrNull()
private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else buildList { for (i in 0 until a.length()) add(a.optString(i)) }
private fun stringMap(o: JSONObject?): Map<String, String> = if (o == null) emptyMap() else buildMap { val keys = o.keys(); while (keys.hasNext()) { val key = keys.next(); o.optString(key).takeIf { it.startsWith("https://") }?.let { put(key, it) } } }
fun mergeEvents(base: List<TrackerEvent>, remote: List<TrackerEvent>) = (base + remote).distinctBy { "${it.type}:${it.title}" }
