package com.nightwielder.wuwatracker.worker

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import com.nightwielder.wuwatracker.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore("wuwa_tracker")
private const val CHANNEL_ID = "wuwa_reminders"
private const val RESET_ALARM = 1001
private const val FEED_WORK = "wuwa_feed_sync"
private const val GITHUB_FEED_URL = "https://the-nightwielder.github.io/WuWa-Tracker/feed.json"
private const val MAX_FEED_BYTES = 5 * 1024 * 1024

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            FEED_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<FeedSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    companion object {
        var instance: App? = null
    }
}

fun nextReset(server: Server, now: Instant = Instant.now()): ZonedDateTime {
    val local = now.atZone(ZoneId.of(server.zone))
    var reset = local.toLocalDate().atTime(4, 0).atZone(ZoneId.of(server.zone))
    if (!local.isBefore(reset)) reset = reset.plusDays(1)
    return reset
}

fun nextCustomReminderTime(anchor: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant {
    val selected = anchor.atZone(zone)
    if (selected.toInstant().isAfter(now)) return selected.toInstant()
    val localNow = now.atZone(zone)
    var next = localNow.toLocalDate().atTime(selected.toLocalTime()).atZone(zone)
    if (!next.toInstant().isAfter(now)) next = localNow.toLocalDate().plusDays(1).atTime(selected.toLocalTime()).atZone(zone)
    return next.toInstant()
}

fun hasExactAlarmAccess(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

fun scheduleResetAlarm(server: Server, leadMinutes: Int = 0) {
    if (leadMinutes < 0) return
    val context = App.instance ?: return
    val now = Instant.now()
    val reset = nextReset(server, now)
    var trigger = reset.toInstant().minusSeconds(leadMinutes * 60L)
    if (!trigger.isAfter(now)) {
        val followingReset = nextReset(server, reset.toInstant().plusSeconds(1))
        trigger = followingReset.toInstant().minusSeconds(leadMinutes * 60L)
    }
    val intent = Intent(context, ResetAlarmReceiver::class.java).putExtra("server", server.name).putExtra("lead", leadMinutes)
    val pending = PendingIntent.getBroadcast(context, RESET_ALARM, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
}

fun scheduleCustomReminder(anchor: Instant) {
    val context = App.instance ?: return
    val trigger = nextCustomReminderTime(anchor)
    val intent = Intent(context, ResetAlarmReceiver::class.java).putExtra("custom", true).putExtra("custom_at", anchor.toString())
    val pending = PendingIntent.getBroadcast(context, RESET_ALARM + 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val alarms = context.getSystemService(AlarmManager::class.java)
    if (hasExactAlarmAccess(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
    else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), pending)
}

fun cancelCustomReminder() {
    val context = App.instance ?: return
    val intent = Intent(context, ResetAlarmReceiver::class.java).putExtra("custom", true)
    val pending = PendingIntent.getBroadcast(context, RESET_ALARM + 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java).cancel(pending)
}

fun cancelResetAlarm() {
    val context = App.instance ?: return
    val intent = Intent(context, ResetAlarmReceiver::class.java)
    val pending = PendingIntent.getBroadcast(context, RESET_ALARM, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java).cancel(pending)
}

fun enqueueFeedSync(context: Context, immediate: Boolean = false) {
    if (immediate) {
        val r = OneTimeWorkRequestBuilder<FeedSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("wuwa_feed_now", ExistingWorkPolicy.REPLACE, r)
    }
}

fun openUrl(url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return
    val ctx = App.instance ?: return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val p = goAsync()
            kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                try {
                    val s = com.nightwielder.wuwatracker.loadState(context)
                    if (s.reminderEnabled && s.reminderLead >= 0) scheduleResetAlarm(s.server, s.reminderLead)
                    if (s.reminderEnabled && s.reminderLead == -1) s.customReminderAt?.let(::scheduleCustomReminder)
                    if (s.feedUrl.isNotBlank()) enqueueFeedSync(context)
                } finally {
                    p.finish()
                }
            }
        }
    }
}

class ResetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val custom = intent.getBooleanExtra("custom", false)
        val server = runCatching { Server.valueOf(intent.getStringExtra("server") ?: Server.SEA.name) }.getOrDefault(Server.SEA)
        val lead = intent.getIntExtra("lead", 0)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(
                if (custom) 2002 else 2001,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("Wuthering Waves reminder")
                    .setContentText(if (custom) "Your custom reminder is due." else if (lead == 0) "${server.label} server daily reset is now live." else "${server.label} server reset is in $lead minutes. Finish your dailies and claim Lunite.")
                    .setAutoCancel(true)
                    .build()
            )
        }
        if (custom) {
            val anchor = runCatching { Instant.parse(intent.getStringExtra("custom_at")) }.getOrNull()
            if (anchor != null) scheduleCustomReminder(anchor) else {
                val pending = goAsync()
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        com.nightwielder.wuwatracker.loadState(context).customReminderAt?.let(::scheduleCustomReminder)
                    } finally {
                        pending.finish()
                    }
                }
            }
        } else scheduleResetAlarm(server, lead)
    }
}

class FeedSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val url = GITHUB_FEED_URL
        return runCatching {
            val f = fetchFeedHelper(url)
            applicationContext.dataStore.edit {
                it[stringPreferencesKey("cached_feed")] = JSONObject().apply {
                    put("latestVersion", f.latestVersion)
                    put("news", JSONArray().apply { f.items.forEach { put(JSONObject().apply { put("title", it.title); put("url", it.url); put("summary", it.summary); put("status", it.status); put("sourceLabel", it.source); put("publishedAt", it.publishedAt); put("confidence", it.confidence); if (it.targetVersion != null) put("targetVersion", it.targetVersion) }) } })
                    put("events", JSONArray().apply { f.events.forEach { put(JSONObject().apply { put("title", it.title); put("type", it.type); put("startAt", it.start.toString()); put("endAt", it.end?.toString()); put("note", it.note); put("sourceUrl", it.sourceUrl); put("status", it.status); put("confidence", it.confidence) }) } })
                    put("versions", JSONArray().apply { f.versions.forEach { put(JSONObject().apply { put("version", it.version); put("status", it.status); put("confidence", it.confidence); put("sourceUrls", JSONArray(it.sourceUrls)) }) } })
                    put("banners", JSONArray().apply { f.banners.forEach { put(JSONObject().apply { put("title", it.title); put("version", it.version); put("phase", it.phase); put("startAt", it.startAt?.toString()); put("endAt", it.endAt?.toString()); put("status", it.status); put("confidence", it.confidence); put("sourceLabel", it.sourceLabel); put("weapon", it.weaponClaim); put("resonatorImages", JSONObject(it.resonatorImages)); put("weaponImages", JSONObject(it.weaponImages)); put("sourceUrls", JSONArray(it.sourceUrls)) }) } })
                    put("resonators", JSONArray().apply { f.resonators.forEach { put(JSONObject().apply { put("name", it.name); put("version", it.version); put("phase", it.phase); put("element", it.element); put("weapon", it.weapon); put("status", it.status); put("confidence", it.confidence); put("sourceUrls", JSONArray(it.sourceUrls)) }) } })
                }.toString()
                it[stringPreferencesKey("last_sync")] = Instant.now().toString()
                f.latestVersion?.let { v -> it[stringPreferencesKey("version")] = v }
            }
            Result.success()
        }.getOrElse { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}

suspend fun fetchFeedHelper(url: String): FeedResult = withContext(Dispatchers.IO) {
    require(url.startsWith("https://")) { "HTTPS required" }
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000; instanceFollowRedirects = false
        setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "WuWaTracker/1.3")
    }
    try {
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        if (conn.contentLengthLong > MAX_FEED_BYTES) error("Feed is too large")
        val body = conn.inputStream.use { String(readBoundedHelper(it, MAX_FEED_BYTES), Charsets.UTF_8) }
        com.nightwielder.wuwatracker.parseFeed(body)
    } finally {
        conn.disconnect()
    }
}

private fun readBoundedHelper(stream: java.io.InputStream, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var read: Int
    var total = 0
    while (stream.read(buf).also { read = it } != -1) {
        total += read
        if (total > maxBytes) error("Stream exceeds $maxBytes bytes")
        out.write(buf, 0, read)
    }
    return out.toByteArray()
}
