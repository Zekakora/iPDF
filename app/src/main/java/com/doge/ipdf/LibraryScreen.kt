package com.doge.ipdf

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val PanePadding = 20.dp
private val RowStart = 8.dp
private val IndentStep = 14.dp
private val HistoryWidthMin = 276.dp
private val HistoryWidthMax = 340.dp
private val WideBreakpoint = 840.dp

private fun DocType.color(): Color = when (this) {
    DocType.LECTURE -> Color(0xFF4F46E5)
    DocType.EXERCISE -> Color(0xFFB45309)
    DocType.EXAM -> Color(0xFFB3261E)
    DocType.OTHERS -> Color(0xFF6B7280)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    schedule: ScheduleViewModel,
    onOpenFolder: () -> Unit,
    onRescan: () -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    onOpenHistory: (HistoryEntry) -> Unit,
    onOpenFilePicker: () -> Unit,
    onFiltersChange: ((LibraryFilters) -> LibraryFilters) -> Unit,
    onScrollPositionChanged: (Int, Int) -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onReset: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onUndoClearHistory: () -> Unit,
    onThemeSettings: () -> Unit,
    onSelectCourse: (String) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var historySheetOpen by remember { mutableStateOf(false) }
    var scheduleSheetOpen by remember { mutableStateOf(false) }
    val historySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheduleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val clearHistory: () -> Unit = {
        onClearHistory()
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "History cleared",
                actionLabel = "Undo",
                withDismissAction = false,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoClearHistory()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WideBreakpoint
        val historyWidth = (maxWidth * 0.30f).coerceIn(HistoryWidthMin, HistoryWidthMax)

        Row(Modifier.fillMaxSize()) {
            LibraryLeftPane(
                state = state,
                wide = wide,
                onOpenFolder = onOpenFolder,
                onRescan = onRescan,
                onOpenFilePicker = onOpenFilePicker,
                onFiltersChange = onFiltersChange,
                onScrollPositionChanged = onScrollPositionChanged,
                onToggle = onToggle,
                onToggleAll = onToggleAll,
                onReset = onReset,
                onOpenEntry = onOpenEntry,
                onHistoryClick = { historySheetOpen = true },
                onScheduleClick = { scheduleSheetOpen = true },
                onThemeSettings = onThemeSettings,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            if (wide) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                )
                Column(Modifier.width(historyWidth).fillMaxHeight()) {
                    HistoryPane(
                        history = state.history,
                        entryByUri = state.entryByUri,
                        onOpen = onOpenHistory,
                        onRemove = onRemoveHistory,
                        onClear = clearHistory,
                        modifier = Modifier.weight(0.9f).fillMaxWidth(),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                    )
                    CalendarTodoPane(
                        schedule = schedule,
                        folders = state.folderOptions,
                        onSelectCourse = onSelectCourse,
                        modifier = Modifier.weight(1.1f).fillMaxWidth(),
                    )
                }
            }
        }

        if (!wide && historySheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { historySheetOpen = false },
                sheetState = historySheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                HistoryPane(
                    history = state.history,
                    entryByUri = state.entryByUri,
                    onOpen = onOpenHistory,
                    onRemove = onRemoveHistory,
                    onClear = clearHistory,
                    modifier = Modifier.fillMaxWidth().height(460.dp),
                )
            }
        }

        if (!wide && scheduleSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { scheduleSheetOpen = false },
                sheetState = scheduleSheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                CalendarTodoPane(
                    schedule = schedule,
                    folders = state.folderOptions,
                    onSelectCourse = { course ->
                        onSelectCourse(course)
                        scheduleSheetOpen = false
                    },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
    }
}

