package com.doge.ipdf

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Index/history persistence and SAF permission checks for the library. */
class LibraryStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "library")
    private val indexFile = File(dir, "index.json")
    private val historyFile = File(dir, "history.json")
    private val uiStateFile = File(dir, "ui-state.json")

    fun loadIndex(): LibraryIndex? {
        if (!indexFile.isFile) return null
        return runCatching { LibraryJson.decodeIndex(indexFile.readText()) }.getOrNull()
    }

    fun saveIndex(index: LibraryIndex) {
        writeAtomic(indexFile, LibraryJson.encodeIndex(index))
    }

    fun loadHistory(): List<HistoryEntry> {
        if (!historyFile.isFile) return emptyList()
        return runCatching { LibraryJson.decodeHistory(historyFile.readText()) }.getOrDefault(emptyList())
    }

    fun saveHistory(history: List<HistoryEntry>) {
        writeAtomic(historyFile, LibraryJson.encodeHistory(history))
    }

    fun loadUiState(): LibraryUiPreferences {
        if (!uiStateFile.isFile) return LibraryUiPreferences()
        return runCatching {
            val json = JSONObject(uiStateFile.readText())
            val filtersJson = json.optJSONObject("filters") ?: JSONObject()
            fun JSONObject.optionalString(name: String): String? {
                if (isNull(name)) return null
                return optString(name).takeUnless {
                    it.isBlank() || it.equals("null", ignoreCase = true)
                }
            }
            val semester = when (filtersJson.optString("semesterKind")) {
                "unassigned" -> SemesterKey.Unassigned
                "named" -> filtersJson.optionalString("semesterLabel")
                    ?.let(SemesterKey::Named)
                else -> null
            }
            val filters = LibraryFilters(
                course = filtersJson.optionalString("course"),
                type = filtersJson.optionalString("type")
                    ?.let { value -> DocType.entries.firstOrNull { it.name == value } },
                semester = semester,
                query = filtersJson.optString("query"),
            )
            val collapsedKeys = buildSet {
                val array = json.optJSONArray("collapsedKeys") ?: JSONArray()
                for (index in 0 until array.length()) add(array.optString(index))
            }.filterTo(linkedSetOf()) { it.isNotBlank() }
            LibraryUiPreferences(
                rootUri = json.optionalString("rootUri"),
                filters = filters,
                collapsedKeys = collapsedKeys,
                firstVisibleItemIndex = json.optInt("firstVisibleItemIndex", 0).coerceAtLeast(0),
                firstVisibleItemScrollOffset = json.optInt("firstVisibleItemScrollOffset", 0)
                    .coerceAtLeast(0),
            )
        }.getOrDefault(LibraryUiPreferences())
    }

    fun saveUiState(preferences: LibraryUiPreferences) {
        val filters = JSONObject().apply {
            put("course", preferences.filters.course ?: JSONObject.NULL)
            put("type", preferences.filters.type?.name ?: JSONObject.NULL)
            when (val semester = preferences.filters.semester) {
                null -> {
                    put("semesterKind", JSONObject.NULL)
                    put("semesterLabel", JSONObject.NULL)
                }
                SemesterKey.Unassigned -> {
                    put("semesterKind", "unassigned")
                    put("semesterLabel", JSONObject.NULL)
                }
                is SemesterKey.Named -> {
                    put("semesterKind", "named")
                    put("semesterLabel", semester.label)
                }
            }
            put("query", preferences.filters.query)
        }
        val json = JSONObject().apply {
            put("rootUri", preferences.rootUri ?: JSONObject.NULL)
            put("filters", filters)
            put("collapsedKeys", JSONArray(preferences.collapsedKeys.toList()))
            put("firstVisibleItemIndex", preferences.firstVisibleItemIndex.coerceAtLeast(0))
            put(
                "firstVisibleItemScrollOffset",
                preferences.firstVisibleItemScrollOffset.coerceAtLeast(0),
            )
        }
        writeAtomic(uiStateFile, json.toString())
    }

    fun hasReadPermission(uri: String): Boolean =
        appContext.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission
        }

    fun hasWritePermission(uri: String): Boolean =
        appContext.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isWritePermission
        }

    private fun writeAtomic(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text)
        if (file.exists() && !file.delete()) {
            // Fall back to an in-place write; a partial index is recoverable by rescanning.
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
