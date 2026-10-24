package com.nightwielder.wuwatracker.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

enum class Server(val label: String, val zone: String) {
    AMERICA("America", "America/New_York"),
    EUROPE("Europe", "Europe/London"),
    ASIA("Asia", "Asia/Shanghai"),
    SEA("SEA", "Asia/Singapore"),
    HMT("HMT", "Asia/Hong_Kong")
}

val Server.bannerZone: ZoneId get() = ZoneOffset.of(when (this) {
    Server.AMERICA -> "-05:00"
    Server.EUROPE -> "+01:00"
    Server.ASIA -> "+09:00"
    Server.SEA, Server.HMT -> "+08:00"
})

data class TrackerEvent(
    val title: String,
    val type: String,
    val start: Instant,
    val end: Instant?,
    val note: String,
    val sourceUrl: String = "",
    val status: String = "COMMUNITY",
    val confidence: Double = 0.0
)

data class FeedItem(
    val title: String,
    val url: String,
    val summary: String,
    val status: String,
    val source: String,
    val publishedAt: String,
    val confidence: Double,
    val targetVersion: String? = null
)

data class VersionIntel(
    val version: String,
    val status: String,
    val confidence: Double,
    val sourceUrls: List<String>
)

data class BannerServerTiming(
    val startAt: Instant?,
    val endAt: Instant?
)

data class BannerIntel(
    val title: String,
    val version: String?,
    val phase: Int?,
    val startAt: Instant?,
    val endAt: Instant?,
    val status: String,
    val confidence: Double,
    val sourceUrls: List<String>,
    val sourceLabel: String = "Website",
    val weaponClaim: String? = null,
    val resonatorImages: Map<String, String> = emptyMap(),
    val weaponImages: Map<String, String> = emptyMap(),
    val serverTimes: Map<String, BannerServerTiming> = emptyMap(),
    val scheduleStatus: String? = null
)

data class ResonatorIntel(
    val name: String,
    val version: String?,
    val phase: Int?,
    val element: String?,
    val weapon: String?,
    val status: String,
    val confidence: Double,
    val sourceUrls: List<String>,
    val sourceLabel: String = "Website",
    val weaponClaim: String? = null
)

data class FeedResult(
    val items: List<FeedItem>,
    val events: List<TrackerEvent>,
    val versions: List<VersionIntel>,
    val banners: List<BannerIntel>,
    val resonators: List<ResonatorIntel>,
    val latestVersion: String?
)

data class UiState(
    val server: Server = Server.SEA,
    val dailyDone: Boolean = false,
    val luniteDone: Boolean = false,
    val weeklyDone: Boolean = false,
    val weeklyDoneDate: String = "",
    val reminderEnabled: Boolean = true,
    val reminderLead: Int = 0,
    val version: String = "Unknown",
    val events: List<TrackerEvent> = defaultEvents(),
    val feedItems: List<FeedItem> = emptyList(),
    val versions: List<VersionIntel> = emptyList(),
    val banners: List<BannerIntel> = emptyList(),
    val resonators: List<ResonatorIntel> = emptyList(),
    val feedUrl: String = "https://the-nightwielder.github.io/WuWa-Tracker/feed.json",
    val lastSync: String = "",
    val serverSelected: Boolean = false,
    val customReminderAt: Instant? = null,
    val collapsedSections: Set<String> = emptySet(),
    val monthlyTower: Boolean = false,
    val monthlyWastes: Boolean = false,
    val monthlyMatrix: Boolean = false
)

fun defaultEvents() = listOf(
    TrackerEvent("Tower of Adversity", "Endgame", Instant.EPOCH, null, "Permanent/recurring content. Use the in-game timer for the active rotation."),
    TrackerEvent("Whimpering Wastes", "Endgame", Instant.EPOCH, null, "Permanent/recurring content. Use the in-game timer for the active cycle."),
    TrackerEvent("Endstate Matrix", "Endgame", Instant.EPOCH, null, "Version/phase-linked challenge. Use the in-game timer for exact availability.")
)
