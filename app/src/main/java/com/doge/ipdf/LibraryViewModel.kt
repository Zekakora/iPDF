package com.doge.ipdf

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val loading: Boolean = true,
    val rootName: String = "",
    val rootUri: String? = null,
    val scannedAt: Long = 0L,
    val hasIndex: Boolean = false,
    val totalCount: Int = 0,
    val resultCount: Int = 0,
    val courses: List<CourseOption> = emptyList(),
    val folderOptions: List<CourseFolderOption> = emptyList(),
    val semesters: List<SemesterOption> = emptyList(),
    val typeCounts: Map<DocType, Int> = emptyMap(),
    val courseFacetBaseCount: Int = 0,
    val semesterFacetBaseCount: Int = 0,
    val typeFacetBaseCount: Int = 0,
    val tree: List<LibraryCourseNode> = emptyList(),
    val rows: List<LibraryRow> = emptyList(),
    val collapsedKeys: Set<String> = emptySet(),
    val allCollapsed: Boolean = false,
    val filters: LibraryFilters = LibraryFilters(),
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
    val history: List<HistoryEntry> = emptyList(),
    val entryByUri: Map<String, LibraryEntry> = emptyMap(),
    val scanning: Boolean = false,
    val progress: LibraryScanner.Progress? = null,
    val permissionLost: Boolean = false,
    val error: String? = null,
    val truncated: Boolean = false,
    val skippedRootFiles: Int = 0,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val scanner = LibraryScanner(application.contentResolver)

    private var index: LibraryIndex? = null
    private var allEntries: List<LibraryEntry> = emptyList()
    private val collapsed = mutableSetOf<String>()
    private var scanJob: Job? = null
    private var uiStateSaveJob: Job? = null
    private var lastClearedHistory: List<HistoryEntry>? = null

    var state by mutableStateOf(LibraryUiState())
        private set

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { store.loadIndex() }
            val history = withContext(Dispatchers.IO) { store.loadHistory() }
            val savedUi = withContext(Dispatchers.IO) { store.loadUiState() }
            index = cached
            allEntries = cached?.entries.orEmpty()
            val permissionLost = cached != null && !store.hasReadPermission(cached.rootUri)
            val restoreUi = cached != null && savedUi.rootUri == cached.rootUri
            collapsed.clear()
            if (restoreUi) collapsed.addAll(savedUi.collapsedKeys)
            state = buildState(
                filters = if (restoreUi) savedUi.filters else LibraryFilters(),
                history = history,
                permissionLost = permissionLost,
                firstVisibleItemIndex = if (restoreUi) savedUi.firstVisibleItemIndex else 0,
                firstVisibleItemScrollOffset = if (restoreUi) savedUi.firstVisibleItemScrollOffset else 0,
            )
            if (cached != null && !permissionLost && cached.entries.isNotEmpty() &&
                System.currentTimeMillis() - cached.scannedAt > STALE_MS
            ) {
                rescan(silent = true)
            }
        }
    }

    fun importTree(uri: Uri) {
        scanJob?.cancel()
        uiStateSaveJob?.cancel()
        index = null
        allEntries = emptyList()
        collapsed.clear()
        state = buildState(
            filters = LibraryFilters(),
            history = state.history,
            scanning = true,
            progress = null,
            permissionLost = false,
            error = null,
            truncated = false,
            skippedRootFiles = 0,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
        startScan(uri)
    }

    fun rescan(silent: Boolean = false) {
        val root = index?.rootUri ?: return
        scanJob?.cancel()
        state = state.copy(scanning = true, progress = null, error = null)
        startScan(Uri.parse(root))
    }

    private fun startScan(uri: Uri) {
        scanJob = viewModelScope.launch {
            try {
                val result = scanner.scan(uri) { progress ->
                    viewModelScope.launch { state = state.copy(progress = progress) }
                }
                val newIndex = LibraryIndex(
                    rootUri = uri.toString(),
                    rootName = result.rootName,
                    scannedAt = System.currentTimeMillis(),
                    entries = result.entries,
                )
                withContext(Dispatchers.IO) { store.saveIndex(newIndex) }
                index = newIndex
                allEntries = newIndex.entries
                state = buildState(
                    filters = state.filters,
                    history = state.history,
                    scanning = false,
                    progress = null,
                    permissionLost = false,
                    error = null,
                    truncated = result.truncated,
                    skippedRootFiles = result.skippedRootFiles,
                    firstVisibleItemIndex = state.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
                )
                persistUiState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                state = state.copy(
                    scanning = false,
                    progress = null,
                    error = failure.message ?: "Could not scan this folder",
                )
            }
        }
    }

    fun updateFilters(transform: (LibraryFilters) -> LibraryFilters) {
        val next = transform(state.filters)
        if (next == state.filters) return
        collapsed.clear()
        state = buildState(
            filters = next,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
        persistUiState()
    }

    fun resetFilters() {
        if (!state.filters.active) return
        collapsed.clear()
        state = buildState(
            filters = LibraryFilters(),
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
        persistUiState()
    }

    /** Selecting a class in the calendar shows that course and drops unrelated filters. */
    fun selectCourse(course: String) {
        val trimmed = course.trim()
        if (trimmed.isBlank()) return
        if (state.filters.course.equals(trimmed, ignoreCase = true) &&
            state.filters.type == null && state.filters.semester == null &&
            state.filters.query.isBlank()
        ) {
            return
        }
        collapsed.clear()
        state = buildState(
            filters = LibraryFilters(course = trimmed),
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
        persistUiState()
    }

    fun toggle(key: String) {
        if (!collapsed.remove(key)) collapsed.add(key)
        state = state.copy(
            rows = flattenLibraryTree(state.tree, collapsed),
            collapsedKeys = collapsed.toSet(),
            allCollapsed = isAllCollapsed(state.tree, collapsed),
        )
        persistUiState()
    }

    fun toggleAllCollapsed() {
        val keys = collectTreeKeys(state.tree)
        if (keys.isEmpty()) return
        val collapse = !state.allCollapsed
        collapsed.clear()
        if (collapse) collapsed.addAll(keys)
        state = state.copy(
            rows = flattenLibraryTree(state.tree, collapsed),
            collapsedKeys = collapsed.toSet(),
            allCollapsed = collapse,
        )
        persistUiState()
    }

    fun updateScrollPosition(index: Int, offset: Int) {
        val nextIndex = index.coerceAtLeast(0)
        val nextOffset = offset.coerceAtLeast(0)
        if (nextIndex == state.firstVisibleItemIndex && nextOffset == state.firstVisibleItemScrollOffset) {
            return
        }
        state = state.copy(
            firstVisibleItemIndex = nextIndex,
            firstVisibleItemScrollOffset = nextOffset,
        )
        persistUiState(debounceMs = 250L)
    }

    fun recordHistory(uri: String, name: String) {
        val now = System.currentTimeMillis()
        val updated = (
            listOf(HistoryEntry(uri, name, now)) +
                state.history.filterNot { it.uri == uri }
            ).take(MAX_HISTORY)
        state = state.copy(history = updated)
        viewModelScope.launch(Dispatchers.IO) { store.saveHistory(updated) }
    }

    fun removeHistory(uri: String) {
        val updated = state.history.filterNot { it.uri == uri }
        if (updated.size == state.history.size) return
        lastClearedHistory = null
        state = state.copy(history = updated)
        viewModelScope.launch(Dispatchers.IO) { store.saveHistory(updated) }
    }

    fun clearHistory() {
        if (state.history.isEmpty()) return
        lastClearedHistory = state.history
        state = state.copy(history = emptyList())
        viewModelScope.launch(Dispatchers.IO) { store.saveHistory(emptyList()) }
    }

    fun undoClearHistory() {
        val restored = lastClearedHistory ?: return
        lastClearedHistory = null
        state = state.copy(history = restored)
        viewModelScope.launch(Dispatchers.IO) { store.saveHistory(restored) }
    }

    fun checkPermission() {
        val root = index?.rootUri ?: return
        val lost = !store.hasReadPermission(root)
        if (lost != state.permissionLost) state = state.copy(permissionLost = lost)
    }

    private fun buildState(
        filters: LibraryFilters = state.filters,
        history: List<HistoryEntry> = state.history,
        scanning: Boolean = state.scanning,
        progress: LibraryScanner.Progress? = state.progress,
        permissionLost: Boolean = state.permissionLost,
        error: String? = state.error,
        truncated: Boolean = state.truncated,
        skippedRootFiles: Int = state.skippedRootFiles,
        firstVisibleItemIndex: Int = state.firstVisibleItemIndex,
        firstVisibleItemScrollOffset: Int = state.firstVisibleItemScrollOffset,
    ): LibraryUiState {
        val filtered = allEntries.filter { it.matchesFilters(filters) }
        val courseFacet = allEntries.filter { it.matchesFilters(filters, FilterFacet.COURSE) }
        val semesterFacet = allEntries.filter { it.matchesFilters(filters, FilterFacet.SEMESTER) }
        val typeFacet = allEntries.filter { it.matchesFilters(filters, FilterFacet.TYPE) }

        val allCourses = allEntries.map { it.course }.distinct()
            .sortedWith { a, b -> naturalCompare(a, b) }
        val courses = allCourses.map { course ->
            CourseOption(course, courseFacet.count { it.course == course })
        }
        val folderOptions = allEntries
            .groupBy { it.courseFolder }
            .map { (folder, entries) ->
                val first = entries.first()
                CourseFolderOption(
                    folder = folder,
                    course = first.course,
                    semester = first.semester,
                    count = entries.size,
                )
            }
            .sortedWith { a, b -> naturalCompare(a.folder, b.folder) }

        val allSemesters = allEntries.map { it.semester }.distinct()
            .sortedWith { a, b -> compareSemesterLabels(a, b) }
        val semesters = allSemesters.map { label ->
            SemesterOption(
                key = if (label == null) SemesterKey.Unassigned else SemesterKey.Named(label),
                label = label ?: "Unassigned",
                count = semesterFacet.count { it.semester == label },
            )
        }

        val typeCounts = DocType.entries.associateWith { type ->
            typeFacet.count { it.type == type }
        }

        val tree = if (filtered.isEmpty()) emptyList() else buildLibraryTree(filtered)
        val rows = flattenLibraryTree(tree, collapsed)

        return LibraryUiState(
            loading = false,
            rootName = index?.rootName.orEmpty(),
            rootUri = index?.rootUri,
            scannedAt = index?.scannedAt ?: 0L,
            hasIndex = index != null,
            totalCount = allEntries.size,
            resultCount = filtered.size,
            courses = courses,
            folderOptions = folderOptions,
            semesters = semesters,
            typeCounts = typeCounts,
            courseFacetBaseCount = courseFacet.size,
            semesterFacetBaseCount = semesterFacet.size,
            typeFacetBaseCount = typeFacet.size,
            tree = tree,
            rows = rows,
            collapsedKeys = collapsed.toSet(),
            allCollapsed = isAllCollapsed(tree, collapsed),
            filters = filters,
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
            history = history,
            entryByUri = allEntries.associateBy { it.uri },
            scanning = scanning,
            progress = progress,
            permissionLost = permissionLost,
            error = error,
            truncated = truncated,
            skippedRootFiles = skippedRootFiles,
        )
    }

    private fun persistUiState(debounceMs: Long = 0L) {
        val snapshot = state
        if (snapshot.rootUri == null) return
        uiStateSaveJob?.cancel()
        uiStateSaveJob = viewModelScope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val current = state
            withContext(Dispatchers.IO) {
                store.saveUiState(
                    LibraryUiPreferences(
                        rootUri = current.rootUri,
                        filters = current.filters,
                        collapsedKeys = current.collapsedKeys,
                        firstVisibleItemIndex = current.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = current.firstVisibleItemScrollOffset,
                    ),
                )
            }
        }
    }

    private fun isAllCollapsed(tree: List<LibraryCourseNode>, collapsed: Set<String>): Boolean {
        val keys = collectTreeKeys(tree)
        return keys.isNotEmpty() && keys.all { it in collapsed }
    }

    private companion object {
        const val MAX_HISTORY = 40
        const val STALE_MS = 24L * 60L * 60L * 1000L
    }
}
