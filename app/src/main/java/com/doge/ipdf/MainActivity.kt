package com.doge.ipdf

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch
import java.net.URLDecoder

class MainActivity : ComponentActivity() {
    private lateinit var penSettings: PenSettings
    private val pendingOpen = mutableStateOf<String?>(null)
    private val storageGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        penSettings = PenSettings(applicationContext)
        storageGranted.value = StorageAccess.isGranted(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        handleViewIntent(intent)
        setContent { ReaderApp(penSettings, pendingOpen, storageGranted) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        persistPermission(this, uri)
        pendingOpen.value = uri.toString()
    }

    override fun onResume() {
        super.onResume()
        // All-files access is granted outside the app; picking it up here also retries any save
        // that previously failed because the PDF was opened read-only.
        val granted = StorageAccess.isGranted(this)
        if (granted && !storageGranted.value) ReaderSession.flushAll()
        storageGranted.value = granted
        val ready = MiuiTouchFilm.init(this) { function ->
            runOnUiThread { onTouchFilmFunction(function) }
        }
        if (ready) android.util.Log.i("PenButton", "Xiaomi touch-film SDK ready")
    }

    private fun onTouchFilmFunction(function: Int) {
        android.util.Log.i("PenButton", "touch-film function=$function")
        when (function) {
            // "Switch between brush and eraser" and "switch to previous brush" both drive the
            // single-press path. A quick second button press is interpreted by PenSettings as the
            // lasso shortcut.
            MiuiTouchFilm.switchBrushAndEraser,
            MiuiTouchFilm.switchToPreviousBrush -> penSettings.handleStylusButtonPress()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The official PenEngine touch-film SDK registers for the pen button and decodes its
        // gestures. It consumes the key and reports via onTouchFilmFunction when present.
        if (MiuiTouchFilm.onDispatchKeyEvent(event)) return true
        // Fallback for pens/ROMs that deliver the raw key directly. Focus Pen Pro is exposed by
        // this tablet as vendor/product 0x0022/0x5081 and can report F19, PAGE_UP/DOWN, or one of
        // the HID BUTTON_7..10 usages depending on the HyperOS input path in use.
        val isFocusPenRawButton = isFocusPenDevice(event) && when (event.keyCode) {
            KeyEvent.KEYCODE_F19,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_BUTTON_7,
            KeyEvent.KEYCODE_BUTTON_8,
            KeyEvent.KEYCODE_BUTTON_9,
            KeyEvent.KEYCODE_BUTTON_10 -> true
            else -> false
        }
        val isPenButton = isFocusPenRawButton ||
            event.keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
            event.keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
            event.keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY ||
            event.keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL
        if (isPenButton) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                android.util.Log.i("PenButton", "raw key=${event.keyCode} device=${event.deviceId}")
                penSettings.handleStylusButtonPress()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isFocusPenDevice(event: KeyEvent): Boolean {
        val device = android.view.InputDevice.getDevice(event.deviceId) ?: return false
        val name = device.name ?: ""
        return (device.vendorId == 0x0022 && device.productId == 0x5081) ||
            name.contains("Focus Pen", ignoreCase = true)
    }

    override fun onPause() {
        MiuiTouchFilm.destroy()
        super.onPause()
        ReaderSession.flushAll()
    }

    override fun onStop() {
        super.onStop()
        ReaderSession.flushAll()
    }
}

@Composable
private fun ReaderApp(
    penSettings: PenSettings,
    pendingOpen: MutableState<String?>,
    storageGranted: MutableState<Boolean>,
) {
    val context = LocalContext.current
    val library: LibraryViewModel = viewModel()
    val schedule: ScheduleViewModel = viewModel()
    var theme by remember { mutableStateOf(AppThemeStore.load(context)) }
    var themePickerOpen by remember { mutableStateOf(false) }
    var tabs by remember { mutableStateOf(RecentDocuments.load(context)) }
    var selectedUri by rememberSaveable { mutableStateOf<String?>(tabs.lastOrNull()) }
    var lastReaderUri by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedUri) {
        if (selectedUri != null) lastReaderUri = selectedUri
    }

    val allFilesGranted = storageGranted.value
    var storagePromptOpen by remember { mutableStateOf(!allFilesGranted) }
    LaunchedEffect(allFilesGranted) {
        if (allFilesGranted) storagePromptOpen = false
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { storageGranted.value = StorageAccess.isGranted(context) }

    val activity = context.findActivity()
    SideEffect {
        activity?.window?.statusBarColor = theme.scheme.surface.toArgb()
    }

    fun openDocument(uriString: String, name: String) {
        // Opening a document starts with no tool selected; the first stylus contact picks the
        // marker. This also applies when switching back and forth between documents.
        penSettings.clearTool()
        tabs = (tabs - uriString + uriString).takeLast(12)
        selectedUri = uriString
        RecentDocuments.save(context, tabs)
        library.recordHistory(uriString, name)
    }

    suspend fun flushSessions(uris: Iterable<String>): Boolean {
        for (uri in uris.distinct()) {
            ReaderSession.of(context, uri.toUri()).flushJournalAndWait()
        }
        return true
    }

    suspend fun closeSingleTab(closed: String) {
        if (!flushSessions(listOf(closed))) return
        val wasActive = selectedUri == closed
        val updated = tabs - closed
        tabs = updated
        if (wasActive) {
            selectedUri = updated.lastOrNull()
            if (selectedUri != null) penSettings.clearTool()
        }
        RecentDocuments.save(context, updated)
    }

    suspend fun closeTabGroup(action: TabCloseAction) {
        val active = selectedUri ?: return
        val activeIndex = tabs.indexOf(active)
        if (activeIndex < 0) return
        val closing = when (action) {
            TabCloseAction.ALL -> tabs
            TabCloseAction.OTHERS -> tabs.filterNot { it == active }
            TabCloseAction.LEFT -> tabs.take(activeIndex)
            TabCloseAction.RIGHT -> tabs.drop(activeIndex + 1)
        }
        if (closing.isEmpty() || !flushSessions(closing)) return
        val updated = tabs.filterNot { it in closing }
        tabs = updated
        selectedUri = if (active in updated) active else updated.lastOrNull()
        if (selectedUri != null) penSettings.clearTool()
        RecentDocuments.save(context, updated)
    }

    val externalUri = pendingOpen.value
    LaunchedEffect(externalUri) {
        if (externalUri != null) {
            openDocument(externalUri, documentLabel(externalUri))
            pendingOpen.value = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        persistPermission(context, uri)
        val value = uri.toString()
        openDocument(value, documentLabel(value))
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        persistPermission(context, uri)
        library.importTree(uri)
    }

    MaterialTheme(colorScheme = theme.scheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                val active = selectedUri
                AnimatedContent(
                    targetState = active != null,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (targetState) {
                            // Library -> reader: the page settles in a touch smaller and the
                            // library fades away behind it.
                            (
                                fadeIn(
                                    tween(
                                        durationMillis = 260,
                                        delayMillis = 40,
                                        easing = FastOutSlowInEasing,
                                    ),
                                ) +
                                    scaleIn(
                                        initialScale = 0.965f,
                                        animationSpec = tween(
                                            durationMillis = 300,
                                            delayMillis = 40,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    )
                                ).togetherWith(fadeOut(tween(durationMillis = 160)))
                        } else {
                            // Reader -> library: a quiet eased cross-fade.
                            fadeIn(
                                tween(durationMillis = 220, easing = FastOutSlowInEasing),
                            ) togetherWith
                                fadeOut(tween(durationMillis = 160))
                        }
                    },
                    contentAlignment = Alignment.Center,
                    label = "libraryReader",
                ) { reading ->
                    val document = selectedUri ?: lastReaderUri
                    if (!reading || document == null) {
                        LibraryScreen(
                            state = library.state,
                            schedule = schedule,
                            onOpenFolder = { treePicker.launch(null) },
                            onRescan = { library.rescan() },
                            onOpenEntry = { entry -> openDocument(entry.uri, entry.name) },
                            onOpenHistory = { entry -> openDocument(entry.uri, entry.name) },
                            onOpenFilePicker = { picker.launch(arrayOf("application/pdf")) },
                            onFiltersChange = library::updateFilters,
                            onScrollPositionChanged = library::updateScrollPosition,
                            onToggle = library::toggle,
                            onToggleAll = library::toggleAllCollapsed,
                            onReset = library::resetFilters,
                            onRemoveHistory = library::removeHistory,
                            onClearHistory = library::clearHistory,
                            onUndoClearHistory = library::undoClearHistory,
                            onThemeSettings = { themePickerOpen = true },
                            onSelectCourse = library::selectCourse,
                        )
                    } else {
                        ReaderScreen(
                            tabs = tabs,
                            uriString = document,
                            penSettings = penSettings,
                            onSelectTab = { target ->
                                penSettings.clearTool()
                                selectedUri = target
                            },
                            onCloseTab = ::closeSingleTab,
                            onCloseTabs = ::closeTabGroup,
                            onHome = { selectedUri = null },
                            onOpen = { picker.launch(arrayOf("application/pdf")) },
                            onStorageNeeded = { storagePromptOpen = true },
                        )
                    }
                }
            }
        }

        if (themePickerOpen) {
            ThemePickerDialog(
                selected = theme,
                onSelect = { selected ->
                    theme = selected
                    AppThemeStore.save(context, selected)
                },
                onDismiss = { themePickerOpen = false },
            )
        }

        if (storagePromptOpen && !allFilesGranted) {
            StorageAccessDialog(
                requestLabel = if (StorageAccess.usesSettingsPage) "Open settings" else "Allow",
                onGrant = {
                    if (StorageAccess.usesSettingsPage) {
                        runCatching { context.startActivity(StorageAccess.settingsIntent(context)) }
                    } else {
                        storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                onDismiss = { storagePromptOpen = false },
            )
        }
    }
}

@Composable
private fun StorageAccessDialog(
    requestLabel: String,
    onGrant: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
            shadowElevation = 14.dp,
        ) {
            Column(Modifier.widthIn(max = 460.dp).padding(24.dp)) {
                Text(
                    text = "Allow access to all files",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "iPDF saves your handwriting back into the PDF itself. A PDF opened from " +
                        "a file manager only grants read access, so writing it back needs the " +
                        "all-files permission.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Only the PDF you opened is ever rewritten; nothing is uploaded or scanned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Later", style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.size(6.dp))
                    Button(onClick = onGrant, shape = CircleShape) {
                        Text(
                            requestLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

private enum class TabCloseAction { ALL, OTHERS, LEFT, RIGHT }

@Composable
private fun ReaderScreen(
    tabs: List<String>,
    uriString: String,
    penSettings: PenSettings,
    onSelectTab: suspend (String) -> Unit,
    onCloseTab: suspend (String) -> Unit,
    onCloseTabs: suspend (TabCloseAction) -> Unit,
    onHome: suspend () -> Unit,
    onOpen: suspend () -> Unit,
    onStorageNeeded: () -> Unit,
) {
    val context = LocalContext.current
    val uri = uriString.toUri()
    val session = remember(uriString) { ReaderSession.of(context, uri) }
    val scope = rememberCoroutineScope()
    val topBarPx = with(LocalDensity.current) { 54.dp.roundToPx() }

    fun afterFlush(action: suspend () -> Unit) {
        scope.launch {
            session.flushJournalAndWait()
            action()
        }
    }

    LaunchedEffect(session) {
        if (!session.ready) session.prepare { }
    }

    val saveState = session.saveState
    LaunchedEffect(saveState) {
        if (saveState.kind == SaveState.Kind.FAILED && !StorageAccess.isGranted(context)) {
            onStorageNeeded()
        }
    }

    DisposableEffect(session) {
        onDispose { session.flushJournal() }
    }

    // Subscribe to ink/tool changes so the View repaints after undo/redo/erase from the dock.
    val pageView = remember { mutableStateOf<PdfPageView?>(null) }
    val inkRevision = session.inkRevision
    val tool = penSettings.tool
    val preset = penSettings.activePreset
    val eraserWidth = penSettings.eraserWidth
    val eraserPixel = penSettings.eraserMode == EraserMode.PIXEL
    val stabilizer = penSettings.stabilizer
    val zoomSpeed = penSettings.zoomSpeed
    val scrollSpeed = penSettings.scrollSpeed
    var editorAnchor by remember { mutableStateOf<Rect?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var readerSize by remember { mutableStateOf(IntSize.Zero) }
    var scrollbarState by remember { mutableStateOf(ScrollbarState()) }

    LaunchedEffect(inkRevision, tool, preset, eraserWidth, eraserPixel, stabilizer, zoomSpeed, scrollSpeed) {
        pageView.value?.let { view ->
            view.tool = tool
            view.preset = preset
            view.eraserWidthPt = eraserWidth
            view.eraserPixel = eraserPixel
            view.stabilizerStrength = stabilizer
            view.zoomSpeed = zoomSpeed
            view.scrollSpeed = scrollSpeed
            view.invalidate()
        }
    }

    DisposableEffect(Unit) {
        onDispose { pageView.value = null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { readerSize = it }
    ) {
        val pristine = session.pristineFile()
        if (session.ready && pristine != null) {
            key(uriString) {
                AndroidView(
                    factory = { ctx ->
                        PdfPageView(ctx).apply {
                            attachSession(session)
                            setTopInset(topBarPx)
                            this.zoomSpeed = zoomSpeed
                            this.scrollSpeed = scrollSpeed
                            onViewportChanged = { session.onViewportChanged(it) }
                            onScrollbarChanged = { scrollbarState = it }
                            onStylusButton = { penSettings.handleStylusButtonPress() }
                            onAutoMarker = {
                                penSettings.selectTool(PenTool.PEN)
                                penSettings.selectPreset(0)
                                penSettings.penPresets.firstOrNull()
                            }
                            pageView.value = this
                            runCatching {
                                openDocument(pristine, session.savedViewport(), session.pageSizes)
                            }
                        }
                    },
                    update = { view ->
                        view.setTopInset(topBarPx)
                        view.tool = tool
                        view.preset = preset
                        view.eraserWidthPt = eraserWidth
                        view.eraserPixel = eraserPixel
                        view.stabilizerStrength = stabilizer
                        view.zoomSpeed = zoomSpeed
                        view.scrollSpeed = scrollSpeed
                        view.invalidate()
                    },
                    onRelease = { view -> view.release() },
                    modifier = Modifier.fillMaxSize()
                )
            }
            ReaderTopBar(
                tabs = tabs,
                activeUri = uriString,
                session = session,
                onSelectTab = { target -> afterFlush { onSelectTab(target) } },
                onCloseTab = { closed -> scope.launch { onCloseTab(closed) } },
                onCloseTabs = { action -> scope.launch { onCloseTabs(action) } },
                onHome = { afterFlush { onHome() } },
                onOpen = { afterFlush { onOpen() } },
                onSettings = { settingsOpen = true },
                modifier = Modifier.align(Alignment.TopCenter)
            )
            PenDock(
                settings = penSettings,
                canUndo = session.canUndo,
                canRedo = session.canRedo,
                onUndo = { session.undo() },
                onRedo = { session.redo() },
                onEditBrush = { editorAnchor = it },
                forceCollapsed = scrollbarState.dragging,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
            )
            if (penSettings.floatingPresets && penSettings.hasPresets) {
                FloatingPresetBar(
                    settings = penSettings,
                    containerSize = readerSize,
                    onEditBrush = { editorAnchor = it }
                )
            }
            ReaderScrollChrome(
                state = scrollbarState,
                topInset = with(LocalDensity.current) { topBarPx.toDp() },
                onBeginDrag = { pageView.value?.beginScrollbarDrag() },
                onDrag = { fraction -> pageView.value?.dragScrollbarTo(fraction) },
                onEndDrag = { pageView.value?.endScrollbarDrag() },
                onJumpToPage = { page -> pageView.value?.jumpToPage(page) },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!session.ready) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text("Opening document…", modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("This document could not be opened", style = MaterialTheme.typography.titleMedium)
                Text(
                    session.saveState.message.ifBlank { "Check the file permission and try again." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(
                    onClick = { afterFlush { onHome() } },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Back to library") }
            }
        }

        editorAnchor?.let { anchor ->
            BrushEditorPopup(
                settings = penSettings,
                anchor = anchor,
                onDismiss = { editorAnchor = null }
            )
        }
        if (settingsOpen) {
            PenSettingsDialog(
                settings = penSettings,
                onDismiss = { settingsOpen = false }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderTopBar(
    tabs: List<String>,
    activeUri: String,
    session: ReaderSession,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseTabs: (TabCloseAction) -> Unit,
    onHome: () -> Unit,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var closeMenuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onHome) {
                Text("Library", style = MaterialTheme.typography.labelLarge)
            }
            Box {
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(40.dp)
                        .combinedClickable(
                            onClick = onOpen,
                            onLongClick = { closeMenuOpen = true },
                        )
                        .semantics { contentDescription = "Open document" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(
                    expanded = closeMenuOpen,
                    onDismissRequest = { closeMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Close all tabs") },
                        onClick = {
                            closeMenuOpen = false
                            onCloseTabs(TabCloseAction.ALL)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Close other tabs") },
                        onClick = {
                            closeMenuOpen = false
                            onCloseTabs(TabCloseAction.OTHERS)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Close tabs to the left") },
                        onClick = {
                            closeMenuOpen = false
                            onCloseTabs(TabCloseAction.LEFT)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Close tabs to the right") },
                        onClick = {
                            closeMenuOpen = false
                            onCloseTabs(TabCloseAction.RIGHT)
                        },
                    )
                }
            }
            Spacer(Modifier.size(6.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabs.forEach { tab ->
                    TabChip(
                        label = documentLabel(tab),
                        active = tab == activeUri,
                        onClick = { onSelectTab(tab) },
                        onClose = { onCloseTab(tab) }
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            SaveStatusChip(session)
            Spacer(Modifier.size(4.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onSettings)
                    .semantics { contentDescription = "Pen settings" },
                contentAlignment = Alignment.Center
            ) {
                GearGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val containerColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "tabContainer",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        modifier = Modifier.heightIn(min = 38.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.widthIn(max = 190.dp)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text("×", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun SaveStatusChip(session: ReaderSession) {
    val state = session.saveState
    val (label, color) = when (state.kind) {
        SaveState.Kind.LOADING -> "Opening…" to MaterialTheme.colorScheme.onSurfaceVariant
        SaveState.Kind.DRAFT -> "Not saved" to ComposeColor(0xFFB45309)
        SaveState.Kind.SAVING -> "Saving…" to MaterialTheme.colorScheme.primary
        SaveState.Kind.SAVED -> "Saved" to ComposeColor(0xFF15803D)
        SaveState.Kind.FAILED -> "Save failed" to MaterialTheme.colorScheme.error
    }
    Surface(
        onClick = { session.requestExport(0) },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.size(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

private fun persistPermission(context: android.content.Context, uri: android.net.Uri) {
    val resolver = context.contentResolver
    val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        resolver.takePersistableUriPermission(uri, readWrite)
    } catch (_: Exception) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some providers / file URIs are not persistable; the URI stays usable for this session.
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun documentLabel(uriString: String): String {
    val segment = uriString.toUri().lastPathSegment ?: return "PDF"
    val decoded = runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
    val name = decoded.substringAfterLast(':').substringAfterLast('/')
    return name.ifBlank { "PDF" }
}

private object RecentDocuments {
    private const val PREFS = "reader_state"
    private const val RECENTS = "recent_uris"

    fun load(context: android.content.Context): List<String> =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(RECENTS, "")!!.split('\n').filter { it.isNotBlank() }

    fun save(context: android.content.Context, uris: List<String>) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(RECENTS, uris.takeLast(12).joinToString("\n")).apply()
    }
}
