package com.doge.ipdf

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The four folders the library understands under each course. Only the folder name is used:
 * anything that is not exactly one of these names (ignoring case) is grouped under OTHERS.
 */
enum class DocType(val label: String) {
    LECTURE("Lecture"),
    EXERCISE("Exercise"),
    EXAM("Exam"),
    OTHERS("Others");

    companion object {
        fun fromFolderName(name: String): DocType? =
            DocType.entries.firstOrNull { it.label.equals(name.trim(), ignoreCase = true) }

        fun fromStorage(value: String): DocType =
            DocType.entries.firstOrNull { it.name == value } ?: OTHERS
    }
}

data class LibraryEntry(
    val uri: String,
    val name: String,
    val course: String,
    val courseFolder: String,
    val semester: String?,
    val type: DocType,
    val relativeFolder: String,
    val size: Long,
    val modified: Long,
)

data class LibraryIndex(
    val rootUri: String,
    val rootName: String,
    val scannedAt: Long,
    val entries: List<LibraryEntry>,
)

data class HistoryEntry(
    val uri: String,
    val name: String,
    val openedAt: Long,
)

data class ParsedCourse(val base: String, val semester: String?)

sealed interface SemesterKey {
    data object Unassigned : SemesterKey
    data class Named(val label: String) : SemesterKey
}

data class LibraryFilters(
    val course: String? = null,
    val type: DocType? = null,
    val semester: SemesterKey? = null,
    val query: String = "",
) {
    val active: Boolean
        get() = course != null || type != null || semester != null || query.isNotBlank()
}

