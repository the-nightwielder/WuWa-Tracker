package com.nightwielder.wuwatracker.ui.intel

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nightwielder.wuwatracker.data.*
import com.nightwielder.wuwatracker.model.*
import com.nightwielder.wuwatracker.ui.*
import com.nightwielder.wuwatracker.worker.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class IntelGroup(val title: String, val status: String, val emptyMessage: String)

private fun formatBannerTime(i: Instant, server: Server, usesServerTime: Boolean): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)
        .withZone(if (usesServerTime) server.bannerZone else ZoneId.systemDefault()).format(i)

private fun phaseLabel(phase: Int?): String? = when (phase) {
    1 -> "Phase I"; 2 -> "Phase II"; 3 -> "Phase III"; 4 -> "Phase IV"; else -> null
}

private fun FeedItem.isNextVersionLeak(
    currentVersion: String,
    confirmedResonators: Set<String> = emptySet(),
    confirmedUpcomingVersions: Set<String> = emptySet()
): Boolean {
    val text = title + " " + summary

    // Exclude leaks if they mention any confirmed active or upcoming resonator
    val hasConfirmedResonator = confirmedResonators.any { name ->
        name.length > 2 && Regex("(?i)(?<![a-z])" + Regex.escape(name) + "(?![a-z])").containsMatchIn(text)
    }
    if (hasConfirmedResonator) {
        return false
    }

    val versionPattern = Regex("(?i)(?:(?:version|ver\\.?|wuthering\\s+waves|wuwa)\\s*|\\bv\\s*)(\\d+\\.\\d+)")
    val headlineVersions = versionPattern.findAll(title).map { it.groupValues[1] }.toList()
    val versions = (headlineVersions.ifEmpty { versionPattern.findAll(text).map { it.groupValues[1] }.toList() }).toMutableList()
    if (versions.isEmpty() && Regex("(?i)wuthering\\s+waves|\\bwuwa\\b").containsMatchIn(text)) {
        versions += Regex("\\b(\\d+\\.\\d+)\\b").findAll(text).map { it.groupValues[1] }.toList()
    }

    // Target unconfirmed versions beyond any confirmed upcoming version
    val highestConfirmed = confirmedUpcomingVersions.maxWithOrNull { a, b -> compareVersion(a, b) } ?: currentVersion
    val base = if (compareVersion(highestConfirmed, currentVersion) > 0) highestConfirmed else currentVersion
    val parts = Regex("\\d+\\.\\d+").find(base)?.value?.split(".")?.mapNotNull { it.toIntOrNull() } ?: return false
    val nextTarget = "${parts.getOrElse(0) { 0 }}.${parts.getOrElse(1) { 0 } + 1}"

    if (versions.isNotEmpty()) {
        // If all mentioned versions are <= highest confirmed version (e.g. 3.8), it is not a future unconfirmed leak
        if (versions.all { compareVersion(it, highestConfirmed) <= 0 }) return false
        return versions.any { compareVersion(it, nextTarget) >= 0 }
    }
    return Regex("(?i)\\b(?:next\\s+(?:version|patch|update|resonator)|upcoming\\s+(?:version|resonator|banner)|beta\\s+(?:for|of)\\s+(?:the\\s+)?next\\s+(?:version|patch))\\b").containsMatchIn(text)
}

private fun FeedItem.leakDedupKey(currentVersion: String, resonators: List<ResonatorIntel>): String {
    val nextVersion = Regex("(?i)\\b(?:version\\s*|v\\s*|wuwa\\s*|wuthering\\s+waves\\s*)(\\d+\\.\\d+)").find(title)?.groupValues?.get(1) ?: currentVersion
    val subjects = resonators.map { it.name.trim() }.filter { it.isNotBlank() && Regex("(?i)(?<![a-z])" + Regex.escape(it) + "(?![a-z])").containsMatchIn(title) }.distinctBy { it.lowercase(Locale.ROOT) }
    val stop = setOf("wuthering", "waves", "wuwa", "version", "banner", "banners", "leak", "leaks", "pulling", "strategy", "release", "date", "expected", "rerun", "reruns", "prediction", "predictions", "new", "character", "upcoming", "phase", "update", "guide", "more", "and", "the", "for", "with", "from", "official", "confirmed", "unconfirmed", "u7buy", "ldshop", "uutop", "mone")
    val words = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.length > 2 && it !in stop && !Regex("\\d+\\.\\d+").matches(it) }
    val topic = subjects.joinToString(",") { it.lowercase(Locale.ROOT) }.ifBlank { words.take(4).joinToString(" ").ifBlank { "general" } }
    val publisher = source.substringAfterLast("·", source).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    return "$publisher|$nextVersion|$topic"
}

