package com.doge.ipdf

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat as JavaDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

private val TodayGlow = Color(0xFFFFDDB0)
private val TodayGlowBorder = Color(0xFFE2B36C)
private val TodayInk = Color(0xFF4A2F00)
private val AgendaRowHeight = 40.dp
private val TodoRowHeight = 36.dp
private val TodoVisibleRows = 3
private val TodoViewportHeight = TodoRowHeight * TodoVisibleRows + 4.dp

/**
 * The calendar + task panel under History. The calendar is a three-row agenda: past classes drop
 * off, the current or next class stays on top, and the list can be scrolled down for later classes.
 */
@Composable
fun CalendarTodoPane(
    schedule: ScheduleViewModel,
    folders: List<CourseFolderOption>,
    onSelectCourse: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = schedule.state
    val now = state.now
    val zone = remember { ZoneId.systemDefault() }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsHint by remember { mutableStateOf<String?>(null) }
    var allTasksOpen by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TodoTask?>(null) }

    val upcoming = remember(state.events, now) {
        state.events
            .filter { it.endAt > now }
            .sortedWith(compareBy({ it.startAt }, { it.summary }))
    }
    val mappedCourseKeys = remember(state.folderToCourse) {
        state.folderToCourse.values.toSet()
    }
    val active = remember(state.todos) { activeTodos(state.todos) }

    fun openTaskEditor(task: TodoTask?) {
        editingTask = task
        editorOpen = true
    }

    fun openSettings(hint: String? = null) {
        settingsHint = hint
        settingsOpen = true
    }

    fun handleEvent(event: CalendarEvent) {
        val mapping = state.folderToCourse
        val exact = folders.firstOrNull { mapping[it.folder] == event.courseKey }
        // Feeds occasionally rewrite punctuation/whitespace in a title without changing the course.
        // Accept a normalized key as well and repair the stored mapping so the next tap is exact.
        val looseKey = normalizeMappingKey(event.courseKey)
        val loose = if (exact == null) {
            folders.firstOrNull { folder ->
                val mapped = mapping[folder.folder] ?: return@firstOrNull false
                normalizeMappingKey(mapped) == looseKey
            }
        } else {
            null
        }
        // A feed can expose the same course title under two keys (for example after a wording
        // change). If the exact key is not mapped, accept a folder whose mapped course has the
        // same title as this event.
        val mappedByTitle = if (exact == null && loose == null) {
            folders.firstOrNull { folder ->
                val mappedKey = mapping[folder.folder] ?: return@firstOrNull false
                val mappedCourse = state.courses.firstOrNull { it.key == mappedKey }
                    ?: return@firstOrNull false
                normalizeMappingKey(mappedCourse.title) == normalizeMappingKey(event.courseTitle)
            }
        } else {
            null
        }
        val mappedFolder = exact ?: loose ?: mappedByTitle
        if (mappedFolder != null) {
            if (exact == null && loose != null) schedule.setMapping(mappedFolder.folder, event.courseKey)
            onSelectCourse(mappedFolder.course)
            return
        }

        // A folder that was never mapped can still be selected when its library course name is
        // the same as the calendar course name (or a clear part/word match of it).
        val direct = folders.firstOrNull { folderMatchesCourse(it.course, event.courseTitle) }
        if (direct != null) {
            schedule.setMapping(direct.folder, event.courseKey)
            onSelectCourse(direct.course)
            return
        }

        android.util.Log.w(
            "Schedule",
            "Unmapped class key='${event.courseKey}' title='${event.courseTitle}' " +
                "mapping=$mapping folders=${folders.map { it.folder to it.course }}",
        )
        openSettings("\"${event.courseTitle}\" is not mapped to a library folder yet.")
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        TodoHeader(
            openCount = active.size,
            onAddTask = { openTaskEditor(null) },
            onOpenAllTasks = { allTasksOpen = true },
            onOpenSettings = { openSettings() },
        )

        if (active.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(TodoViewportHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No tasks yet. Tap + to add one.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(TodoViewportHeight),
                contentPadding = PaddingValues(bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(active, key = { it.id }) { task ->
                    TodoRow(
                        task = task,
                        now = now,
                        zone = zone,
                        onToggle = { schedule.toggleTask(task.id) },
                        onEdit = { openTaskEditor(task) },
                        onDelete = { schedule.deleteTask(task.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        AgendaList(
            upcoming = upcoming,
            mappedCourseKeys = mappedCourseKeys,
            hasLink = state.icalUrl.isNotBlank(),
            now = now,
            zone = zone,
            onEventClick = ::handleEvent,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }

    if (editorOpen) {
        TaskEditorDialog(
            initial = editingTask,
            onDismiss = { editorOpen = false },
            onSave = { title, dueAt ->
                val editing = editingTask
                if (editing == null) {
                    schedule.addTask(title, dueAt)
                } else {
                    schedule.updateTask(editing.id, title, dueAt)
                }
                editorOpen = false
            },
        )
    }

    if (allTasksOpen) {
        AllTasksDialog(
            todos = state.todos,
            now = now,
            zone = zone,
            onToggle = schedule::toggleTask,
            onEdit = { task ->
                allTasksOpen = false
                openTaskEditor(task)
            },
            onDelete = schedule::deleteTask,
            onDismiss = { allTasksOpen = false },
        )
    }

    if (settingsOpen) {
        ScheduleSettingsDialog(
            state = state,
            folders = folders,
            hint = settingsHint,
            onSaveUrl = { url ->
                settingsHint = null
                schedule.setIcalUrl(url)
            },
            onSync = schedule::sync,
            onMap = schedule::setMapping,
            onAutoMatch = { schedule.autoMatch(folders) },
            onDismiss = { settingsOpen = false },
        )
    }
}

/* ------------------------------------------------------------------ */
/* To-do header                                                        */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoHeader(
    openCount: Int,
    onAddTask: () -> Unit,
    onOpenAllTasks: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "To-do",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (openCount > 0) {
            Text(
                text = "$openCount open",
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .combinedClickable(
                        onClick = onAddTask,
                        onLongClick = { menuOpen = true },
                    )
                    .semantics { contentDescription = "Add task" },
                contentAlignment = Alignment.Center,
            ) {
                PlusGlyph(Modifier.size(13.dp), MaterialTheme.colorScheme.onPrimaryContainer)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("All tasks — history & future") },
                    onClick = {
                        menuOpen = false
                        onOpenAllTasks()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Calendar & task settings") },
                    onClick = {
                        menuOpen = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Agenda list                                                         */
/* ------------------------------------------------------------------ */

private data class AgendaDayGroup(
    val date: LocalDate,
    val label: String,
    val isToday: Boolean,
    val events: List<CalendarEvent>,
)

private fun buildAgendaGroups(
    events: List<CalendarEvent>,
    today: LocalDate,
    zone: ZoneId,
    locale: Locale,
): List<AgendaDayGroup> {
    val fullDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
    val shortDate = DateTimeFormatter.ofPattern("EEE, d MMM", locale)
    val byDate = LinkedHashMap<LocalDate, MutableList<CalendarEvent>>()
    for (event in events) {
        val date = localDateTime(event.startAt, zone).toLocalDate()
        byDate.getOrPut(date) { mutableListOf() }.add(event)
    }

    val dates = ArrayList<LocalDate>()
    // The first heading is always today so the list starts with today's date even when today has
    // no more classes; later dates get their own separator.
    if (byDate.keys.firstOrNull() != today) dates += today
    dates += byDate.keys

    return dates.map { date ->
        val label = when {
            date == today -> date.format(fullDate)
            date == today.plusDays(1) -> "Tomorrow"
            else -> date.format(shortDate)
        }
        AgendaDayGroup(
            date = date,
            label = label,
            isToday = date == today,
            events = byDate[date].orEmpty(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgendaList(
    upcoming: List<CalendarEvent>,
    mappedCourseKeys: Set<String>,
    hasLink: Boolean,
    now: Long,
    zone: ZoneId,
    onEventClick: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = remember { Locale.getDefault() }
    val today = localDateTime(now, zone).toLocalDate()
    val todayLabel = remember(today, locale) {
        today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
    }

    if (upcoming.isEmpty()) {
        Column(modifier) {
            AgendaDateHeader(label = todayLabel, isToday = true)
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (!hasLink) {
                        "Subscribe to an iCalendar feed in the calendar settings."
                    } else {
                        "No upcoming classes."
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        return
    }

    val groups = remember(upcoming, today, zone, locale) {
        buildAgendaGroups(upcoming, today, zone, locale)
    }
    val listState = rememberLazyListState()
    val firstEventId = upcoming.firstOrNull()?.let { "${it.uid}:${it.startAt}" }
    LaunchedEffect(firstEventId) {
        if (firstEventId != null) listState.animateScrollToItem(0)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(bottom = 4.dp),
    ) {
        groups.forEach { group ->
            stickyHeader(key = "date:${group.date}") {
                AgendaDateHeader(label = group.label, isToday = group.isToday)
            }
            items(group.events, key = { "event:${it.uid}:${it.startAt}" }) { event ->
                AgendaEventRow(
                    event = event,
                    mapped = event.courseKey in mappedCourseKeys,
                    dotColor = dotColorFor(event.courseKey),
                    onClick = { onEventClick(event) },
                )
            }
        }
    }
}

@Composable
private fun AgendaDateHeader(label: String, isToday: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (isToday) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp, top = 5.dp, bottom = 3.dp),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
                Text(
                    text = label,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    thickness = 0.7.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun AgendaEventRow(
    event: CalendarEvent,
    mapped: Boolean,
    dotColor: Color,
    onClick: () -> Unit,
) {
    val locale = Locale.getDefault()
    val zone = remember { ZoneId.systemDefault() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .height(AgendaRowHeight)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (mapped) dotColor else MaterialTheme.colorScheme.outline, CircleShape),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.summary,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (event.allDay) "All day" else formatTime(event.startAt, zone, locale),
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event.location.isNotBlank()) {
                    LocationGlyph(
                        modifier = Modifier.size(8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = event.location,
                        fontSize = 9.5.sp,
                        lineHeight = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (!event.allDay) {
                    Text(
                        text = formatEventDuration(event.startAt, event.endAt),
                        fontSize = 9.3.sp,
                        lineHeight = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Task rows                                                           */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(
    task: TodoTask,
    now: Long,
    zone: ZoneId,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = todoStatus(task.dueAt, now, zone)
    val shape = RoundedCornerShape(10.dp)
    val background = when (status) {
        TodoStatus.OVERDUE -> MaterialTheme.colorScheme.errorContainer
        TodoStatus.TODAY -> TodayGlow
        TodoStatus.UPCOMING -> Color.Transparent
    }
    val border = when (status) {
        TodoStatus.TODAY -> BorderStroke(1.dp, TodayGlowBorder)
        TodoStatus.OVERDUE -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
        TodoStatus.UPCOMING -> null
    }
    val titleColor = when (status) {
        TodoStatus.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer
        TodoStatus.TODAY -> TodayInk
        TodoStatus.UPCOMING -> MaterialTheme.colorScheme.onSurface
    }
    val metaColor = when (status) {
        TodoStatus.OVERDUE -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
        TodoStatus.TODAY -> TodayInk.copy(alpha = 0.75f)
        TodoStatus.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TodoRowHeight)
                .clip(shape)
                .background(background)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .combinedClickable(onClick = onEdit, onLongClick = { menuOpen = true })
                .padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .border(
                            width = 1.4.dp,
                            color = when (status) {
                                TodoStatus.OVERDUE -> MaterialTheme.colorScheme.error
                                TodoStatus.TODAY -> TodayGlowBorder
                                TodoStatus.UPCOMING -> MaterialTheme.colorScheme.primary
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (task.done) {
                        CheckGlyph(Modifier.size(12.dp), MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.width(3.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontSize = 11.8.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    lineHeight = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = todoDueText(task, now, zone),
                    fontSize = 9.3.sp,
                    color = metaColor,
                    lineHeight = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = {
                    menuOpen = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

private fun todoDueText(task: TodoTask, now: Long, zone: ZoneId): String {
    val locale = Locale.getDefault()
    val due = localDateTime(task.dueAt, zone)
    val time = due.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    val today = localDateTime(now, zone).toLocalDate()
    return when {
        task.done -> "Completed"
        task.dueAt < now ->
            "Overdue · ${due.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))} · $time"
        due.toLocalDate() == today -> "Today · $time"
        due.toLocalDate() == today.plusDays(1) -> "Tomorrow · $time"
        else -> "${due.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))} · $time"
    }
}

/* ------------------------------------------------------------------ */
/* Add / edit task                                                     */
/* ------------------------------------------------------------------ */

@Composable
private fun TaskEditorDialog(
    initial: TodoTask?,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var dueAt by remember { mutableStateOf(initial?.dueAt ?: defaultDueAt()) }
    var error by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(24.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 500.dp).fillMaxWidth(0.92f),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
            shadowElevation = 14.dp,
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    text = if (initial == null) "New task" else "Edit task",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        error = null
                    },
                    label = { Text("Task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            pickDate(context, dueAt) { picked -> dueAt = picked }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Text(
                            text = JavaDateFormat.getDateInstance(JavaDateFormat.MEDIUM)
                                .format(Date(dueAt)),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            pickTime(context, dueAt) { picked -> dueAt = picked }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(
                            text = JavaDateFormat.getTimeInstance(JavaDateFormat.SHORT)
                                .format(Date(dueAt)),
                            fontSize = 12.sp,
                        )
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error.orEmpty(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                error = "Give the task a name."
                            } else {
                                onSave(title, dueAt)
                            }
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 22.dp),
                    ) {
                        Text(
                            text = if (initial == null) "Add" else "Save",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private fun defaultDueAt(): Long {
    val now = ZonedDateTime.now()
    var due = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
    if (!due.isAfter(now)) due = due.plusHours(1)
    return due.toInstant().toEpochMilli()
}

private fun pickDate(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val zoned = localDateTime(current, zone)
    DatePickerDialog(
        context,
        { _, year, month, day ->
            val picked = ZonedDateTime.of(
                LocalDate.of(year, month + 1, day),
                zoned.toLocalTime(),
                zone,
            )
            onPicked(picked.toInstant().toEpochMilli())
        },
        zoned.year,
        zoned.monthValue - 1,
        zoned.dayOfMonth,
    ).show()
}

private fun pickTime(context: Context, current: Long, onPicked: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val zoned = localDateTime(current, zone)
    TimePickerDialog(
        context,
        { _, hour, minute ->
            val picked = ZonedDateTime.of(
                zoned.toLocalDate(),
                LocalTime.of(hour, minute),
                zone,
            )
            onPicked(picked.toInstant().toEpochMilli())
        },
        zoned.hour,
        zoned.minute,
        AndroidDateFormat.is24HourFormat(context),
    ).show()
}

/* ------------------------------------------------------------------ */
/* All tasks                                                           */
/* ------------------------------------------------------------------ */

@Composable
private fun AllTasksDialog(
    todos: List<TodoTask>,
    now: Long,
    zone: ZoneId,
    onToggle: (String) -> Unit,
    onEdit: (TodoTask) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val active = activeTodos(todos)
    val overdue = active.filter { todoStatus(it.dueAt, now, zone) == TodoStatus.OVERDUE }
    val today = active.filter { todoStatus(it.dueAt, now, zone) == TodoStatus.TODAY }
    val upcoming = active.filter { todoStatus(it.dueAt, now, zone) == TodoStatus.UPCOMING }
    val completed = completedTodos(todos)
    val shape = RoundedCornerShape(24.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 760.dp),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
            shadowElevation = 14.dp,
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "All tasks",
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${active.size} open · ${completed.size} completed",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onDismiss)
                            .semantics { contentDescription = "Close" },
                        contentAlignment = Alignment.Center,
                    ) {
                        CloseGlyph(Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (todos.isEmpty()) {
                        Text(
                            text = "No tasks yet.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 18.dp),
                        )
                    }
                    TaskSection("Overdue", overdue, now, zone, onToggle, onEdit, onDelete)
                    TaskSection("Today", today, now, zone, onToggle, onEdit, onDelete)
                    TaskSection("Upcoming", upcoming, now, zone, onToggle, onEdit, onDelete)
                    TaskSection(
                        label = "Completed",
                        tasks = completed,
                        now = now,
                        zone = zone,
                        onToggle = onToggle,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskSection(
    label: String,
    tasks: List<TodoTask>,
    now: Long,
    zone: ZoneId,
    onToggle: (String) -> Unit,
    onEdit: (TodoTask) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (tasks.isEmpty()) return
    Text(
        text = label.uppercase(Locale.getDefault()),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 5.dp),
    )
    tasks.forEach { task ->
        AllTasksRow(task, now, zone, onToggle, onEdit, onDelete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllTasksRow(
    task: TodoTask,
    now: Long,
    zone: ZoneId,
    onToggle: (String) -> Unit,
    onEdit: (TodoTask) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val completed = task.done
    val status = todoStatus(task.dueAt, now, zone)
    val meta = if (completed) {
        val at = task.completedAt?.let { localDateTime(it, zone) }
            ?.format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.getDefault()))
            .orEmpty()
        "Completed${if (at.isNotBlank()) " · $at" else ""}"
    } else {
        todoDueText(task, now, zone)
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(9.dp))
                .combinedClickable(
                    onClick = { onEdit(task) },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable { onToggle(task.id) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .border(
                            1.4.dp,
                            if (completed) MaterialTheme.colorScheme.primary
                            else if (status == TodoStatus.OVERDUE) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (completed) CheckGlyph(Modifier.size(14.dp), MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontSize = 12.2.sp,
                    color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (completed) TextDecoration.LineThrough else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    fontSize = 10.sp,
                    color = if (status == TodoStatus.OVERDUE && !completed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = {
                    menuOpen = false
                    onEdit(task)
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    menuOpen = false
                    onDelete(task.id)
                },
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Calendar & task settings                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun ScheduleSettingsDialog(
    state: ScheduleUiState,
    folders: List<CourseFolderOption>,
    hint: String?,
    onSaveUrl: (String) -> Unit,
    onSync: () -> Unit,
    onMap: (String, String?) -> Unit,
    onAutoMatch: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(state.icalUrl) }
    var urlError by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(24.dp)
    val selectedByFolder = state.folderToCourse
    val feedKeys = state.courses.map { it.key }.toSet()
    val mappedKeys = selectedByFolder.values.toSet()
    val staleMappings = if (state.courses.isEmpty()) {
        emptyMap()
    } else {
        state.folderToCourse.filterValues { it !in feedKeys }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 780.dp),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
            shadowElevation = 14.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
            ) {
                Text(
                    text = "Calendar & tasks",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Subscribe to an iCalendar feed, then map each library folder to the " +
                        "course it belongs to. Tapping a class in the calendar filters the library " +
                        "to that course.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (hint != null) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = hint,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        urlError = null
                    },
                    label = { Text("iCalendar link (https)") },
                    placeholder = { Text("https://…/calendar.ics") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (urlError != null) {
                    Text(
                        text = urlError.orEmpty(),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            val trimmed = url.trim()
                            if (trimmed.isNotBlank() && !trimmed.startsWith("https://", true)) {
                                urlError = "Use an https:// link."
                            } else {
                                onSaveUrl(trimmed)
                            }
                        },
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 18.dp),
                    ) {
                        Text(
                            text = if (url.isBlank()) "Save" else "Save & sync",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = onSync,
                        enabled = !state.syncing && state.icalUrl.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        if (state.syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Text("Sync now", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        state.syncing -> "Syncing calendar…"
                        state.syncError != null -> state.syncError
                        state.lastSyncedAt > 0L ->
                            "Synced ${formatDateTime(state.lastSyncedAt)} · " +
                                "${state.events.size} events · ${state.courses.size} courses"
                        state.icalUrl.isBlank() -> "No calendar link yet."
                        else -> "Not synced yet."
                    },
                    fontSize = 10.5.sp,
                    color = if (state.syncError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsSectionTitle("Folder → course", Modifier.weight(1f))
                    TextButton(
                        onClick = onAutoMatch,
                        enabled = folders.isNotEmpty() && state.courses.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Auto-match", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    text = "A class is selected in the library through its folder. Unmapped folders " +
                        "keep their normal library behaviour.",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (staleMappings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = "${staleMappings.size} folder mapping(s) point to a course " +
                                "that is no longer in the feed. Re-select them or use Auto-match.",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (folders.isEmpty()) {
                    Text(
                        text = "Import a course folder first.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    folders.forEach { folder ->
                        FolderMappingRow(
                            folder = folder,
                            courses = state.courses,
                            selectedKey = selectedByFolder[folder.folder],
                            onMap = { key -> onMap(folder.folder, key) },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                SettingsSectionTitle("Courses in the feed")
                if (state.courses.isEmpty()) {
                    Text(
                        text = "Sync the link to parse course names.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                } else {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        state.courses.forEach { course ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(dotColorFor(course.key), CircleShape),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = course.title,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (course.codes.isNotEmpty()) {
                                        Text(
                                            text = course.codes.joinToString(" · ") +
                                                " · ${course.eventCount} dates",
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                val mapped = course.key in mappedKeys
                                Text(
                                    text = if (mapped) "Mapped" else "No folder",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (mapped) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FolderMappingRow(
    folder: CourseFolderOption,
    courses: List<CalendarCourse>,
    selectedKey: String?,
    onMap: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = courses.firstOrNull { it.key == selectedKey }
    val label = when {
        selected != null -> selected.title
        selectedKey != null -> "Unknown course"
        else -> "Not mapped"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = folder.course.ifBlank { folder.folder },
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.folder} · ${folder.count} files",
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.widthIn(max = 210.dp).height(34.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Not mapped") },
                    onClick = {
                        expanded = false
                        onMap(null)
                    },
                )
                courses.forEach { course ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = course.title,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onMap(course.key)
                        },
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Small helpers and glyphs                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun dotColorFor(courseKey: String): Color {
    if (courseKey.isBlank()) return MaterialTheme.colorScheme.outline
    val scheme = MaterialTheme.colorScheme
    val palette = remember(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error) {
        listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error)
    }
    return palette[Math.floorMod(courseKey.hashCode(), palette.size)]
}

/** Loose comparison for course keys, used only when an exact mapping lookup fails. */
private fun normalizeMappingKey(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/**
 * Last-resort match between a library folder name and a calendar course title, used when the
 * user's mapping is missing or stale. Requires either a full string match or all meaningful
 * folder-name words to occur in the course title, so unrelated courses are not picked.
 */
private fun folderMatchesCourse(folderCourse: String, eventTitle: String): Boolean {
    val folder = normalizeMappingKey(folderCourse)
    val title = normalizeMappingKey(eventTitle)
    if (folder.isBlank() || title.isBlank()) return false
    if (folder == title) return true
    if (folder.length >= 4 && title.contains(folder)) return true
    val folderWords = folder.split(' ').filter { it.length >= 3 }
    if (folderWords.isEmpty()) return false
    val titleWords = title.split(' ').toSet()
    return folderWords.all { it in titleWords }
}

private fun formatTime(epochMillis: Long, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(localDateTime(epochMillis, zone))

private fun formatDateTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
        .format(localDateTime(epochMillis, ZoneId.systemDefault()))

@Composable
private fun LocationGlyph(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.95f)
            lineTo(w * 0.16f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.16f)
            close()
        }
        drawPath(path = path, color = tint)
    }
}
