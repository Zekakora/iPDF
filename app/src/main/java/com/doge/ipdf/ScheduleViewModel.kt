package com.doge.ipdf

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the subscribed calendar and the task list. The feed is fetched over HTTPS, parsed locally
 * and cached for four hours; everything else (tasks, mappings) is local JSON.
 */
class ScheduleViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ScheduleStore(application)
    private var syncJob: Job? = null
    private var lastSyncAttemptAt = 0L
    private val settingsWriteLock = Any()
    private val todoWriteLock = Any()

    var state by mutableStateOf(ScheduleUiState())
        private set

    init {
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) { store.loadSettings() }
            val todos = withContext(Dispatchers.IO) { store.loadTodos() }
            val cache = withContext(Dispatchers.IO) { store.loadCachedCalendar() }
            val cachedEvents = if (
                cache != null && settings.cachedUrl == settings.icalUrl && settings.icalUrl.isNotBlank()
            ) {
                runCatching { IcsCalendar.parse(cache) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val now = System.currentTimeMillis()
            state = state.copy(
                ready = true,
                now = now,
                icalUrl = settings.icalUrl,
                folderToCourse = settings.folderToCourse,
                events = cachedEvents,
                courses = IcsCalendar.courses(cachedEvents),
                todos = todos.sortedBy { it.dueAt },
                lastSyncedAt = settings.lastSyncedAt,
            )
            if (settings.icalUrl.isNotBlank() &&
                (cachedEvents.isEmpty() || now - settings.lastSyncedAt > CACHE_TTL_MS)
            ) {
                sync()
            }
        }

        viewModelScope.launch {
            while (isActive) {
                delay(30_000L)
                state = state.copy(now = System.currentTimeMillis())
                refreshIfStale()
            }
        }
    }

    fun refreshIfStale() {
        if (!state.ready || state.syncing || state.icalUrl.isBlank()) return
        val now = System.currentTimeMillis()
        val stale = state.lastSyncedAt == 0L || now - state.lastSyncedAt > CACHE_TTL_MS
        if (stale && now - lastSyncAttemptAt > RETRY_AFTER_FAILURE_MS) sync()
    }

    fun setIcalUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed == state.icalUrl) {
            if (trimmed.isNotBlank()) sync()
            return
        }
        syncJob?.cancel()
        syncJob = null
        state = state.copy(
            icalUrl = trimmed,
            events = emptyList(),
            courses = emptyList(),
            lastSyncedAt = 0L,
            syncing = false,
            syncError = null,
            now = System.currentTimeMillis(),
        )
        persistedSettings(cachedUrl = "")
        viewModelScope.launch(Dispatchers.IO) { store.clearCachedCalendar() }
        if (trimmed.isNotBlank()) sync()
    }

    fun sync() {
        val url = state.icalUrl.trim()
        if (url.isBlank()) {
            state = state.copy(syncError = "Add an iCalendar link first.")
            return
        }
        if (state.syncing) return
        lastSyncAttemptAt = System.currentTimeMillis()
        syncJob = viewModelScope.launch {
            state = state.copy(syncing = true, syncError = null)
            try {
                val feed = withContext(Dispatchers.IO) { store.fetchCalendar(url) }
                val events = withContext(Dispatchers.IO) { IcsCalendar.parse(feed) }
                val courses = IcsCalendar.courses(events)
                val syncedAt = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    store.saveCachedCalendar(feed)
                    store.saveSettings(
                        ScheduleSettings(
                            icalUrl = url,
                            folderToCourse = state.folderToCourse,
                            lastSyncedAt = syncedAt,
                            cachedUrl = url,
                        ),
                    )
                }
                state = state.copy(
                    events = events,
                    courses = courses,
                    lastSyncedAt = syncedAt,
                    syncing = false,
                    syncError = null,
                    now = System.currentTimeMillis(),
                )
            } catch (cancelled: CancellationException) {
                state = state.copy(syncing = false)
                throw cancelled
            } catch (failure: Throwable) {
                state = state.copy(
                    syncing = false,
                    syncError = failure.message?.takeIf { it.isNotBlank() }
                        ?: "Could not load the calendar.",
                )
            }
        }
    }

    fun setMapping(folder: String, courseKey: String?) {
        if (folder.isBlank()) return
        val next = LinkedHashMap(state.folderToCourse)
        if (courseKey.isNullOrBlank()) next.remove(folder) else next[folder] = courseKey
        if (next == state.folderToCourse) return
        state = state.copy(folderToCourse = next)
        persistedSettings()
    }

    fun autoMatch(folders: List<CourseFolderOption>) {
        val matched = autoMatchFolders(folders, state.courses, state.folderToCourse)
        if (matched == state.folderToCourse) return
        state = state.copy(folderToCourse = matched)
        persistedSettings()
    }

    fun addTask(title: String, dueAt: Long) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        val task = TodoTask(
            id = UUID.randomUUID().toString(),
            title = cleanTitle,
            notes = "",
            dueAt = dueAt,
            createdAt = System.currentTimeMillis(),
        )
        state = state.copy(todos = (state.todos + task).sortedWith(compareBy({ it.dueAt }, { it.createdAt })))
        persistTodos()
    }

    fun updateTask(id: String, title: String, dueAt: Long) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        if (state.todos.none { it.id == id }) return
        state = state.copy(
            todos = state.todos.map { todo ->
                if (todo.id == id) {
                    todo.copy(title = cleanTitle, dueAt = dueAt)
                } else {
                    todo
                }
            }.sortedWith(compareBy({ it.dueAt }, { it.createdAt })),
        )
        persistTodos()
    }

    fun toggleTask(id: String) {
        val completedAt = System.currentTimeMillis()
        if (state.todos.none { it.id == id }) return
        state = state.copy(
            todos = state.todos.map { todo ->
                if (todo.id == id) todo.copy(completedAt = if (todo.done) null else completedAt) else todo
            },
        )
        persistTodos()
    }

    fun deleteTask(id: String) {
        if (state.todos.none { it.id == id }) return
        state = state.copy(todos = state.todos.filterNot { it.id == id })
        persistTodos()
    }

    private fun persistedSettings(cachedUrl: String = state.icalUrl) {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(settingsWriteLock) {
                store.saveSettings(
                    ScheduleSettings(
                        icalUrl = state.icalUrl,
                        folderToCourse = state.folderToCourse,
                        lastSyncedAt = state.lastSyncedAt,
                        cachedUrl = cachedUrl,
                    ),
                )
            }
        }
    }

    private fun persistTodos() {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(todoWriteLock) { store.saveTodos(state.todos) }
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 4L * 60L * 60L * 1000L
        const val RETRY_AFTER_FAILURE_MS = 10L * 60L * 1000L
    }
}