private fun compareVersion(a: String, b: String): Int {
    val x = Regex("\\d+\\.\\d+").find(a)?.value?.split(".")?.map { it.toInt() } ?: return 0
    val y = Regex("\\d+\\.\\d+").find(b)?.value?.split(".")?.map { it.toInt() } ?: return 0
    for (i in 0 until maxOf(x.size, y.size)) {
        val n = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (n != 0) return n
    }
    return 0
}

private fun FeedItem.relevant(version: String, now: Instant): Boolean {
    val mentions = Regex("(?i)(?:(?:version|ver\\.?|wuthering\\s+waves)\\s*|\\bv\\s*)(\\d+\\.\\d+)").findAll(title + " " + summary).map { it.groupValues[1] }.toList()
    val targetIsCurrentOrNewer = targetVersion?.takeIf { Regex("\\d+\\.\\d+").matches(it) }?.let { compareVersion(it, version) >= 0 } == true
    val date = runCatching { Instant.parse(publishedAt) }.getOrNull()
    if (status.equals("OFFICIAL", true)) return date != null && Duration.between(date, now).abs() <= Duration.ofDays(60)
    if (mentions.any { compareVersion(it, version) < 0 } && mentions.none { compareVersion(it, version) >= 0 } && !targetIsCurrentOrNewer) return false
    if (date == null) return targetIsCurrentOrNewer || mentions.any { compareVersion(it, version) >= 0 }
    return targetIsCurrentOrNewer || mentions.any { compareVersion(it, version) >= 0 } || Duration.between(date, now).abs() <= Duration.ofDays(60)
}

private fun relativeTime(raw: String): String {
    val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return "time unavailable"
    val seconds = Duration.between(instant, Instant.now()).seconds
    val mins = seconds / 60
    val hours = mins / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        mins < 60 -> "${mins}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${days}d ago"
    }
}

data class PromoCodeItem(
    val code: String,
    val label: String,
    val expiresAt: Instant? = null
)

fun extractPromoCodesWithDetails(feedItems: List<FeedItem>, now: Instant): List<PromoCodeItem> {
    val exclude = setOf(
        "WUTHERING", "RESONATOR", "ASTRITES", "LUNITE", "CONVENE", "SOLARIS", "JADEHAVEN",
        "KUMOKIRI", "MOONGAZER", "COMMUNITY", "OFFICIAL", "PREVIEW", "VERSION", "UPDATE",
        "SUMMARY", "DESKTOP", "SPECIAL", "BROADCAST", "CHARACTER", "SCHEDULE", "FEATURED",
        "ACTIVITIES", "NOVEMBER", "DECEMBER", "OCTOBER", "JANUARY", "DISCORD", "TWITTER",
        "YOUTUBE", "WAVES", "PORTAL", "WEAPON", "PHASE", "REWARD", "GUIDE", "DETAILS", "CHECKLIST"
    )
    val codeRegex = Regex("\\b[A-Z0-9]{5,16}\\b")
    val dateRegex = Regex("(?i)(?:valid|available|ends?|expiry|expires?|till|until)\\s*(?:through|till|until|to)?\\s*:?\\s*([A-Za-z]{3,9}\\s+\\d{1,2}(?:,\\s*\\d{4})?(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)?)?(?:\\s*\\([A-Za-z0-9+-]+\\))?|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)?)?(?:\\s*\\([A-Za-z0-9+-]+\\))?)")

    val results = mutableListOf<PromoCodeItem>()
    for (item in feedItems) {
        val text = item.title + " " + item.summary
        val isCodePost = Regex("(?i)\\b(?:code|codes|redeem|redemption|gift|broadcast|stream|reward)\\b").containsMatchIn(text)
        if (!isCodePost) continue

        var expiresAt: Instant? = null
        val dateMatch = dateRegex.find(text)
        if (dateMatch != null) {
            val dateStr = dateMatch.groupValues[1]
            expiresAt = parseDateString(dateStr)
        }
        if (expiresAt == null) {
            val pub = runCatching { Instant.parse(item.publishedAt) }.getOrNull()
            if (pub != null) {
                val isBroadcast = Regex("(?i)broadcast|stream|preview|special").containsMatchIn(text)
                expiresAt = pub.plus(Duration.ofDays(if (isBroadcast) 3 else 30))
            }
        }

        if (expiresAt != null && now.isAfter(expiresAt)) continue

        val matches = codeRegex.findAll(text)
        for (m in matches) {
            val candidate = m.value
            if (candidate in exclude || candidate.startsWith("HTTP")) continue
            val isAlphaOnly = candidate.all { it.isLetter() }
            if (isAlphaOnly) {
                val idx = text.indexOf(candidate)
                val window = text.substring(maxOf(0, idx - 40), minOf(text.length, idx + candidate.length + 40)).lowercase(Locale.ROOT)
                val nearCodeKeyword = listOf("code", "redeem", "gift", "reward", "convene", "astrite", "free").any { it in window }
                if (!nearCodeKeyword) continue
            }
            results.add(
                PromoCodeItem(
                    candidate,
                    if (item.title.contains("Broadcast", true) || item.title.contains("Stream", true) || item.title.contains("Preview", true)) "Livestream Reward" else "Event / Community Reward",
                    expiresAt
                )
            )
        }
    }
    return results.distinctBy { it.code }
}

