package app.chapteralert

import android.app.*
import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL

object Tracker {
    private const val CHANNEL = "chapters"
    private fun prefs(c: Context) = c.getSharedPreferences("series", Context.MODE_PRIVATE)

    // url -> "lastChapter|Title"
    fun all(c: Context): Map<String, String> = prefs(c).all.mapValues { it.value as String }
    fun remove(c: Context, url: String) { prefs(c).edit().remove(url).apply() }
    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    private fun norm(url: String) = url.substringAfter("/comics/").replace(Regex("-[0-9a-f]{8}$"), "")

    private fun titleFrom(slug: String) = slug.replace(Regex("-[0-9a-f]{8}$"), "")
        .split("-").joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    // Series pages list every chapter as a /chapter/N link; the highest N is the newest.
    private fun fetchLatest(url: String): Double? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) ChapterAlert/1.0")
        try {
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            return Regex("/chapter/(\\d+(?:\\.\\d+)?)").findAll(html)
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull()
        } finally { conn.disconnect() }
    }

    /** Blocking: call off the main thread. Accepts a series or chapter URL. */
    fun add(c: Context, input: String): String {
        val m = Regex("https://asurascans\\.com/comics/([^/?#\\s]+)").find(input.trim())
            ?: return "Open a series page first"
        val title = titleFrom(m.groupValues[1])
        if (all(c).keys.any { norm(it) == norm(m.value) }) return "Already tracking $title"
        val latest: Double?
        try { latest = fetchLatest(m.value) } catch (e: Exception) { return "Couldn't load page: ${e.message}" }
        if (latest == null) return "No chapters found on that page"
        prefs(c).edit().putString(m.value, "${fmt(latest)}|$title").apply()
        return "Tracking $title (latest: ch. ${fmt(latest)})"
    }

    /** Blocking: checks every tracked series and notifies on new chapters. */
    fun check(c: Context) {
        for ((url, v) in all(c)) {
            val parts = v.split("|", limit = 2)
            val last = parts[0].toDoubleOrNull() ?: 0.0
            val title = parts.getOrElse(1) { "Series" }
            val latest: Double? = try { fetchLatest(url) } catch (e: Exception) { null }
            if (latest != null && latest > last) {
                notifyNew(c, url, title, latest)
                prefs(c).edit().putString(url, "${fmt(latest)}|$title").apply()
            }
        }
    }

    private fun notifyNew(c: Context, url: String, title: String, ch: Double) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New chapters", NotificationManager.IMPORTANCE_HIGH))
        // Tapping opens the chapter inside the app's reader.
        val intent = Intent(c, MainActivity::class.java).putExtra("open", "$url/chapter/${fmt(ch)}")
        val open = PendingIntent.getActivity(
            c, url.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText("Chapter ${fmt(ch)} is out")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(url.hashCode(), n)
    }
}
