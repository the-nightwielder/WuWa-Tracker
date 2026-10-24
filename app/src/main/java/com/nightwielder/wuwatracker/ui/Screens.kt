package com.nightwielder.wuwatracker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightwielder.wuwatracker.R
import com.nightwielder.wuwatracker.model.*
import com.nightwielder.wuwatracker.worker.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val GITHUB_FEED_URL = "https://the-nightwielder.github.io/WuWa-Tracker/feed.json"

private fun durationText(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    val days = s / 86400
    val hours = (s % 86400) / 3600
    val mins = (s % 3600) / 60
    val secs = s % 60
    return if (days > 0) "%dd %02dh %02dm".format(days, hours, mins) else "%02dh %02dm %02ds".format(hours, mins, secs)
}

@Composable fun ArtworkScreen(modifier: Modifier, background: Int, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        Image(painterResource(background), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.28f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x990C0B11), Color(0xB0100E15), Color(0x990D0C12)))))
        content()
    }
}

@Composable fun TaskCard(title: String, subtitle: String, done: Boolean, onDone: (Boolean) -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle)
            }
            Checkbox(done, onDone)
        }
    }
}

@Composable fun EventCard(event: TrackerEvent, now: Instant) {
    val active = event.start <= now && (event.end == null || event.end.isAfter(now))
    val remaining = if (event.start == Instant.EPOCH) "Ongoing / Recurring"
    else if (active && event.end != null) "Ends in ${durationText(Duration.between(now, event.end))}"
    else if (event.start > now) "Starts in ${durationText(Duration.between(now, event.start))}"
    else "Ended"
    Card(Modifier.fillMaxWidth().clickable(enabled = event.sourceUrl.isNotBlank()) { openUrl(event.sourceUrl) }) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(5.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(remaining)
            Text(event.note, style = MaterialTheme.typography.bodySmall)
            if (event.status == "LEAK") Text("LEAK · UNCONFIRMED", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable fun InfoCard(title: String, body: String, note: String) {
    Card {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body)
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun HomeScreen(
    state: UiState,
    now: Instant,
    countdown: String,
    localReset: ZonedDateTime,
    onUpdate: ((UiState) -> UiState) -> Unit,
    modifier: Modifier
) {
    val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
    val localClock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(now)
    val todayResetKey = com.nightwielder.wuwatracker.resetKey(state.server, now)
    val dailyEarned = (if (state.dailyDone) 60 else 0) + (if (state.luniteDone) 90 else 0)
    val weeklyTodayEarned = if (state.weeklyDone && state.weeklyDoneDate == todayResetKey) 160 else 0
    val totalTodayEarned = dailyEarned + weeklyTodayEarned

    ArtworkScreen(modifier, R.drawable.bg_home) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Today", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White) }
            item {
                Card {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) {
                        Text("Next server reset")
                        Text(countdown, style = MaterialTheme.typography.headlineMedium)
                        Text(state.server.label + " • 04:00 server time")
                        Text("Your local time now: " + localClock)
                        Text("Next reset locally: " + fmt.format(localReset))
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2E273A))) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Astrites Earned Today", style = MaterialTheme.typography.labelMedium, color = Color(0xFFBFE8E2))
                            Text("Checklist Rewards", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                        Surface(
                            color = Color(0x33FFD994),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "+$totalTodayEarned Astrites",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFFFFD994),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
            item { Text("Daily Checklist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White) }
            item { TaskCard("Daily Activities", "Reach 100 Activity Points • +60 Astrites", state.dailyDone) { v -> onUpdate { it.copy(dailyDone = v) } } }
            item { TaskCard("Lunite Subscription", "Claim today's reward • +90 Astrites", state.luniteDone) { v -> onUpdate { it.copy(luniteDone = v) } } }
            item { Text("Weekly Checklist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color.White) }
            item {
                TaskCard("Weekly Activity Points", "Collect 6000 points (Phantasma / Gateways) • +160 Astrites", state.weeklyDone) { v ->
                    onUpdate {
                        it.copy(
                            weeklyDone = v,
                            weeklyDoneDate = if (v) com.nightwielder.wuwatracker.resetKey(it.server, now) else ""
                        )
                    }
                }
            }
            item { Text("Monthly Endgame Checklist", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), fontWeight = FontWeight.Bold, color = Color(0xFFFFD994)) }
            item { Text("These checkboxes reset with the server's calendar month. Exact active phases should be verified in-game.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE2DDEA)) }
            item { TaskCard("Tower of Adversity", "Complete this Tower cycle", state.monthlyTower) { v -> onUpdate { it.copy(monthlyTower = v) } } }
            item { TaskCard("Whimpering Wastes", "Complete this challenge cycle", state.monthlyWastes) { v -> onUpdate { it.copy(monthlyWastes = v) } } }
            item { TaskCard("Endstate Matrix", "Complete this challenge cycle", state.monthlyMatrix) { v -> onUpdate { it.copy(monthlyMatrix = v) } } }
        }
    }
}

@Composable fun EndgameScreen(state: UiState, now: Instant, modifier: Modifier) {
    ArtworkScreen(modifier, R.drawable.bg_endgame) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Endgame", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF3E9FF)) }
            item { Text("Permanent and recurring content. Exact active phases should be verified in-game.", color = Color(0xFFE2DDEB)) }
            items(state.events.filter { it.type.equals("Endgame", true) }) { EventCard(it, now) }
        }
    }
}

fun showCustomReminderPicker(context: Context, onSelected: (Instant) -> Unit) {
    val initial = java.time.LocalDateTime.now().plusHours(1)
    android.app.DatePickerDialog(context, { _, year, month, day ->
        android.app.TimePickerDialog(context, { _, hour, minute ->
            val at = java.time.LocalDate.of(year, month + 1, day).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant()
            if (at.isAfter(Instant.now())) onSelected(at)
        }, initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply { datePicker.minDate = System.currentTimeMillis() }.show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(
    state: UiState,
    update: ((UiState) -> UiState) -> Unit,
    showCustomReminderPickerParam: (Context, (Instant) -> Unit) -> Unit = ::showCustomReminderPicker,
    modifier: Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var serverExpanded by remember { mutableStateOf(false) }
    var leadExpanded by remember { mutableStateOf(false) }
    var exactAlarmAccess by remember { mutableStateOf(hasExactAlarmAccess(context)) }
    var showExactAlarmPrompt by remember { mutableStateOf(false) }
    val exactAlarmSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exactAlarmAccess = hasExactAlarmAccess(context)
        if (exactAlarmAccess && state.reminderEnabled && state.reminderLead == -1) state.customReminderAt?.let(::scheduleCustomReminder)
    }
    val onCustomReminderSelected: (Instant) -> Unit = { selected ->
        update { it.copy(customReminderAt = selected) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !hasExactAlarmAccess(context)) showExactAlarmPrompt = true
    }
    val options = listOf(0 to "At reset", 15 to "15 minutes before", 30 to "30 minutes before", 60 to "1 hour before", -1 to "Custom date & time")
    ArtworkScreen(modifier, R.drawable.bg_settings) {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF3E9FF)) }
            item { ExposedDropdownMenuBox(serverExpanded, { serverExpanded = !serverExpanded }) { OutlinedTextField(state.server.label, {}, readOnly = true, label = { Text("Game Server") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(serverExpanded, { serverExpanded = false }) { Server.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { update { it.copy(server = s, serverSelected = true) }; serverExpanded = false }) } } } }
            item { Text("Reset timezone: ${ZoneId.of(state.server.zone)}") }
            item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Enable reset reminder"); Text("Turn this on to receive a reminder for the daily reset of your selected server.") }; Switch(state.reminderEnabled, { checked -> update { it.copy(reminderEnabled = checked) } }) } }
            item { ExposedDropdownMenuBox(leadExpanded, { leadExpanded = !leadExpanded }) { OutlinedTextField(options.first { it.first == state.reminderLead }.second, {}, readOnly = true, label = { Text("Reminder Timing") }, modifier = Modifier.menuAnchor().fillMaxWidth()); ExposedDropdownMenu(leadExpanded, { leadExpanded = false }) { options.forEach { (v, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { update { it.copy(reminderLead = v) }; leadExpanded = false; if (v == -1) showCustomReminderPicker(context, onCustomReminderSelected) }) } } } }
            if (state.reminderLead == -1) {
                item {
                    TextButton(onClick = { showCustomReminderPicker(context, onCustomReminderSelected) }) {
                        Text(state.customReminderAt?.let {
                            val next = nextCustomReminderTime(it).atZone(ZoneId.systemDefault())
                            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(next)
                            val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(next).uppercase(Locale.ROOT)
                            "Next custom reminder: $date, $time"
                        } ?: "Choose reminder date and time")
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !exactAlarmAccess) item {
                    Column {
                        Text("Android may delay this reminder. Allow Alarms & reminders access for delivery closer to the selected time.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showExactAlarmPrompt = true }) { Text("Allow exact alarms") }
                    }
                }
            }
            item { Text("Intelligence Feed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD994)) }
            item { InfoCard("Live Intelligence", GITHUB_FEED_URL, "Sources include official notices, Reddit leaks, WuWa Banners, GenGamer, u7buy and LDShop. Leaks stay unconfirmed.") }
            item { Text("Security", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFFBFE8E2)) }
            item { InfoCard("Local-first", "No game login, password, cookies or account tokens are collected.", "Only the configured HTTPS feed is requested by the app.") }
            item { InfoCard("Leak Safety", "Leaks remain clearly marked as unconfirmed.", "Check the linked source before treating a claim as confirmed.") }
        }
        if (showExactAlarmPrompt) AlertDialog(
            onDismissRequest = { showExactAlarmPrompt = false },
            title = { Text("Allow on-time custom reminders?") },
            text = { Text("Android requires Alarms & reminders access to deliver this custom reminder close to the selected time. If you decline, the reminder will remain enabled but Android may deliver it late.") },
            confirmButton = {
                TextButton(onClick = {
                    showExactAlarmPrompt = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}"))
                        exactAlarmSettingsLauncher.launch(intent)
                    }
                }) { Text("Open permission settings") }
            },
            dismissButton = { TextButton(onClick = { showExactAlarmPrompt = false }) { Text("Not now") } }
        )
    }
}