/* ------------------------------------------------------------------ */
/* Left pane                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun LibraryLeftPane(
    state: LibraryUiState,
    wide: Boolean,
    onOpenFolder: () -> Unit,
    onRescan: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onFiltersChange: ((LibraryFilters) -> LibraryFilters) -> Unit,
    onScrollPositionChanged: (Int, Int) -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onReset: () -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    onHistoryClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onThemeSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = PanePadding, end = PanePadding, top = 16.dp)) {
        LibraryHeader(
            state = state,
            wide = wide,
            onOpenFolder = onOpenFolder,
            onRescan = onRescan,
            onOpenFilePicker = onOpenFilePicker,
            onHistoryClick = onHistoryClick,
            onScheduleClick = onScheduleClick,
            onThemeSettings = onThemeSettings,
            onQueryChange = { value -> onFiltersChange { it.copy(query = value) } },
        )

        val body = when {
            state.loading -> LibraryBody.Loading
            !state.hasIndex -> if (state.scanning) LibraryBody.Scanning else LibraryBody.Empty
            state.totalCount == 0 -> LibraryBody.NoPdf
            else -> LibraryBody.List
        }

        AnimatedContent(
            targetState = body,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                (
                    fadeIn(
                        tween(
                            durationMillis = 220,
                            delayMillis = 30,
                            easing = FastOutSlowInEasing,
                        ),
                    ) +
                        slideInVertically(
                            initialOffsetY = { it / 28 },
                            animationSpec = tween(
                                durationMillis = 260,
                                delayMillis = 20,
                                easing = FastOutSlowInEasing,
                            ),
                        ) +
                        scaleIn(
                            initialScale = 0.99f,
                            animationSpec = tween(
                                durationMillis = 260,
                                delayMillis = 20,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    ).togetherWith(fadeOut(tween(durationMillis = 140)))
            },
            contentAlignment = Alignment.TopCenter,
            label = "libraryBody",
        ) { target ->
            when (target) {
                LibraryBody.Loading -> LoadingState(Modifier.fillMaxSize())
                LibraryBody.Scanning -> ScanningState(state, Modifier.fillMaxSize())
                LibraryBody.Empty -> EmptyLibraryState(
                    error = state.error,
                    onOpenFolder = onOpenFolder,
                    onOpenFilePicker = onOpenFilePicker,
                    modifier = Modifier.fillMaxSize(),
                )
                LibraryBody.NoPdf -> NoPdfState(
                    scanning = state.scanning,
                    onRescan = onRescan,
                    onOpenFolder = onOpenFolder,
                    modifier = Modifier.fillMaxSize(),
                )
                LibraryBody.List -> LibraryListBody(
                    state = state,
                    onFiltersChange = onFiltersChange,
                    onScrollPositionChanged = onScrollPositionChanged,
                    onToggleAll = onToggleAll,
                    onReset = onReset,
                    onToggle = onToggle,
                    onOpenEntry = onOpenEntry,
                    onOpenFolder = onOpenFolder,
                    onRescan = onRescan,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private enum class LibraryBody { Loading, Scanning, Empty, NoPdf, List }

@Composable
private fun LibraryListBody(
    state: LibraryUiState,
    onFiltersChange: ((LibraryFilters) -> LibraryFilters) -> Unit,
    onScrollPositionChanged: (Int, Int) -> Unit,
    onToggleAll: () -> Unit,
    onReset: () -> Unit,
    onToggle: (String) -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    onOpenFolder: () -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        FilterBar(
            state = state,
            onFiltersChange = onFiltersChange,
            onToggleAll = onToggleAll,
            onReset = onReset,
        )
        if (state.scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
            )
        }
        if (state.permissionLost) {
            NoticeBanner(
                text = "Folder access was lost. Re-select the folder to keep reading.",
                actionLabel = "Re-select",
                onAction = onOpenFolder,
            )
        }
        state.error?.let { message ->
            NoticeBanner(
                text = message,
                actionLabel = if (state.hasIndex) "Retry" else "Choose folder",
                onAction = if (state.hasIndex) onRescan else onOpenFolder,
            )
        }
        TreeArea(
            state = state,
            onToggle = onToggle,
            onOpenEntry = onOpenEntry,
            onScrollPositionChanged = onScrollPositionChanged,
            onReset = onReset,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    wide: Boolean,
    onOpenFolder: () -> Unit,
    onRescan: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onHistoryClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onThemeSettings: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "iPDF",
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = headerSubtitle(state),
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SearchBox(
            query = state.filters.query,
            enabled = state.hasIndex,
            onQueryChange = onQueryChange,
            modifier = Modifier.width(if (wide) 196.dp else 150.dp),
        )

        if (state.hasIndex) {
            RoundIconButton(
                onClick = onRescan,
                contentDescription = "Rescan folder",
            ) {
                if (state.scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    RefreshGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        RoundIconButton(
            onClick = onOpenFolder,
            contentDescription = "Choose folder",
        ) {
            FolderGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }

        RoundIconButton(
            onClick = onOpenFilePicker,
            contentDescription = "Open a single PDF",
            onLongClick = onThemeSettings,
            background = MaterialTheme.colorScheme.primaryContainer,
        ) {
            PlusGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.onPrimaryContainer)
        }

        if (!wide) {
            RoundIconButton(
                onClick = onScheduleClick,
                contentDescription = "Calendar and tasks",
            ) {
                CalendarGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RoundIconButton(
                onClick = onHistoryClick,
                contentDescription = "History",
            ) {
                HistoryGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun headerSubtitle(state: LibraryUiState): String {
    if (state.loading) return "Loading…"
    if (!state.hasIndex) return "No folder selected"
    if (state.scanning) {
        val progress = state.progress
        return if (progress != null) {
            "Scanning… ${progress.courses} courses · ${progress.files} PDFs"
        } else {
            "Scanning…"
        }
    }
    val root = state.rootName.ifBlank { "Library" }
    return "$root · ${state.courses.size} courses"
}

@Composable
private fun SearchBox(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(38.dp).alpha(if (enabled) 1f else 0.45f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search files",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.6f, animationSpec = tween(140)),
                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.6f, animationSpec = tween(100)),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    CloseGlyph(Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .then(clickModifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/* ------------------------------------------------------------------ */