private fun String.matchesSearchQuery(query: String): Boolean {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isBlank()) return true
    val content = this.lowercase(Locale.ROOT)
    if (content.contains(q)) return true

    val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }

    fun matchToken(token: String): Boolean {
        if (content.contains(token)) return true
        val stem = when {
            token.endsWith("ies") -> token.dropLast(3) + "y"
            token.endsWith("es") && (token.endsWith("shes") || token.endsWith("ches") || token.endsWith("xes")) -> token.dropLast(2)
            token.endsWith("s") && !token.endsWith("ss") -> token.dropLast(1)
            else -> token
        }
        val variants = setOf(token, stem, stem + "s", stem + "es")
        if (variants.any { content.contains(it) }) return true

        when (stem) {
            "weapon" -> {
                val weaponTerms = listOf("weapon", "sword", "broadblade", "pistol", "rectifier", "gauntlet", "signature")
                if (weaponTerms.any { content.contains(it) }) return true
            }
            "resonator", "character" -> {
                val charTerms = listOf("resonator", "character", "glacio", "fusion", "electro", "aero", "spectro", "havoc")
                if (charTerms.any { content.contains(it) }) return true
            }
            "code", "promo" -> {
                val codeTerms = listOf("code", "redeem", "redemption", "promo", "astrite", "gift")
                if (codeTerms.any { content.contains(it) }) return true
            }
            "material" -> {
                val matTerms = listOf("material", "ascension", "forgery", "weekly", "boss", "drop", "talent")
                if (matTerms.any { content.contains(it) }) return true
            }
        }
        return false
    }

    return tokens.all { matchToken(it) }
}

private fun parseDateString(raw: String): Instant? = runCatching {
    val clean = raw.replace(Regex("\\s*\\([A-Za-z0-9+-]+\\)\\s*"), "").trim()
    val zone = ZoneId.of("America/Los_Angeles")
    val formatsWithTime = listOf(
        "M/d/yyyy H:mm", "M/d/yyyy h:mm a", "d/M/yyyy H:mm", "d/M/yyyy h:mm a",
        "MMM d, yyyy h:mm a", "MMM d yyyy h:mm a", "MMM d, yyyy H:mm", "MMM d yyyy H:mm",
        "yyyy-MM-dd H:mm", "yyyy-MM-dd h:mm a"
    )
    for (f in formatsWithTime) {
        val parsed = runCatching {
            val dtf = DateTimeFormatter.ofPattern(f, Locale.US)
            java.time.LocalDateTime.parse(clean, dtf).atZone(zone).toInstant()
        }.getOrNull()
        if (parsed != null) return parsed
    }
    val formatsDateOnly = listOf(
        "MMM d, yyyy", "MMM d yyyy", "d/M/yyyy", "M/d/yyyy", "yyyy-MM-dd"
    )
    for (f in formatsDateOnly) {
        val parsed = runCatching {
            val dtf = DateTimeFormatter.ofPattern(f, Locale.US)
            java.time.LocalDate.parse(clean, dtf).atTime(23, 59, 59).atZone(zone).toInstant()
        }.getOrNull()
        if (parsed != null) return parsed
    }
    null
}.getOrNull()

