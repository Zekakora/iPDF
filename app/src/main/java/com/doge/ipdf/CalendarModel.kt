package com.doge.ipdf

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One class/event read from the subscribed iCalendar feed. [courseKey] groups the lecture and
 * exercise appointments of the same course: RWTHonline gives each appointment type its own course
 * number (61.00004 VO vs 61.00019 UE), but both carry the same title, so the title is the stable
 * key the folder mapping uses.
 */
data class CalendarEvent(
    val uid: String,
    val summary: String,
    val location: String,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean,
    val courseKey: String,
    val courseTitle: String,
)

/** A course discovered in the feed, shown in the mapping settings. */
data class CalendarCourse(
    val key: String,
    val title: String,
    val codes: List<String>,
    val eventCount: Int,
)

/** A task with a due date. Completed tasks keep [completedAt] and move to the history view. */
data class TodoTask(
    val id: String,
    val title: String,
    val notes: String,
    val dueAt: Long,
    val createdAt: Long,
    val completedAt: Long? = null,
) {
    val done: Boolean get() = completedAt != null
}

enum class TodoStatus { OVERDUE, TODAY, UPCOMING }

fun todoStatus(dueAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): TodoStatus {
    if (dueAt < now) return TodoStatus.OVERDUE
    val dueDate = java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (dueDate == today) TodoStatus.TODAY else TodoStatus.UPCOMING
}

fun activeTodos(todos: List<TodoTask>): List<TodoTask> =
    todos.filterNot { it.done }.sortedWith(compareBy({ it.dueAt }, { it.createdAt }))

fun completedTodos(todos: List<TodoTask>): List<TodoTask> =
    todos.filter { it.done }.sortedByDescending { it.completedAt }

/** "1h30m", "2h", "45m" — matches the compact duration in the agenda card. */
fun formatEventDuration(startAt: Long, endAt: Long): String {
    val minutes = ((endAt - startAt).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}h"
        minutes >= 60 -> "${minutes / 60}h${minutes % 60}m"
        else -> "${minutes}m"
    }
}

