package com.doge.ipdf

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * Personal RWTHonline feed supplied by the device owner. It is only a first-run default: the
 * settings screen can replace or clear it, and the value is stored locally with the other schedule
 * data. Treat this link as private because the token grants read access to the timetable.
 */
const val DEFAULT_ICAL_URL =
    "https://online.rwth-aachen.de/RWTHonlinej/ws/termin/ical?" +
        "pStud=91A83BD75EBB15A6&pToken=49C31FD842A9829B3712BB6F04F080B97047BAEBEA60DFBCB2075EB5610C9EBC"

data class ScheduleSettings(
    val icalUrl: String = DEFAULT_ICAL_URL,
    val folderToCourse: Map<String, String> = emptyMap(),
    val lastSyncedAt: Long = 0L,
    val cachedUrl: String = "",
)

/**
 * Local storage for the calendar subscription and the task list. Files live under
 * filesDir/schedule; nothing leaves the device except the HTTPS request for the feed itself.
 */
class ScheduleStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "schedule")
    private val settingsFile = File(dir, "settings.json")
    private val cacheFile = File(dir, "calendar.ics")
    private val todosFile = File(dir, "todos.json")

    fun loadSettings(): ScheduleSettings {
        if (!settingsFile.isFile) return ScheduleSettings()
        return runCatching {
            val json = JSONObject(settingsFile.readText())
            val mapping = LinkedHashMap<String, String>()
            json.optJSONObject("folderToCourse")?.let { objectValue ->
                val keys = objectValue.keys()
                while (keys.hasNext()) {
                    val folder = keys.next()
                    val course = objectValue.optString(folder)
                    if (folder.isNotBlank() && course.isNotBlank()) mapping[folder] = course
                }
            }
            ScheduleSettings(
                icalUrl = json.optString("icalUrl").trim(),
                folderToCourse = mapping,
                lastSyncedAt = json.optLong("lastSyncedAt", 0L),
                cachedUrl = json.optString("cachedUrl").trim(),
            )
        }.getOrDefault(ScheduleSettings())
    }

    fun saveSettings(settings: ScheduleSettings) {
        val json = JSONObject().apply {
            put("version", 1)
            put("icalUrl", settings.icalUrl)
            put("lastSyncedAt", settings.lastSyncedAt)
            put("cachedUrl", settings.cachedUrl)
            put(
                "folderToCourse",
                JSONObject().apply {
                    settings.folderToCourse.forEach { (folder, course) -> put(folder, course) }
                },
            )
        }
        writeAtomic(settingsFile, json.toString())
    }

    fun loadCachedCalendar(): String? {
        if (!cacheFile.isFile) return null
        return runCatching { cacheFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun saveCachedCalendar(text: String) {
        writeAtomic(cacheFile, text)
    }

    fun clearCachedCalendar() {
        synchronized(writeLock) {
            if (cacheFile.exists()) cacheFile.delete()
        }
    }

    /**
     * Removes only the downloaded feed and a crash-left atomic-write temp. Settings and todo data
     * live in sibling files and are deliberately untouched.
     */
    internal fun clearCache(stats: CacheCleanupStats) {
        synchronized(writeLock) {
            deleteTracked(cacheFile, stats)
            deleteTracked(File(dir, "calendar.ics.tmp"), stats)
        }
    }

    fun loadTodos(): List<TodoTask> {
        if (!todosFile.isFile) return emptyList()
        return runCatching {
            val json = JSONObject(todosFile.readText())
            val items = json.optJSONArray("items") ?: JSONArray()
            val out = ArrayList<TodoTask>(items.length())
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val due = item.optLong("dueAt", 0L)
                if (due <= 0L) continue
                out += TodoTask(
                    id = id,
                    title = item.optString("title"),
                    notes = item.optString("notes"),
                    dueAt = due,
                    createdAt = item.optLong("createdAt", 0L),
                    completedAt = if (item.isNull("completedAt")) null else item.optLong("completedAt"),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun saveTodos(todos: List<TodoTask>) {
        val items = JSONArray()
        for (todo in todos) {
            items.put(
                JSONObject().apply {
                    put("id", todo.id)
                    put("title", todo.title)
                    put("notes", todo.notes)
                    put("dueAt", todo.dueAt)
                    put("createdAt", todo.createdAt)
                    put("completedAt", todo.completedAt ?: JSONObject.NULL)
                },
            )
        }
        val json = JSONObject().apply {
            put("version", 1)
            put("items", items)
        }
        writeAtomic(todosFile, json.toString())
    }

    /** Downloads an iCalendar feed. Only HTTPS links are accepted. */
    fun fetchCalendar(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: error("That does not look like a calendar link.")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            error("Use an https:// calendar link.")
        }
        if (uri.host.isNullOrBlank()) error("That calendar link has no host.")
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "text/calendar, text/plain, */*")
            setRequestProperty("User-Agent", "iPDF/0.4 (Android; personal calendar subscription)")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Calendar link returned HTTP $status.")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    if (out.length + read > MAX_FEED_CHARS) {
                        error("This calendar feed is too large.")
                    }
                    out.append(buffer, 0, read)
                }
                out.toString()
            }
            if (!text.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
                error("This link did not return an iCalendar feed.")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun writeAtomic(file: File, text: String) {
        synchronized(writeLock) {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(text)
            if (file.exists() && !file.delete()) {
                file.writeText(text)
                tmp.delete()
                return
            }
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private companion object {
        const val MAX_FEED_CHARS = 8_000_000

        /**
         * Shared by every ScheduleStore instance in the process so the hidden cache cleaner cannot
         * delete calendar.ics while the schedule view model is replacing it.
         */
        val writeLock = Any()
    }
}