fun extractPromoCodes(text: String): List<String> =
    extractPromoCodesWithDetails(listOf(FeedItem("", "", text, "", "", "", 0.0)), Instant.now()).map { it.code }

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
    val clip = android.content.ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        android.widget.Toast.makeText(context, "Copied $text to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun BannerIntel.relevantBanner(version: String, now: Instant): Boolean =
    if (scheduleStatus.equals("TEASED", true)) true
    else if (this.version != null) compareVersion(this.version, version) >= 0
    else (endAt?.isAfter(now) == true || startAt?.isAfter(now) == true)

@Composable fun IntelScreen(state: UiState, now: Instant, syncing: Boolean, syncMessage: String, onRefresh: () -> Unit, onToggle: (String) -> Unit, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val groups = listOf(IntelGroup("Official Confirmations", "OFFICIAL", "No recent official updates for this version."), IntelGroup("Unconfirmed Leaks", "LEAK", "No upcoming version-relevant leak reports."), IntelGroup("Community Reports", "COMMUNITY", "No recent version-relevant community reports."))
    val version = state.version.takeIf { it.isNotBlank() && !it.equals("Unknown", true) && !it.equals("null", true) }
        ?: state.banners.firstOrNull { it.scheduleStatus.equals("CURRENT", true) }?.version
        ?: state.versions.firstOrNull()?.version
        ?: ""
    val feed = state.feedItems.filter { it.relevant(version, now) }.sortedByDescending { runCatching { Instant.parse(it.publishedAt) }.getOrDefault(Instant.EPOCH) }
    val banners = state.banners.filter { it.relevantBanner(version, now) }

    val confirmedUpcoming = remember(state.banners, now) {
        state.banners.filter { b ->
            val t = b.serverTimes[state.server.name]
            (t?.startAt?.isAfter(now) == true) || (t == null && b.scheduleStatus.equals("UPCOMING", true))
        }
    }
    val confirmedResonators = remember(state.banners) {
        state.banners.flatMap { b ->
            b.title.split(Regex("\\s*,\\s*|\\s+and\\s+")).map { it.trim().lowercase(Locale.ROOT) }
        }.filter { it.isNotBlank() }.toSet()
    }
    val confirmedUpcomingVersions = remember(confirmedUpcoming) {
        confirmedUpcoming.mapNotNull { it.version }.toSet()
    }

    val activeCodes = remember(feed, now) {
        extractPromoCodesWithDetails(feed, now).take(4)
    }

    val filteredFeed = remember(feed, searchQuery) {
        if (searchQuery.isBlank()) feed
        else feed.filter {
            val text = "${it.title} ${it.summary} ${it.source}"
            text.matchesSearchQuery(searchQuery)
        }
    }

    ArtworkScreen(modifier, com.nightwielder.wuwatracker.R.drawable.bg_intel) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Intelligence Feed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF3E9FF)) }
            item { InfoCard("Public Intelligence Feed", if (syncing) "Refreshing…" else feed.size.toString() + " version-relevant reports · " + banners.size + " relevant banner claims", listOf(syncMessage.takeIf { it.isNotBlank() }, state.lastSync.takeIf { it.isNotBlank() }?.let { "Last Sync: " + relativeTime(it) }).filterNotNull().joinToString(" · ").ifBlank { "Updated from free public sources." }) }
            item { Button(onClick = onRefresh, enabled = !syncing, modifier = Modifier.fillMaxWidth()) { Text(if (syncing) "Refreshing…" else "Refresh Intelligence Feed") } }

            // Top Active Redemption Codes Section
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF292438)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("🎁 Active Redemption Codes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFFD994))
                        if (activeCodes.isEmpty()) {
                            Text("No active promo codes found in recent feed announcements.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2DDEA))
                        } else {
                            activeCodes.forEach { codeItem ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(codeItem.code, fontWeight = FontWeight.Bold, color = Color.White)
                                        val expirationText = codeItem.expiresAt?.let { exp ->
                                            val remaining = Duration.between(now, exp)
                                            if (remaining.isNegative) "Expired"
                                            else if (remaining.toHours() in 1..48) "⏱ Expires in ${remaining.toHours()}h"
                                            else "Valid till " + DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneId.systemDefault()).format(exp)
                                        }
                                        val subtitle = listOfNotNull(codeItem.label, expirationText).joinToString(" • ")
                                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFFBFE8E2))
                                    }
                                    Button(
                                        onClick = { copyToClipboard(context, "Promo Code", codeItem.code) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFD994), contentColor = Color(0xFFFFD994)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("📋 Copy: ${codeItem.code}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Search Filter Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search news, codes, or keywords…", color = Color.LightGray) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0x3326323C),
                        unfocusedContainerColor = Color(0x2226323C),
                        focusedBorderColor = Color(0xFFFFD994),
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }

            groups.forEach { g ->
                val query = searchQuery.trim()
                val candidates = filteredFeed.filter { it.status.equals(g.status, true) && (g.status != "LEAK" || it.isNextVersionLeak(version, confirmedResonators, confirmedUpcomingVersions)) }
                val rows = (if (g.status == "LEAK") candidates.distinctBy { it.leakDedupKey(version, state.resonators) } else candidates).take(35)
                val shownArticleUrls = rows.map { it.url }.toSet()
                val highestConfirmed = confirmedUpcomingVersions.maxWithOrNull { a, b -> compareVersion(a, b) } ?: version

                val gb = banners.filter { b ->
                    val bannerSearchText = listOfNotNull(b.title, b.sourceLabel, b.version?.let { "Version $it" }, b.weaponClaim).joinToString(" ")
                    val matchesSearch = query.isBlank() || bannerSearchText.matchesSearchQuery(query)
                    val isExcludedFromLeaks = (b.version?.let { v -> compareVersion(v, highestConfirmed) <= 0 } ?: false) ||
                            confirmedResonators.any { r -> b.title.contains(r, true) }

                    matchesSearch &&
                    b.scheduleStatus != "TEASED" &&
                    b.status.equals(g.status, true) &&
                    (g.status != "LEAK" || (!isExcludedFromLeaks && b.version?.let { v -> compareVersion(v, highestConfirmed) > 0 } == true && b.sourceUrls.none { url -> url in shownArticleUrls }))
                }.take(8)

                val gr = state.resonators.filter { r ->
                    val resSearchText = listOfNotNull(r.name, r.element, r.weapon, r.version?.let { "Version $it" }, r.sourceLabel).joinToString(" ")
                    val matchesSearch = query.isBlank() || resSearchText.matchesSearchQuery(query)
                    val isResExcluded = (r.version?.let { v -> compareVersion(v, highestConfirmed) <= 0 } ?: false) ||
                            confirmedResonators.contains(r.name.trim().lowercase(Locale.ROOT))

                    matchesSearch &&
                    r.status.equals(g.status, true) &&
                    if (g.status == "LEAK") {
                        !isResExcluded && r.version?.let { v -> compareVersion(v, highestConfirmed) > 0 } == true && r.sourceUrls.none { url -> url in shownArticleUrls }
                    } else {
                        (r.version?.let { v -> compareVersion(v, version) >= 0 } == true || feed.any { n -> n.title.contains(r.name, true) || n.summary.contains(r.name, true) })
                    }
                }.take(8)
                val closed = g.status in state.collapsedSections
                item(key = "section-" + g.status) { SectionHeader(g, closed) { onToggle(g.status) } }
                if (!closed) {
                    if (rows.isEmpty() && gb.isEmpty() && gr.isEmpty()) item { EmptyIntelCard(g.title, g.emptyMessage) }
                    items(rows, key = { "news-" + g.status + "-" + it.url }) { FeedCard(it) }
                    items(gb, key = { "banner-" + g.status + "-" + it.title }) { b -> IntelCard(b.title, listOfNotNull(b.version?.let { "Version " + it }, b.phase?.let { phaseLabel(it) }, b.scheduleStatus?.let { "Banner status: " + it.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercase) }, b.status, "Source: " + b.sourceLabel).joinToString(" · "), b.sourceUrls.firstOrNull()) }
                    items(gr, key = { "res-" + g.status + "-" + it.name }) { r -> IntelCard(r.name, listOfNotNull(r.version?.let { "Version " + it }, r.phase?.let { phaseLabel(it) }, r.element, r.weapon, g.status, "Source: " + r.sourceLabel).joinToString(" · "), r.sourceUrls.firstOrNull()) }
                }
            }
        }
    }
}

