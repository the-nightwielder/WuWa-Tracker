package com.nightwielder.wuwatracker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private data class CachedArtUrl(val url: String?, val cachedAt: Long)
private val fandomArtUrlCache = ConcurrentHashMap<String, CachedArtUrl>()
private const val ART_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
private const val ART_MAX_BYTES = 5 * 1024 * 1024
private const val ART_MAX_DECODE_EDGE = 2048

fun sampledOptions(bounds: BitmapFactory.Options): BitmapFactory.Options? {
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > 1_000_000_000L) return null
    var sample = 1
    while (maxOf(width, height) / sample > ART_MAX_DECODE_EDGE && sample < (1 shl 29)) sample *= 2
    return BitmapFactory.Options().apply { inSampleSize = sample }
}

fun decodeArtwork(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = sampledOptions(bounds) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

fun decodeArtwork(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = sampledOptions(bounds) ?: return null
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

fun openFandomArtworkConnection(startUrl: String): HttpURLConnection {
    var target = URL(startUrl)
    var redirects = 0
    while (true) {
        if (!isFandomArtUrl(target.toString())) throw IllegalArgumentException("Artwork host is not approved")
        val connection = (target.openConnection() as HttpURLConnection).apply {
            useCaches = false; instanceFollowRedirects = false; connectTimeout = 8000; readTimeout = 10000
            setRequestProperty("Cache-Control", "no-cache"); setRequestProperty("User-Agent", "WuWaTracker/1.0 (Android)")
        }
        val status = connection.responseCode
        if (status in listOf(301, 302, 303, 307, 308)) {
            val location = connection.getHeaderField("Location"); connection.disconnect()
            if (location.isNullOrBlank() || redirects >= 3) throw IllegalStateException("Artwork redirect limit exceeded")
            redirects++; target = URL(target, location)
            if (!isFandomArtUrl(target.toString())) throw IllegalArgumentException("Artwork redirect host is not approved")
            continue
        }
        if (status !in 200..299) { connection.disconnect(); throw IllegalStateException("Artwork request failed") }
        if (!isFandomArtUrl(connection.url.toString())) { connection.disconnect(); throw IllegalArgumentException("Artwork destination is not approved") }
        return connection
    }
}

fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw IllegalStateException("Response is too large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun isFandomArtUrl(value: String?): Boolean = runCatching {
    val u = URL(value)
    u.protocol == "https" && (u.host == "fandom.com" || u.host.endsWith(".fandom.com") || u.host.endsWith(".wikia.nocookie.net") || u.host.endsWith(".wikia.com"))
}.getOrDefault(false)

fun fandomPageImage(name: String, kind: String): String? {
    val key = "$kind:${name.trim().lowercase(Locale.ROOT)}"
    val now = System.currentTimeMillis()
    fandomArtUrlCache[key]?.takeIf { now - it.cachedAt < (if (it.url == null) 5 * 60 * 1000L else ART_CACHE_TTL_MS) }?.let { return it.url }
    val image = runCatching {
        val query = URLEncoder.encode(name, "UTF-8")
        val api = "https://wutheringwaves.fandom.com/api.php?action=query&titles=$query&prop=pageimages&format=json&pithumbsize=640&redirects=1"
        val conn = openFandomArtworkConnection(api)
        val json = try {
            if (conn.contentLengthLong > 1024 * 1024) throw IllegalStateException("Artwork metadata is too large")
            conn.inputStream.use { String(readBounded(it, 1024 * 1024), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        val pages = JSONObject(json).getJSONObject("query").getJSONObject("pages")
        val keys = pages.keys()
        if (!keys.hasNext()) null else pages.getJSONObject(keys.next()).optJSONObject("thumbnail")?.optString("source")?.takeIf(::isFandomArtUrl)
    }.getOrNull()
    fandomArtUrlCache[key] = CachedArtUrl(image, now)
    return image
}

fun artworkFile(context: Context, identity: String, url: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest("$identity|$url".toByteArray()).joinToString("") { "%02x".format(it) }
    return File(File(context.cacheDir, "banner-artwork"), "$digest.img")
}

fun pruneArtworkCache(context: Context) {
    val dir = File(context.cacheDir, "banner-artwork")
    val files = dir.listFiles() ?: return
    val now = System.currentTimeMillis()
    files.filter { it.name.endsWith(".img") }.forEach { file ->
        val expiryFile = File(dir, file.name + ".expiry")
        val expiry = expiryFile.takeIf { it.isFile }?.readText()?.toLongOrNull() ?: 0L
        if (expiry != Long.MAX_VALUE && expiry <= now) {
            file.delete()
            expiryFile.delete()
        }
    }
    dir.listFiles()?.filter { it.name.endsWith(".tmp") && now - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
}

fun loadArtwork(context: Context, identity: String, url: String, crop: Float, expiresAt: Long, verticalBias: Float = .5f): ImageBitmap? = runCatching {
    val file = artworkFile(context, identity, url)
    val expiryFile = File(file.parentFile, file.name + ".expiry")
    val now = System.currentTimeMillis()
    val storedExpiry = expiryFile.takeIf { it.isFile }?.readText()?.toLongOrNull() ?: 0L
    var bitmap: Bitmap? = if (file.isFile && storedExpiry != 0L && (storedExpiry == Long.MAX_VALUE || now < storedExpiry)) decodeArtwork(file) else null
    if (bitmap == null) {
        file.delete()
        expiryFile.delete()
        val connection = openFandomArtworkConnection(url)
        try {
            if (connection.contentLengthLong > ART_MAX_BYTES) throw IllegalStateException("Artwork is too large")
            val bytes = connection.inputStream.use { readBounded(it, ART_MAX_BYTES) }
            bitmap = decodeArtwork(bytes) ?: throw IllegalStateException("Unsupported or oversized artwork")
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + "." + Thread.currentThread().id + ".tmp")
            FileOutputStream(temp).use { it.write(bytes) }
            if (temp.renameTo(file)) {
                file.setLastModified(now)
                expiryFile.writeText(expiresAt.toString())
            } else temp.delete()
        } finally {
            connection.disconnect()
        }
    }
    bitmap?.let { source ->
        val side = (minOf(source.width, source.height) * crop).toInt().coerceAtLeast(1)
        val left = ((source.width - side) / 2).coerceIn(0, source.width - side)
        val top = ((source.height - side) * verticalBias).toInt().coerceIn(0, source.height - side)
        Bitmap.createBitmap(source, left, top, side, side).asImageBitmap()
    }
}.getOrNull()