/** Parser for the subset of RFC 5545 that calendar subscriptions actually use. */
object IcsCalendar {
    private val dateTimePattern = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val shortDateTimePattern = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm")
    private val courseCode = Regex("""^\s*(\d{2}\.\d{5})\s+(.+)$""")
    private val appointmentKind = Regex(
        """\s+(VO|UE|VU|PR|SK|SE|PS|HS|KO|PJ|EX|TU|IV)\b(?:\s*,\s*.*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val standardGroup = Regex("""(?i),\s*Standardgruppe\s*$""")

    fun parse(text: String, defaultZone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        val events = ArrayList<CalendarEvent>()
        var current: MutableMap<String, IcsProperty>? = null
        var insideEvent = false

        for (line in unfold(text)) {
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    current = LinkedHashMap()
                    insideEvent = true
                }

                line.equals("END:VEVENT", ignoreCase = true) -> {
                    current?.let { properties ->
                        buildEvent(properties, defaultZone)?.let(events::add)
                    }
                    current = null
                    insideEvent = false
                }

                insideEvent -> {
                    val property = parseProperty(line) ?: continue
                    current?.put(property.name, property)
                }
            }
        }
        return events.sortedWith(compareBy({ it.startAt }, { it.summary }))
    }

    /** Distinct course names with the course numbers that produced them. */
    fun courses(events: List<CalendarEvent>): List<CalendarCourse> =
        events.groupBy { it.courseKey }
            .map { (key, list) ->
                CalendarCourse(
                    key = key,
                    title = list.first().courseTitle,
                    codes = list.mapNotNull { eventCourseCode(it.summary) }.distinct(),
                    eventCount = list.size,
                )
            }
            .sortedWith { a, b -> naturalCompare(a.title, b.title) }

    /** The course key and display title hidden in an appointment summary. */
    fun courseOf(summary: String): Pair<String, String> {
        val cleaned = cleanSummary(summary)
        val codeMatch = courseCode.find(cleaned)
        var title = codeMatch?.groupValues?.get(2) ?: cleaned
        title = appointmentKind.replace(title, "").trim()
        title = title.trim(' ', ',', '-')
        if (title.isBlank()) title = cleaned.ifBlank { "Untitled course" }
        return normalizeCourseKey(title) to title
    }

    fun courseKeyOf(summary: String): String = courseOf(summary).first

    private fun normalizeCourseKey(title: String): String =
        title.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    private fun cleanSummary(summary: String): String =
        summary.replace(standardGroup, "").trim()

    private fun eventCourseCode(summary: String): String? =
        courseCode.find(cleanSummary(summary))?.groupValues?.get(1)

    private fun buildEvent(
        properties: Map<String, IcsProperty>,
        defaultZone: ZoneId,
    ): CalendarEvent? {
        if (properties["STATUS"]?.value?.equals("CANCELLED", ignoreCase = true) == true) return null
        val startProperty = properties["DTSTART"] ?: return null
        val start = parseMoment(startProperty, defaultZone) ?: return null
        val end = properties["DTEND"]?.let { parseMoment(it, defaultZone)?.first }
            ?: properties["DURATION"]?.value?.let { raw ->
                runCatching { start.first + Duration.parse(raw.trim()).toMillis() }.getOrNull()
            }
            ?: (start.first + 60L * 60L * 1000L)
        val safeEnd = if (end > start.first) end else start.first + 60L * 60L * 1000L

        val rawSummary = unescape(properties["SUMMARY"]?.value.orEmpty()).trim()
            .ifBlank { "Untitled event" }
        val summary = cleanSummary(rawSummary)
        if (summary.isBlank()) return null
        val (key, title) = courseOf(summary)

        val uid = unescape(properties["UID"]?.value.orEmpty()).trim()
            .ifBlank { "event:${start.first}:$summary" }
        val location = unescape(properties["LOCATION"]?.value.orEmpty()).trim()

        return CalendarEvent(
            uid = uid,
            summary = summary,
            location = location,
            startAt = start.first,
            endAt = safeEnd,
            allDay = start.second,
            courseKey = key,
            courseTitle = title,
        )
    }

    /** @return epoch millis and whether the value was a date-only (all-day) value. */
    private fun parseMoment(property: IcsProperty, defaultZone: ZoneId): Pair<Long, Boolean>? {
        val value = property.value.trim()
        if (value.length == 8 && value.all(Char::isDigit)) {
            val date = runCatching { LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) }
                .getOrNull() ?: return null
            return date.atStartOfDay(defaultZone).toInstant().toEpochMilli() to true
        }

        val utc = value.endsWith("Z", ignoreCase = true)
        val core = if (utc) value.dropLast(1) else value
        val zone = when {
            utc -> ZoneOffset.UTC
            property.params["TZID"] != null ->
                runCatching { ZoneId.of(property.params.getValue("TZID")) }.getOrDefault(defaultZone)
            else -> defaultZone
        }
        val formatter = if (core.length >= 15) dateTimePattern else shortDateTimePattern
        val local = runCatching { LocalDateTime.parse(core, formatter) }.getOrNull() ?: return null
        return local.atZone(zone).toInstant().toEpochMilli() to false
    }

    private data class IcsProperty(
        val name: String,
        val value: String,
        val params: Map<String, String>,
    )

    private fun parseProperty(line: String): IcsProperty? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val header = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val segments = header.split(';')
        val name = segments.first().trim().uppercase(Locale.ROOT)
        if (name.isEmpty()) return null
        val params = LinkedHashMap<String, String>()
        for (segment in segments.drop(1)) {
            val equals = segment.indexOf('=')
            if (equals > 0) {
                params[segment.substring(0, equals).trim().uppercase(Locale.ROOT)] =
                    segment.substring(equals + 1).trim().trim('"')
            } else if (segment.isNotBlank()) {
                params[segment.trim().uppercase(Locale.ROOT)] = ""
            }
        }
        return IcsProperty(name, value, params)
    }

    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val lines = ArrayList<String>(raw.size)
        for (line in raw) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + line.substring(1)
            } else {
                lines += line
            }
        }
        return lines
    }

    private fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    ',', ';', '\\' -> out.append(next)
                    'n', 'N' -> out.append('\n')
                    else -> out.append(next)
                }
                index += 2
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }
}

/** The folder row shown in the mapping table. */
data class CourseFolderOption(
    val folder: String,
    val course: String,
    val semester: String?,
    val count: Int,
)

/** Scheduling state used by the library screen. */
data class ScheduleUiState(
    val ready: Boolean = false,
    val now: Long = System.currentTimeMillis(),
    val icalUrl: String = "",
    val folderToCourse: Map<String, String> = emptyMap(),
    val events: List<CalendarEvent> = emptyList(),
    val courses: List<CalendarCourse> = emptyList(),
    val todos: List<TodoTask> = emptyList(),
    val lastSyncedAt: Long = 0L,
    val syncing: Boolean = false,
    val syncError: String? = null,
)

/**
 * Best-effort automatic folder mapping: fold the semester out of the folder name and look for a
 * course whose title shares tokens with it (or whose course number appears in the folder name).
 * Only empty mappings are filled, so a manual choice always wins.
 */
fun autoMatchFolders(
    folders: List<CourseFolderOption>,
    courses: List<CalendarCourse>,
    existing: Map<String, String>,
): Map<String, String> {
    if (courses.isEmpty()) return existing
    val result = LinkedHashMap(existing)
    for (folder in folders) {
        if (result[folder.folder] != null) continue
        val parsed = SemesterParser.parse(folder.folder)
        val folderName = parsed.base.ifBlank { folder.folder }
        val normalizedFolder = folderName.lowercase(Locale.ROOT)
        val folderTokens = tokens(normalizedFolder)
        var best: CalendarCourse? = null
        var bestScore = 0

        for (course in courses) {
            var score = 0
            val title = course.title.lowercase(Locale.ROOT)
            if (folderName.length >= 3 && title.contains(normalizedFolder)) score += 8
            if (normalizedFolder.length >= 3 && normalizedFolder.contains(title)) score += 6
            course.codes.forEach { code ->
                if (normalizedFolder.contains(code)) score += 10
            }
            val titleTokens = tokens(title)
            score += folderTokens.intersect(titleTokens).sumOf { it.length }
            // "HPC Winter" should find "Einführung in High-Performance Computing": compare the
            // folder's short tokens with acronyms built from runs of title words.
            score += acronyms(title).intersect(folderTokens).sumOf { it.length * 3 }
            if (titleTokens.isNotEmpty() && folderTokens.containsAll(titleTokens)) score += 6
            if (score > bestScore) {
                bestScore = score
                best = course
            }
        }
        if (best != null && bestScore >= 4) result[folder.folder] = best.key
    }
    return result
}

private fun tokens(value: String): Set<String> =
    value.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 }
        .toSet()

/** Initials of every contiguous run of 2–4 title words, e.g. high-performance computing → hpc. */
private fun acronyms(value: String): Set<String> {
    val words = value.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .map { it.first().lowercaseChar() }
    val out = LinkedHashSet<String>()
    for (start in words.indices) {
        for (length in 2..4) {
            if (start + length > words.size) break
            out += words.subList(start, start + length).joinToString("")
        }
    }
    return out
}

/** Local wall-clock helpers shared by the agenda and the task list. */
fun localDateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
