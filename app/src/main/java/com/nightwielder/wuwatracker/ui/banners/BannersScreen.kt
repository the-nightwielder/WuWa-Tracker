package com.nightwielder.wuwatracker.ui.banners

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nightwielder.wuwatracker.data.*
import com.nightwielder.wuwatracker.model.*
import com.nightwielder.wuwatracker.ui.*
import com.nightwielder.wuwatracker.ui.intel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

@Composable
fun BannersScreen(state: UiState, now: Instant, modifier: Modifier) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val version = state.version.takeIf { it != "Unknown" } ?: "3.7"
    val banners = state.banners.filter { it.relevantBanner(version, now) }

    fun oneCardPerPhase(rows: List<BannerIntel>) = rows.groupBy { "${it.version.orEmpty()}|${it.phase ?: 0}" }
        .values.mapNotNull { phaseRows ->
            phaseRows.maxByOrNull {
                (if (it.serverTimes.keys.containsAll(setOf("AMERICA", "EUROPE", "ASIA", "HMT", "SEA"))) 1000 else 0) +
                        (if (it.sourceLabel.contains("Prydwen", true)) 200 else 0) +
                        (if (it.sourceLabel.contains("Game8", true)) 100 else 0) +
                        (if (it.weaponClaim.isNullOrBlank()) 0 else 50 + it.weaponClaim.length) +
                        (it.weaponImages.size * 10) + (it.resonatorImages.size * 5)
            }
        }

    fun timing(b: BannerIntel): BannerServerTiming? = b.serverTimes[state.server.name]?.takeIf { it.startAt != null && it.endAt != null }

    val active = oneCardPerPhase(banners.filter {
        val t = timing(it)
        (t != null && t.startAt!! <= now && t.endAt!! > now) || (t == null && it.scheduleStatus.equals("CURRENT", true))
    }).sortedBy { timing(it)?.startAt ?: Instant.MIN }

    val upcoming = oneCardPerPhase(banners.filter {
        timing(it)?.startAt?.isAfter(now) == true || (timing(it) == null && it.scheduleStatus.equals("UPCOMING", true))
    }).sortedBy { timing(it)?.startAt ?: Instant.MAX }

    val teased = banners.filter { it.scheduleStatus.equals("TEASED", true) && timing(it) == null }
        .distinctBy { it.title.lowercase(Locale.ROOT) + it.version.orEmpty() }

    fun cacheEnd(b: BannerIntel): Instant? {
        timing(b)?.endAt?.let { return it }
        val baseStart = timing(b)?.startAt
        val laterPhase = state.banners.asSequence().filter { candidate ->
            val candidateStart = timing(candidate)?.startAt
            candidate.version == b.version && candidate.phase != null && b.phase != null && candidate.phase > b.phase && candidateStart != null && (baseStart == null || candidateStart.isAfter(baseStart))
        }.mapNotNull { timing(it)?.startAt }.minOrNull()
        if (laterPhase != null) return laterPhase
        return b.version?.let { v ->
            state.banners.asSequence().filter { candidate ->
                val candidateStart = timing(candidate)?.startAt
                candidate.version != null && compareVer(candidate.version, v) > 0 && candidateStart != null && (baseStart == null || candidateStart.isAfter(baseStart))
            }.mapNotNull { timing(it)?.startAt }.minOrNull()
        }
    }

    LaunchedEffect(state.banners) { withContext(Dispatchers.IO) { pruneArtworkCache(appContext) } }

    ArtworkScreen(modifier, com.nightwielder.wuwatracker.R.drawable.bg_intel) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Resonator & Weapon Banners", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFFF3E9FF)) }
            item { Text("Current version · $version", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFFFD994)) }
            item { Text("Active Resonator / Weapon Banners", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFE7DAFF)) }
            if (active.isEmpty()) item { InfoCard("Live schedule", "No verified timing for your selected server is available in the feed.", "") }
            items(active) { b -> BannerOverviewCard(b, true, state.server, cacheEnd(b)?.toEpochMilli() ?: Long.MAX_VALUE, now) }
            item { Text("Upcoming Resonator / Weapon Banners", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFBFE8E2)) }
            if (upcoming.isEmpty()) item { InfoCard("Upcoming schedule", "No verified upcoming timing for your selected server is available in the feed.", "Upcoming banners will appear when a source provides server-specific times.") }
            items(upcoming) { b -> BannerOverviewCard(b, false, state.server, cacheEnd(b)?.toEpochMilli() ?: Long.MAX_VALUE, now) }
            if (teased.isNotEmpty()) {
                item { Text("Teased Resonator Banners", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFE7DAFF)) }
                items(teased) { b -> BannerOverviewCard(b, false, state.server, Long.MAX_VALUE, now) }
            }

            item { Text("Official Resonator Strategy Guides", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD994)) }
            item {
                val activeLead = active.firstOrNull()?.let { b ->
                    b.title.split(Regex("\\s*,\\s*|\\s+and\\s+")).firstOrNull { it.isNotBlank() }
                } ?: "Suoming"
                val activeBanner = active.firstOrNull()
                val cacheExpires = activeBanner?.let { cacheEnd(it)?.toEpochMilli() } ?: Long.MAX_VALUE
                val activeArtUrl = activeBanner?.resonatorImages?.entries?.firstOrNull { it.key.equals(activeLead, true) }?.value

                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF292438))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            ResonatorArtwork(activeLead, activeArtUrl, cacheExpires)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(activeLead, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFFFD994))
                                Text("Official Kuro Games Character Strategy & Build Portal", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE2DDEA))
                            }
                        }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Button(
                            onClick = { com.nightwielder.wuwatracker.ui.guide.openKuroGuide(context, "https://wuwaguide.kurogames.com/en/", activeLead) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FFD994), contentColor = Color(0xFFFFD994)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📖 View Official Build Guide ↗", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun BannerIntel.relevantBanner(version: String, now: Instant): Boolean =
    if (scheduleStatus.equals("TEASED", true)) true
    else if (this.version != null) compareVer(this.version, version) >= 0
    else (endAt?.isAfter(now) == true || startAt?.isAfter(now) == true)

private fun compareVer(a: String, b: String): Int {
    val x = Regex("\\d+\\.\\d+").find(a)?.value?.split(".")?.map { it.toInt() } ?: return 0
    val y = Regex("\\d+\\.\\d+").find(b)?.value?.split(".")?.map { it.toInt() } ?: return 0
    for (i in 0 until maxOf(x.size, y.size)) {
        val n = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (n != 0) return n
    }
    return 0
}