data class LibraryUiPreferences(
    val rootUri: String? = null,
    val filters: LibraryFilters = LibraryFilters(),
    val collapsedKeys: Set<String> = emptySet(),
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

enum class FilterFacet { COURSE, TYPE, SEMESTER }

data class CourseOption(val course: String, val count: Int)

data class SemesterOption(val key: SemesterKey, val label: String, val count: Int)

/**
 * Course folder names can carry the semester as a prefix or suffix:
 * 26ws-HPC, HPC-26ws, 26ws HPC, WS26-HPC, 2026ws-HPC, ...
 */
object SemesterParser {
    private const val YEAR = """(?:\d{2}|\d{4})"""
    private const val TERM = """(?:ws|ss)"""

    private val suffixYearTerm = Regex("""^(.+?)[\s_\-–—.]*($YEAR)\s*($TERM)$""", RegexOption.IGNORE_CASE)
    private val suffixTermYear = Regex("""^(.+?)[\s_\-–—.]*($TERM)\s*($YEAR)$""", RegexOption.IGNORE_CASE)
    private val prefixYearTerm = Regex("""^($YEAR)\s*($TERM)[\s_\-–—.]*(.+)$""", RegexOption.IGNORE_CASE)
    private val prefixTermYear = Regex("""^($TERM)\s*($YEAR)[\s_\-–—.]*(.+)$""", RegexOption.IGNORE_CASE)
    private val semesterLabel = Regex("""^(\d{2}|\d{4})(ws|ss)$""", RegexOption.IGNORE_CASE)

    fun parse(folderName: String): ParsedCourse {
        val name = folderName.trim()
        suffixYearTerm.matchEntire(name)?.let {
            return ParsedCourse(cleanBase(it.groupValues[1], name), normalize(it.groupValues[2], it.groupValues[3]))
        }
        suffixTermYear.matchEntire(name)?.let {
            return ParsedCourse(cleanBase(it.groupValues[1], name), normalize(it.groupValues[3], it.groupValues[2]))
        }
        prefixYearTerm.matchEntire(name)?.let {
            return ParsedCourse(cleanBase(it.groupValues[3], name), normalize(it.groupValues[1], it.groupValues[2]))
        }
        prefixTermYear.matchEntire(name)?.let {
            return ParsedCourse(cleanBase(it.groupValues[3], name), normalize(it.groupValues[2], it.groupValues[1]))
        }
        return ParsedCourse(name, null)
    }

    /** Higher key = more recent. WS sorts after SS within the same academic year. */
    fun sortKey(label: String): Int? {
        val match = semesterLabel.matchEntire(label) ?: return null
        val year = match.groupValues[1].takeLast(2).toIntOrNull() ?: return null
        val termRank = if (match.groupValues[2].equals("ws", ignoreCase = true)) 2 else 1
        return year * 10 + termRank
    }

    private fun cleanBase(value: String, fallback: String): String =
        value.trim(' ', '-', '_', '–', '—', '.').ifBlank { fallback }

    private fun normalize(year: String, term: String): String =
        year.takeLast(2) + term.lowercase(Locale.ROOT)
}

fun LibraryEntry.matchesQuery(query: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val tokens = trimmed.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val haystack = listOf(
        name,
        course,
        courseFolder,
        type.label,
        relativeFolder,
        semester ?: "unassigned",
    ).joinToString(" ").lowercase(Locale.ROOT)
    return tokens.all { haystack.contains(it) }
}

fun LibraryEntry.matchesFilters(filters: LibraryFilters, ignore: FilterFacet? = null): Boolean {
    if (ignore != FilterFacet.COURSE && filters.course != null && !course.equals(filters.course, ignoreCase = true)) {
        return false
    }
    if (ignore != FilterFacet.TYPE && filters.type != null && type != filters.type) {
        return false
    }
    val semesterFilter = filters.semester
    if (ignore != FilterFacet.SEMESTER && semesterFilter != null) {
        when (semesterFilter) {
            SemesterKey.Unassigned -> if (semester != null) return false
            is SemesterKey.Named -> if (!semesterFilter.label.equals(semester, ignoreCase = true)) return false
        }
    }
    return matchesQuery(filters.query)
}

fun compareSemesterLabels(a: String?, b: String?): Int {
    if (a == null && b == null) return 0
    if (a == null) return 1
    if (b == null) return -1
    val ka = SemesterParser.sortKey(a) ?: 0
    val kb = SemesterParser.sortKey(b) ?: 0
    return kb.compareTo(ka)
}

/** Natural sort: L2 before L10, case-insensitive with a stable tie-break. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val na = a.substring(startA, i).toLongOrNull() ?: 0L
            val nb = b.substring(startB, j).toLongOrNull() ?: 0L
            if (na != nb) return na.compareTo(nb)
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++
            j++
        }
    }
    val remaining = (a.length - i).compareTo(b.length - j)
    return if (remaining != 0) remaining else a.compareTo(b)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    return when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/* ------------------------------------------------------------------ */
/* Tree                                                                */
/* ------------------------------------------------------------------ */

data class LibraryFolderNode(
    val key: String,
    val name: String,
    val path: String,
    val folders: List<LibraryFolderNode>,
    val files: List<LibraryEntry>,
    val count: Int,
)

data class LibraryCategoryNode(
    val key: String,
    val type: DocType,
    val folders: List<LibraryFolderNode>,
    val files: List<LibraryEntry>,
    val count: Int,
)

data class LibraryCourseNode(
    val key: String,
    val courseFolder: String,
    val course: String,
    val semester: String?,
    val categories: List<LibraryCategoryNode>,
    val count: Int,
)

sealed interface LibraryRow {
    val key: String
    val depth: Int

    data class Course(val node: LibraryCourseNode) : LibraryRow {
        override val key: String = node.key
        override val depth: Int = 0
    }

    data class Category(
        val course: LibraryCourseNode,
        val node: LibraryCategoryNode,
    ) : LibraryRow {
        override val key: String = node.key
        override val depth: Int = 1
    }

    data class Folder(
        val course: LibraryCourseNode,
        val category: LibraryCategoryNode,
        val node: LibraryFolderNode,
        override val depth: Int,
    ) : LibraryRow {
        override val key: String = node.key
    }

    data class File(
        val course: LibraryCourseNode,
        val category: LibraryCategoryNode,
        val entry: LibraryEntry,
        override val depth: Int,
    ) : LibraryRow {
        override val key: String = "file:${entry.uri}"
    }
}

private class MutableFolder(val name: String, val path: String) {
    val children = LinkedHashMap<String, MutableFolder>()
    val files = mutableListOf<LibraryEntry>()
}

fun buildLibraryTree(entries: List<LibraryEntry>): List<LibraryCourseNode> =
    entries.groupBy { it.courseFolder }
        .map { (folder, list) -> buildCourse(folder, list) }
        .sortedWith { a, b -> naturalCompare(a.courseFolder, b.courseFolder) }

private fun buildCourse(courseFolder: String, entries: List<LibraryEntry>): LibraryCourseNode {
    val first = entries.first()
    val categories = DocType.entries.mapNotNull { type ->
        val typeEntries = entries.filter { it.type == type }
        if (typeEntries.isEmpty()) null else buildCategory(courseFolder, type, typeEntries)
    }
    return LibraryCourseNode(
        key = "course:$courseFolder",
        courseFolder = courseFolder,
        course = first.course,
        semester = first.semester,
        categories = categories,
        count = entries.size,
    )
}

private fun buildCategory(
    courseFolder: String,
    type: DocType,
    entries: List<LibraryEntry>,
): LibraryCategoryNode {
    val root = MutableFolder("", "")
    for (entry in entries) {
        val segments = entry.relativeFolder.split('/').filter { it.isNotBlank() }
        var start = 0
        if (segments.isNotEmpty()) {
            val first = segments[0]
            if (type == DocType.OTHERS) {
                // An exact "Others" folder is the category itself; unknown folders keep their name.
                if (first.equals(DocType.OTHERS.label, ignoreCase = true)) start = 1
            } else if (first.equals(type.label, ignoreCase = true)) {
                start = 1
            }
        }
        var node = root
        for (i in start until segments.size) {
            val segment = segments[i]
            val path = segments.take(i + 1).joinToString("/")
            node = node.children.getOrPut(segment) { MutableFolder(segment, path) }
        }
        node.files += entry
    }
    val folders = root.children.values
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .map { toFolderNode(it, courseFolder, type) }
    val files = root.files.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    return LibraryCategoryNode(
        key = "category:$courseFolder:${type.name}",
        type = type,
        folders = folders,
        files = files,
        count = entries.size,
    )
}

private fun toFolderNode(
    folder: MutableFolder,
    courseFolder: String,
    type: DocType,
): LibraryFolderNode {
    val folders = folder.children.values
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .map { toFolderNode(it, courseFolder, type) }
    val files = folder.files.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    return LibraryFolderNode(
        key = "folder:$courseFolder:${type.name}:${folder.path}",
        name = folder.name,
        path = folder.path,
        folders = folders,
        files = files,
        count = files.size + folders.sumOf { it.count },
    )
}

fun collectTreeKeys(tree: List<LibraryCourseNode>): Set<String> {
    val keys = LinkedHashSet<String>()
    fun addFolder(folder: LibraryFolderNode) {
        keys += folder.key
        folder.folders.forEach(::addFolder)
    }
    for (course in tree) {
        keys += course.key
        for (category in course.categories) {
            keys += category.key
            category.folders.forEach(::addFolder)
        }
    }
    return keys
}

fun flattenLibraryTree(tree: List<LibraryCourseNode>, collapsed: Set<String>): List<LibraryRow> {
    val rows = ArrayList<LibraryRow>()
    fun addFolder(
        course: LibraryCourseNode,
        category: LibraryCategoryNode,
        folder: LibraryFolderNode,
        depth: Int,
    ) {
        rows += LibraryRow.Folder(course, category, folder, depth)
        if (folder.key in collapsed) return
        folder.folders.forEach { addFolder(course, category, it, depth + 1) }
        folder.files.forEach { rows += LibraryRow.File(course, category, it, depth + 1) }
    }

    for (course in tree) {
        rows += LibraryRow.Course(course)
        if (course.key in collapsed) continue
        for (category in course.categories) {
            rows += LibraryRow.Category(course, category)
            if (category.key in collapsed) continue
            category.folders.forEach { addFolder(course, category, it, 2) }
            category.files.forEach { rows += LibraryRow.File(course, category, it, 2) }
        }
    }
    return rows
}

/* ------------------------------------------------------------------ */
/* Persistence                                                         */
/* ------------------------------------------------------------------ */

object LibraryJson {
    private const val VERSION = 1

    fun encodeIndex(index: LibraryIndex): String {
        val entries = JSONArray()
        for (entry in index.entries) {
            entries.put(JSONObject().apply {
                put("uri", entry.uri)
                put("name", entry.name)
                put("course", entry.course)
                put("courseFolder", entry.courseFolder)
                put("semester", entry.semester ?: JSONObject.NULL)
                put("type", entry.type.name)
                put("relativeFolder", entry.relativeFolder)
                put("size", entry.size)
                put("modified", entry.modified)
            })
        }
        return JSONObject().apply {
            put("version", VERSION)
            put("rootUri", index.rootUri)
            put("rootName", index.rootName)
            put("scannedAt", index.scannedAt)
            put("entries", entries)
        }.toString()
    }

    fun decodeIndex(text: String): LibraryIndex? = runCatching {
        val obj = JSONObject(text)
        if (obj.optInt("version", 0) != VERSION) return null
        val rootUri = obj.optString("rootUri")
        if (rootUri.isBlank()) return null
        val array = obj.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<LibraryEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val uri = item.optString("uri")
            if (uri.isBlank()) continue
            entries += LibraryEntry(
                uri = uri,
                name = item.optString("name"),
                course = item.optString("course"),
                courseFolder = item.optString("courseFolder"),
                semester = if (item.isNull("semester")) null else item.optString("semester").takeIf { it.isNotBlank() },
                type = DocType.fromStorage(item.optString("type")),
                relativeFolder = item.optString("relativeFolder"),
                size = item.optLong("size"),
                modified = item.optLong("modified"),
            )
        }
        LibraryIndex(
            rootUri = rootUri,
            rootName = obj.optString("rootName"),
            scannedAt = obj.optLong("scannedAt"),
            entries = entries,
        )
    }.getOrNull()

    fun encodeHistory(history: List<HistoryEntry>): String {
        val items = JSONArray()
        for (entry in history) {
            items.put(JSONObject().apply {
                put("uri", entry.uri)
                put("name", entry.name)
                put("openedAt", entry.openedAt)
            })
        }
        return JSONObject().apply {
            put("version", VERSION)
            put("items", items)
        }.toString()
    }

    fun decodeHistory(text: String): List<HistoryEntry> = runCatching {
        val obj = JSONObject(text)
        if (obj.optInt("version", 0) != VERSION) return emptyList()
        val items = obj.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<HistoryEntry>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val uri = item.optString("uri")
            if (uri.isBlank()) continue
            out += HistoryEntry(
                uri = uri,
                name = item.optString("name"),
                openedAt = item.optLong("openedAt"),
            )
        }
        out
    }.getOrDefault(emptyList())
}