/* Filter bar                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun FilterBar(
    state: LibraryUiState,
    onFiltersChange: ((LibraryFilters) -> LibraryFilters) -> Unit,
    onToggleAll: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoursePill(
            state = state,
            onSelect = { course -> onFiltersChange { it.copy(course = course) } },
        )
        Spacer(Modifier.width(8.dp))
        SemesterPill(
            state = state,
            onSelect = { semester -> onFiltersChange { it.copy(semester = semester) } },
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (state.filters.active) "${state.resultCount} of ${state.totalCount}" else "${state.totalCount} files",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (state.rows.isNotEmpty()) {
            TextButton(
                onClick = onToggleAll,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text(
                    text = if (state.allCollapsed) "Expand" else "Collapse",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (state.filters.active) {
            TextButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text("Reset", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    TypeChips(
        state = state,
        onSelect = { type -> onFiltersChange { it.copy(type = type) } },
    )

    Spacer(Modifier.height(9.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    )
}

@Composable
private fun CoursePill(
    state: LibraryUiState,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterPillSurface(
            label = state.filters.course ?: "All courses",
            active = state.filters.course != null,
            maxWidth = 220.dp,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(310.dp).heightIn(max = 380.dp),
        ) {
            DropdownMenuItem(
                text = {
                    DialogRow("All courses", state.courseFacetBaseCount)
                },
                leadingIcon = {
                    if (state.filters.course == null) {
                        CheckGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = {
                    open = false
                    onSelect(null)
                },
            )
            state.courses.forEach { option ->
                val selected = state.filters.course == option.course
                DropdownMenuItem(
                    text = { DialogRow(option.course, option.count) },
                    leadingIcon = {
                        if (selected) {
                            CheckGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                        }
                    },
                    enabled = option.count > 0 || selected,
                    onClick = {
                        open = false
                        onSelect(if (selected) null else option.course)
                    },
                )
            }
        }
    }
}

@Composable
private fun SemesterPill(
    state: LibraryUiState,
    onSelect: (SemesterKey?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = when (val semester = state.filters.semester) {
        null -> "All semesters"
        SemesterKey.Unassigned -> "Unassigned"
        is SemesterKey.Named -> semester.label
    }
    Box {
        FilterPillSurface(
            label = label,
            active = state.filters.semester != null,
            maxWidth = 138.dp,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(240.dp).heightIn(max = 340.dp),
        ) {
            DropdownMenuItem(
                text = { DialogRow("All semesters", state.semesterFacetBaseCount) },
                leadingIcon = {
                    if (state.filters.semester == null) {
                        CheckGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = {
                    open = false
                    onSelect(null)
                },
            )
            state.semesters.forEach { option ->
                val selected = state.filters.semester == option.key
                DropdownMenuItem(
                    text = { DialogRow(option.label, option.count) },
                    leadingIcon = {
                        if (selected) {
                            CheckGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                        }
                    },
                    enabled = option.count > 0 || selected,
                    onClick = {
                        open = false
                        onSelect(if (selected) null else option.key)
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterPillSurface(
    label: String,
    active: Boolean,
    maxWidth: Dp,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 190),
        label = "pillContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 190),
        label = "pillContent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(durationMillis = 190),
        label = "pillBorder",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.height(34.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, end = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxWidth - 36.dp),
            )
            Spacer(Modifier.width(5.dp))
            ChevronGlyph(
                expanded = true,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DialogRow(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TypeChips(
    state: LibraryUiState,
    onSelect: (DocType?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Type",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TypeChip(
                label = "All",
                count = state.typeFacetBaseCount,
                selected = state.filters.type == null,
                dot = null,
                enabled = true,
                onClick = { onSelect(null) },
            )
            DocType.entries.forEach { type ->
                val count = state.typeCounts[type] ?: 0
                val selected = state.filters.type == type
                TypeChip(
                    label = type.label,
                    count = count,
                    selected = selected,
                    dot = type.color(),
                    enabled = count > 0 || selected,
                    onClick = { onSelect(if (selected) null else type) },
                )
            }
        }
    }
}

@Composable
private fun TypeChip(
    label: String,
    count: Int,
    selected: Boolean,
    dot: Color?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "chipContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 180),
        label = "chipContent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(durationMillis = 180),
        label = "chipBorder",
    )
    val countColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 180),
        label = "chipCount",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.height(32.dp).alpha(if (enabled) 1f else 0.42f),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot != null) {
                Box(Modifier.size(6.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = count.toString(),
                fontSize = 10.5.sp,
                color = countColor,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Tree                                                                */