@Composable private fun SectionHeader(g: IntelGroup, closed: Boolean, toggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = toggle), colors = CardDefaults.cardColors(containerColor = when (g.status) { "OFFICIAL" -> Color(0xFF263746); "LEAK" -> Color(0xFF482D3C); else -> Color(0xFF303044) })) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text(if (closed) "Tap to expand" else if (g.status == "LEAK") "LEAKS" else g.status, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
            }
            ChevronControl(closed)
        }
    }
}

@Composable private fun ChevronControl(collapsed: Boolean) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.5.dp.toPx()
            if (collapsed) {
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .40f), androidx.compose.ui.geometry.Offset(size.width * .50f, size.height * .68f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width * .50f, size.height * .68f), androidx.compose.ui.geometry.Offset(size.width * .78f, size.height * .40f), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width * .22f, size.height * .60f), androidx.compose.ui.geometry.Offset(size.width * .50f, size.height * .32f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(size.width * .50f, size.height * .32f), androidx.compose.ui.geometry.Offset(size.width * .78f, size.height * .60f), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable fun BannerOverviewCard(b: BannerIntel, current: Boolean, server: Server, cacheExpiresAt: Long, now: Instant = Instant.now()) {
    var expanded by remember(b.title, current) { mutableStateOf(false) }
    val serverTiming = b.serverTimes[server.name]?.takeIf { it.startAt != null && it.endAt != null }
    val startAt = serverTiming?.startAt ?: b.startAt
    val endAt = serverTiming?.endAt ?: b.endAt
    val durationText = remember(startAt, endAt, now, current) {
        if (current && endAt != null && endAt.isAfter(now)) {
            val d = Duration.between(now, endAt)
            val s = d.seconds.coerceAtLeast(0)
            val days = s / 86400
            val hours = (s % 86400) / 3600
            val mins = (s % 3600) / 60
            "Ends in ${days}d ${hours}h ${mins}m"
        } else if (!current && startAt != null && startAt.isAfter(now)) {
            val d = Duration.between(now, startAt)
            val s = d.seconds.coerceAtLeast(0)
            val days = s / 86400
            val hours = (s % 86400) / 3600
            val mins = (s % 3600) / 60
            "Starts in ${days}d ${hours}h ${mins}m"
        } else null
    }
    val scheduleRange = listOfNotNull(startAt?.let { formatBannerTime(it, server, serverTiming != null) }, endAt?.let { formatBannerTime(it, server, serverTiming != null) }).let { times -> if (times.size == 2) "${times[0]} – ${times[1]}" else times.firstOrNull().orEmpty() }
    val userTzCode = runCatching {
        val tz = java.util.TimeZone.getDefault()
        val isDaylight = tz.inDaylightTime(java.util.Date())
        val shortName = tz.getDisplayName(isDaylight, java.util.TimeZone.SHORT, Locale.US)
        if (shortName.startsWith("GMT+") || shortName.startsWith("GMT-") || shortName.startsWith("UTC+")) {
            when (tz.id) {
                "Asia/Kolkata", "Asia/Calcutta" -> "IST"
                "Asia/Singapore" -> "SGT"
                "Asia/Tokyo" -> "JST"
                "Asia/Shanghai" -> "CST"
                "Europe/London" -> if (isDaylight) "BST" else "GMT"
                "America/New_York" -> if (isDaylight) "EDT" else "EST"
                "America/Los_Angeles" -> if (isDaylight) "PDT" else "PST"
                else -> shortName
            }
        } else shortName
    }.getOrDefault("Local")
    val localRange = startAt?.let { st ->
        val userZone = ZoneId.systemDefault()
        val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(userZone)
        val dateFmt = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(userZone)
        val serverZone = if (serverTiming != null) server.bannerZone else ZoneId.systemDefault()
        val serverStartFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(serverZone).format(st)
        val localStartFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(userZone).format(st)
        val startStr = if (serverStartFmt == localStartFmt) timeFmt.format(st) else dateFmt.format(st)
        val endStr = endAt?.let { et ->
            val serverEndFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(serverZone).format(et)
            val localEndFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(userZone).format(et)
            if (serverEndFmt == localEndFmt) timeFmt.format(et) else dateFmt.format(et)
        }
        listOfNotNull(startStr, endStr).joinToString(" – ")
    }.orEmpty()
    val isSameOffset = serverTiming != null && ZoneId.systemDefault().rules.getOffset(Instant.now()) == server.bannerZone.rules.getOffset(Instant.now())
    val scheduleSuffix = if (serverTiming != null) {
        if (!isSameOffset && localRange.isNotBlank()) " (${server.label} Server Time · $localRange $userTzCode)" else " (${server.label} Server Time)"
    } else if (startAt != null || endAt != null) {
        " ($userTzCode Time)"
    } else ""
    val missingScheduleLabel = if (b.serverTimes.isEmpty() && b.startAt == null && b.endAt == null) "Exact server schedule unavailable" else "No schedule for ${server.label} server"
    val scheduleDescription = if (scheduleRange.isNotBlank()) "${if (current) "From " else "Starts "}$scheduleRange$scheduleSuffix" else missingScheduleLabel
    val bannerSummary = listOfNotNull(b.version?.let { "Version $it" }, b.phase?.let { phaseLabel(it) }, scheduleDescription).joinToString(" · ")
    val expandedSummary = listOfNotNull(b.version?.let { "Version $it" }, b.phase?.let { phaseLabel(it) }, scheduleRange.takeIf { it.isNotBlank() }?.plus(scheduleSuffix)).joinToString(" · ").ifBlank { missingScheduleLabel }
    val names = b.title.split(Regex("\\s*,\\s*|\\s+and\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }.take(3)
    val mappedWeapons = names.mapNotNull { WeaponMapping.resolveSignatureWeapon(it) }.distinctBy { it.lowercase(Locale.ROOT) }
    val rawWeapons = b.weaponClaim?.split(Regex("\\s*,\\s*|\\s+and\\s+|\\s*;\\s*"))?.map { it.trim() }?.filter { it.isNotBlank() }?.distinctBy { it.lowercase(Locale.ROOT) }.orEmpty()
    val weapons = (if (rawWeapons.size >= names.size && names.isNotEmpty()) rawWeapons else if (mappedWeapons.isNotEmpty()) mappedWeapons else rawWeapons).take(3)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (current) Color(0xFF292438) else Color(0xFF26323C))) {
        Column {
            durationText?.let { text ->
                Surface(
                    color = Color(0xFFFFD994),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF16131D),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    val lead = names.firstOrNull().orEmpty()
                    if (!expanded) {
                        ResonatorArtwork(lead, b.resonatorImages.artworkFor(lead), cacheExpiresAt)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(if (b.scheduleStatus.equals("TEASED", true)) "TEASED BANNER" else if (current) "ACTIVE BANNER" else "UPCOMING BANNER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(lead.ifBlank { b.title }, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), fontWeight = FontWeight.Bold, color = if (current) Color(0xFFFFD994) else Color(0xFFBFE8E2))
                            Text(bannerSummary, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2DDEA))
                        }
                    } else {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (b.scheduleStatus.equals("TEASED", true)) "TEASED BANNER DETAILS" else if (current) "ACTIVE BANNER DETAILS" else "UPCOMING BANNER DETAILS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (current) Color(0xFFFFD994) else Color(0xFFBFE8E2))
                            Text(expandedSummary, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2DDEA))
                        }
                    }
                    ChevronControl(!expanded)
                }
                if (expanded && b.status.equals("COMMUNITY", true)) Text("Community source · ${b.sourceLabel}", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                if (expanded) {
                    Text("Resonators", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (current) Color(0xFFFFD994) else Color(0xFFBFE8E2))
                    names.forEachIndexed { index, name ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ResonatorArtwork(name, b.resonatorImages.artworkFor(name), cacheExpiresAt)
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    val (bText, bBg, bFg) = if (index == 0) Triple("5★ DEBUT", Color(0x33FFD994), Color(0xFFFFD994)) else Triple("5★ RERUN", Color(0x33BFE8E2), Color(0xFFBFE8E2))
                                    BadgePill(bText, bBg, bFg)
                                }
                                val sub = listOfNotNull("5★ Resonator", b.version?.let { "Version $it" }, b.phase?.let { phaseLabel(it) }).joinToString(" · ")
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2DDEA))
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Weapons", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (current) Color(0xFFFFD994) else Color(0xFFBFE8E2))
                    if (weapons.isEmpty()) Text("Weapon names will appear after the updated schedule feed is published.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2DDEA)) else weapons.forEachIndexed { index, name ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            WeaponArtwork(name, b.weaponImages[name], cacheExpiresAt)
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    val (wText, wBg, wFg) = if (index == 0) Triple("5★ SIGNATURE", Color(0x33FFD994), Color(0xFFFFD994)) else Triple("5★ FEATURED", Color(0x33BFE8E2), Color(0xFFBFE8E2))
                                    BadgePill(wText, wBg, wFg)
                                }
                                val sub = listOfNotNull("5★ Weapon", b.version?.let { "Version $it" }, b.phase?.let { phaseLabel(it) }).joinToString(" · ")
                                Text(sub, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2DDEA))
                            }
                        }
                    }
                    if (weapons.isNotEmpty() && b.status.equals("COMMUNITY", true)) Text("Weapon pairings are community schedule references.", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                }
            }
        }
    }
}