/* ------------------------------------------------------------------ */

@Composable
@OptIn(FlowPreview::class)
private fun TreeArea(
    state: LibraryUiState,
    onToggle: (String) -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    onScrollPositionChanged: (Int, Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Construct the list at the saved position. Restoring it in a LaunchedEffect caused a
    // visible top-of-list flash when returning from the reader.
    val listState = remember(state.rootUri, state.filters) {
        LazyListState(
            firstVisibleItemIndex = state.firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset.coerceAtLeast(0),
        )
    }
    LaunchedEffect(state.rootUri, state.filters) {
        if (state.rootUri != null && state.rows.isNotEmpty()) {
            listState.scrollToItem(
                index = state.firstVisibleItemIndex.coerceAtMost(state.rows.lastIndex),
                scrollOffset = state.firstVisibleItemScrollOffset,
            )
        }
    }
    LaunchedEffect(listState) {
        androidx.compose.runtime.snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.debounce(250L).collect { (index, offset) ->
            onScrollPositionChanged(index, offset)
        }
    }
    Column(modifier.fillMaxHeight()) {
        if (state.skippedRootFiles > 0) {
            Text(
                text = "${state.skippedRootFiles} PDFs in the root folder were skipped",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (state.truncated) {
            Text(
                text = "Showing the first 5,000 files",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        if (state.resultCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No PDFs match these filters",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onReset) {
                        Text("Reset filters", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(state.rows, key = { it.key }) { row ->
                    LibraryTreeRow(
                        row = row,
                        collapsed = state.collapsedKeys,
                        onToggle = onToggle,
                        onOpenEntry = onOpenEntry,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTreeRow(
    row: LibraryRow,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    onOpenEntry: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (row) {
        is LibraryRow.Course -> CourseRow(row, collapsed, onToggle, modifier)
        is LibraryRow.Category -> CategoryRow(row, collapsed, onToggle, modifier)
        is LibraryRow.Folder -> FolderRow(row, collapsed, onToggle, modifier)
        is LibraryRow.File -> FileRow(row, onOpenEntry, modifier)
    }
}

@Composable
private fun CourseRow(
    row: LibraryRow.Course,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TreeRowContainer(
        startPadding = RowStart,
        height = 40.dp,
        onClick = { onToggle(row.key) },
        modifier = modifier,
    ) {
        ChevronGlyph(
            expanded = row.key !in collapsed,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        FolderGlyph(Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.node.courseFolder,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.node.count.toString(),
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoryRow(
    row: LibraryRow.Category,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TreeRowContainer(
        startPadding = RowStart + IndentStep,
        height = 32.dp,
        onClick = { onToggle(row.key) },
        modifier = modifier,
    ) {
        ChevronGlyph(
            expanded = row.key !in collapsed,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(6.dp).background(row.node.type.color(), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = row.node.type.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.node.count.toString(),
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderRow(
    row: LibraryRow.Folder,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TreeRowContainer(
        startPadding = RowStart + IndentStep * row.depth,
        height = 28.dp,
        onClick = { onToggle(row.key) },
        modifier = modifier,
    ) {
        ChevronGlyph(
            expanded = row.key !in collapsed,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        FolderGlyph(Modifier.size(14.dp), MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
        Spacer(Modifier.width(6.dp))
        Text(
            text = row.node.name,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = row.node.count.toString(),
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    row: LibraryRow.File,
    onOpenEntry: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    TreeRowContainer(
        startPadding = RowStart + IndentStep * row.depth,
        height = 48.dp,
        onClick = { onOpenEntry(row.entry) },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            FileGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatBytes(row.entry.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TreeRowContainer(
    startPadding: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = startPadding, end = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/* ------------------------------------------------------------------ */
/* Empty / loading / banners                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ScanningState(state: LibraryUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Scanning folder…", fontSize = 13.sp)
        state.progress?.let { progress ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${progress.courses} courses · ${progress.files} PDFs",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyLibraryState(
    error: String?,
    onOpenFolder: () -> Unit,
    onOpenFilePicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error != null) {
            NoticeBanner(
                text = error,
                actionLabel = "Choose folder",
                onAction = onOpenFolder,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(62.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            FolderPlusGlyph(Modifier.size(28.dp), MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text("Build your library", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Pick the folder that holds your courses. Each top-level folder is one course; " +
                "the semester can be a prefix or suffix (26ws-HPC, Power Electronics-27ss).",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
            modifier = Modifier.widthIn(max = 430.dp),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onOpenFolder,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                FolderPlusGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Choose folder", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onOpenFilePicker,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                PlusGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Open a PDF", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(20.dp))
        TreeExample()
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Files stay on this device. Import only remembers the folder and its PDF index.",
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TreeExample() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.widthIn(max = 410.dp),
    ) {
        Text(
            text = buildString {
                appendLine("AAAMasters/")
                appendLine("  26ws-HPC/")
                appendLine("    Lecture/")
                appendLine("      Chapter 1/")
                appendLine("    Exercise/")
                appendLine("    Exam/")
                appendLine("    Others/")
                appendLine("  Power Electronics-27ss/")
                append("    Lecture/")
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun NoPdfState(
    scanning: Boolean,
    onRescan: () -> Unit,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (scanning) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
        }
        Text("No PDFs found in this folder", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Put PDFs inside course folders (Lecture / Exercise / Exam / Others), then rescan.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRescan, shape = CircleShape) { Text("Rescan") }
            OutlinedButton(onClick = onOpenFolder, shape = CircleShape) { Text("Change folder") }
        }
    }
}

@Composable
private fun NoticeBanner(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(actionLabel, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* History                                                             */
/* ------------------------------------------------------------------ */

private sealed interface HistoryRowItem {
    val key: String

    data class Header(val label: String, override val key: String) : HistoryRowItem
    data class Item(val entry: HistoryEntry, override val key: String) : HistoryRowItem
}

@Composable
private fun HistoryPane(
    history: List<HistoryEntry>,
    entryByUri: Map<String, LibraryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "History",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text("Clear", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Recently opened PDFs appear here.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else {
            val rows = remember(history) { buildHistoryRows(history) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is HistoryRowItem.Header -> HistorySectionHeader(row.label)
                        is HistoryRowItem.Item -> HistoryRow(
                            entry = row.entry,
                            meta = entryByUri[row.entry.uri]?.let { "${it.course} · ${it.type.label}" }
                                ?: "Single file",
                            onOpen = { onOpen(row.entry) },
                            onRemove = { onRemove(row.entry.uri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySectionHeader(label: String) {
    Text(
        text = label.uppercase(Locale.getDefault()),
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    meta: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val today = remember { LocalDate.now() }
    val openedDate = remember(entry.openedAt) {
        Instant.ofEpochMilli(entry.openedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val timeText = if (openedDate == today || openedDate == today.minusDays(1)) {
        timeFormat.format(Date(entry.openedAt))
    } else {
        dateFormat.format(Date(entry.openedAt))
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                FileGlyph(Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.name,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = timeText,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = meta,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Open") },
                onClick = {
                    menuOpen = false
                    onOpen()
                },
            )
            DropdownMenuItem(
                text = { Text("Remove from history") },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

private fun buildHistoryRows(history: List<HistoryEntry>): List<HistoryRowItem> {
    val today = LocalDate.now()
    val yesterday = today.minusDays(1)
    val rows = ArrayList<HistoryRowItem>()
    var lastLabel: String? = null
    history.forEachIndexed { index, entry ->
        val date = Instant.ofEpochMilli(entry.openedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val label = when (date) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> "Earlier"
        }
        if (label != lastLabel) {
            rows += HistoryRowItem.Header(label, "history-header:${index}:$label")
            lastLabel = label
        }
        rows += HistoryRowItem.Item(entry, "history:${entry.uri}")
    }
    return rows
}