@Composable fun BadgePill(text: String, containerColor: Color, contentColor: Color) {
    Surface(color = containerColor, shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(start = 6.dp)) {
        Text(text = text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = contentColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable fun ArtworkInitial(name: String, weapon: Boolean = false) {
    Box(Modifier.size(68.dp).clip(if (weapon) RoundedCornerShape(12.dp) else CircleShape).background(Color(0xFF393441)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
    }
}

private fun Map<String, String>.artworkFor(name: String): String? = entries.firstOrNull { it.key.equals(name, true) }?.value

@Composable fun ResonatorArtwork(name: String, url: String?, expiresAt: Long) {
    RemoteArtwork(url, name, expiresAt)
}

@Composable fun WeaponArtwork(name: String, url: String?, expiresAt: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var image by remember(name, url, expiresAt) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(name, url, expiresAt) {
        image = withContext(Dispatchers.IO) {
            val identity = "weapon:${name.trim().lowercase(Locale.ROOT)}"
            val preferred = url?.takeIf(::isFandomArtUrl)
            val first = preferred?.let { loadArtwork(context, identity, it, .72f, expiresAt) }
            first ?: fandomPageImage(name, "weapon")?.let { loadArtwork(context, identity, it, .72f, expiresAt) }
                ?: loadArtworkFromDisk(context, identity, .72f, .5f)
        }
    }
    if (image != null) Image(image!!, name, contentScale = ContentScale.Crop, modifier = Modifier.size(68.dp).clip(RoundedCornerShape(12.dp))) else ArtworkInitial(name, true)
}

@Composable fun RemoteArtwork(url: String?, description: String, expiresAt: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var image by remember(url, description, expiresAt) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, description, expiresAt) {
        image = withContext(Dispatchers.IO) {
            val identity = "resonator:${description.trim().lowercase(Locale.ROOT)}"
            val preferred = url?.takeIf(::isFandomArtUrl)
            val first = preferred?.let { loadArtwork(context, identity, it, .96f, expiresAt, 0f) }
            first ?: fandomPageImage(description, "resonator")?.let { loadArtwork(context, identity, it, .96f, expiresAt, 0f) }
                ?: loadArtworkFromDisk(context, identity, .96f, 0f)
        }
    }
    if (image != null) Image(image!!, description, contentScale = ContentScale.Crop, modifier = Modifier.size(68.dp).clip(CircleShape)) else ArtworkInitial(description)
}

@Composable fun EmptyIntelCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun FeedCard(item: FeedItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val label = if (item.status == "LEAK") "LEAK · UNCONFIRMED" else item.status
    val tint = when (item.status.uppercase()) { "OFFICIAL" -> Color(0xFF314A5B); "LEAK" -> Color(0xFF523241); else -> Color(0xFF3A354D) }
    val codes = remember(item.title, item.summary) { extractPromoCodes(item.title + " " + item.summary) }

    Card(Modifier.fillMaxWidth().clickable { openUrl(item.url) }, colors = CardDefaults.cardColors(containerColor = tint)) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFFFD68A))
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (item.summary.isNotBlank() && !(item.status.equals("LEAK", true) && item.summary.trim().equals(item.title.trim(), true))) Text(item.summary)
            if (codes.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    codes.forEach { code ->
                        Button(
                            onClick = { copyToClipboard(context, "Promo Code", code) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFD994), contentColor = Color(0xFFFFD994)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("📋 Copy Code: $code", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Text("Source: ${item.source} · Destination: ${Uri.parse(item.url).host.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable fun IntelCard(title: String, body: String, url: String?) {
    Card(Modifier.fillMaxWidth().clickable(enabled = url?.startsWith("https://") == true) { openUrl(url!!) }) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body)
            if (url != null) Text("Source evidence • tap to open", style = MaterialTheme.typography.bodySmall)
        }
    }
}
