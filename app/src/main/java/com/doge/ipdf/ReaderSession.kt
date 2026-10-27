package com.doge.ipdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class SaveState(val kind: Kind, val at: Long = 0L, val message: String = "") {
    enum class Kind { LOADING, DRAFT, SAVING, SAVED, FAILED }
}

/**
 * One open document: a private pristine copy for rendering, the stroke journal, undo/redo and the
 * background writer that turns strokes into PDF /Ink annotations on the user's original file.
 */
class ReaderSession private constructor(
    private val appContext: Context,
    val uri: Uri
) {
    companion object {
        private const val PREFS = "reader_state"
        private const val EXPORT_DEBOUNCE_MS = 2200L

        private val cache = HashMap<String, ReaderSession>()

        fun of(context: Context, uri: Uri): ReaderSession = synchronized(cache) {
            cache.getOrPut(uri.toString()) { ReaderSession(context.applicationContext, uri) }
        }

        fun forget(uri: Uri) {
            synchronized(cache) { cache.remove(uri.toString()) }
        }

        fun flushAll() {
            val sessions = synchronized(cache) { cache.values.toList() }
            sessions.forEach { it.flush() }
        }

        private fun sha1(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }

    private val docId: String = sha1(uri.toString())
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val docDir = File(File(appContext.filesDir, "documents"), docId)
    val sourceFile = File(docDir, "source.pdf")
    private val journalFile = File(docDir, "strokes.json")
    private val journalBackup = File(docDir, "strokes.bak")
    private val exportTemp = File(docDir, "export.pdf")
    private val pageSizesFile = File(docDir, "page-sizes.json")

    /** Page sizes measured once per source file; avoids re-measuring every page on the UI thread. */
    var pageSizes: List<PageSize> = emptyList()
        private set

    val strokes = mutableStateListOf<Stroke>()
    var saveState by mutableStateOf(SaveState(SaveState.Kind.LOADING))
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var ready by mutableStateOf(false)
    var displayName by mutableStateOf(uri.lastPathSegment?.substringAfterLast('/').orEmpty())
    var pageCount by mutableStateOf(0)
    var inkRevision by mutableStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exportMutex = Mutex()
    private var journalJob: Job? = null
    private var exportJob: Job? = null
    private var viewportJob: Job? = null
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var eraseBefore: List<Stroke>? = null
    @Volatile
    private var dirty = false
    @Volatile
    private var editGeneration = 0
    private var preparing = false
    private var prepared = false

    /** True once foreign /Ink and /Highlight annotations were added to [strokes]. */
    @Volatile
    private var foreignAdopted = false

    private val stampKey get() = "stamp:$docId"
    private val exportedSizeKey get() = "exportedSize:$docId"
    private val savedAtKey get() = "savedAt:$docId"
    private val viewportKey get() = "viewport:$docId"
    private val annotationsScannedKey get() = "annotationsScanned:$docId"

    var viewport: Viewport? = null
        private set

    private sealed interface Edit {
        data class Add(val stroke: Stroke) : Edit
        data class Replace(val removed: List<Stroke>, val added: List<Stroke>) : Edit
        data class Move(val strokes: List<Stroke>, val dx: Float, val dy: Float) : Edit
        data class Transform(
            val strokes: List<Stroke>,
            val beforePoints: List<List<InkPoint>>,
            val beforeWidths: List<Float>,
            val afterPoints: List<List<InkPoint>>,
            val afterWidths: List<Float>
        ) : Edit
    }

    fun prepare(onReady: () -> Unit) {
        if (prepared) {
            onReady()
            return
        }
        if (preparing) return
        preparing = true
        scope.launch {
            try {
                docDir.mkdirs()
                val before = readStamp()
                val stored = prefs.getString(stampKey, null)
                val lastExport = prefs.getLong(exportedSizeKey, -1L)
                val externallyChanged = before != null && stored != null &&
                    before.text != stored && (lastExport <= 0L || before.size != lastExport)
                // Read the journal before doing anything that could touch it. It is the only copy of
                // strokes that have not reached the PDF yet, so it must never be discarded just
                // because the provider file changed underneath us.
                val journal = readJournal()
                var loaded = journal.strokes
                foreignAdopted = journal.foreignAdopted
                var adoptedNow = false
                var fromPdf = false
                var imported = false
                if (!sourceFile.exists() || externallyChanged) {
                    importSource()
                    imported = true
                }

                val name = queryDisplayName()
                val cachedSizes = loadPageSizes()
                val measuredSizes = cachedSizes ?: measurePageSizes()?.also(::savePageSizes)

                // Search the PDF for our recovered ink and for foreign ink only once per source
                // file. A clean document used to reload the entire PDF with PDFBox twice on every
                // open; the flag makes later opens skip that work entirely.
                val scanned = prefs.getBoolean(annotationsScannedKey, false)
                if (!scanned && (loaded.isEmpty() || !foreignAdopted)) {
                    val annotations = if (imported) {
                        PdfAnnotationReader.read(sourceFile)
                    } else {
                        openSourceStream()?.let(PdfAnnotationReader::read)
                            ?: PdfAnnotationReader.Result(emptyList(), emptyList(), false)
                    }
                    if (annotations.ok) {
                        if (loaded.isEmpty()) {
                            loaded = annotations.ours + annotations.foreign
                            fromPdf = annotations.ours.isNotEmpty()
                        } else if (!foreignAdopted) {
                            loaded = loaded + annotations.foreign
                        }
                        if (annotations.foreign.isNotEmpty()) {
                            foreignAdopted = true
                            adoptedNow = true
                        }
                        prefs.edit().putBoolean(annotationsScannedKey, true).apply()
                    }
                }

                val journalTime = journalFile.lastModified()
                val savedAt = prefs.getLong(savedAtKey, 0L)
                val recovered = !fromPdf && journal.strokes.isNotEmpty() &&
                    journalTime > savedAt + 1000L
                val needsExport = recovered || (externallyChanged && journal.strokes.isNotEmpty())
                val now = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    pageSizes = measuredSizes.orEmpty()
                    if (name != null) displayName = name
                    strokes.clear()
                    strokes.addAll(loaded)
                    prepared = true
                    ready = true
                    saveState = when {
                        loaded.isEmpty() -> SaveState(SaveState.Kind.SAVED, savedAt)
                        fromPdf -> SaveState(SaveState.Kind.SAVED, now)
                        adoptedNow -> SaveState(SaveState.Kind.SAVED, now)
                        needsExport -> SaveState(SaveState.Kind.DRAFT)
                        else -> SaveState(SaveState.Kind.SAVED, savedAt)
                    }
                    updateUndoState()
                    onReady()
                }
                if ((fromPdf && loaded.isNotEmpty()) || adoptedNow) {
                    prefs.edit().putLong(savedAtKey, now).commit()
                    writeJournal()
                }
                if (needsExport) {
                    // Restored journal strokes have not reached the PDF yet, so the next export must
                    // run even though no new edit happened in this session.
                    dirty = true
                    requestExport(1500L)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    prepared = true
                    ready = true
                    saveState = SaveState(SaveState.Kind.FAILED, message = t.message ?: "Could not open document")
                    onReady()
                }
            }
        }
    }

    fun addStroke(stroke: Stroke) {
        strokes.add(stroke)
        pushEdit(Edit.Add(stroke))
        onInkChanged()
    }

    fun beginErase() {
        eraseBefore = strokes.toList()
    }

    /**
     * @param pixel when true only the part of a stroke under the tip disappears (the stroke is
     * split into surviving runs); otherwise the whole stroke is removed.
     */
    fun eraseAt(page: Int, x: Float, y: Float, radius: Float, pixel: Boolean): Boolean {
        val hits = strokes.filter { stroke ->
            if (stroke.page != page) return@filter false
            val b = stroke.bounds()
            if (x < b.left - radius || x > b.right + radius || y < b.top - radius || y > b.bottom + radius) {
                return@filter false
            }
            stroke.distanceTo(x, y) <= radius + stroke.width / 2f
        }
        if (hits.isEmpty()) return false
        if (!pixel) {
            strokes.removeAll(hits.toSet())
            return true
        }
        var changed = false
        hits.forEach { stroke ->
            val runs = splitStroke(stroke, x, y, radius) ?: return@forEach
            strokes.remove(stroke)
            runs.forEach { run -> strokes.add(stroke.fork(run)) }
            changed = true
        }
        return changed
    }

    fun endErase() {
        val before = eraseBefore ?: return
        eraseBefore = null
        val after = strokes.toList()
        val beforeIds = before.mapTo(HashSet()) { it.id }
        val afterIds = after.mapTo(HashSet()) { it.id }
        val removed = before.filter { it.id !in afterIds }
        val added = after.filter { it.id !in beforeIds }
        if (removed.isEmpty() && added.isEmpty()) return
        pushEdit(Edit.Replace(removed, added))
        onInkChanged()
    }

    /** Keeps the points outside the eraser circle and returns the surviving contiguous runs. */
    private fun splitStroke(stroke: Stroke, x: Float, y: Float, radius: Float): List<List<InkPoint>>? {
        val threshold = radius + stroke.width / 2f
        val bounds = stroke.bounds()
        if (x < bounds.left - threshold || x > bounds.right + threshold ||
            y < bounds.top - threshold || y > bounds.bottom + threshold
        ) {
            return null
        }
        val runs = ArrayList<List<InkPoint>>()
        var current = ArrayList<InkPoint>()
        var removedAny = false
        for (point in stroke.points) {
            val distance = kotlin.math.hypot(point.x - x, point.y - y)
            if (distance <= threshold) {
                removedAny = true
                if (current.isNotEmpty()) {
                    runs.add(current)
                    current = ArrayList()
                }
            } else {
                current.add(point)
            }
        }
        if (!removedAny) return null
        if (current.isNotEmpty()) runs.add(current)
        return runs
    }

    /** Records a lasso drag that the view already applied, so it can be undone as one step. */
    fun recordMove(strokes: List<Stroke>, dx: Float, dy: Float) {
        if (strokes.isEmpty() || (kotlin.math.abs(dx) < 0.01f && kotlin.math.abs(dy) < 0.01f)) return
        pushEdit(Edit.Move(strokes.toList(), dx, dy))
        onInkChanged()
    }

    /** Records a lasso resize with exact before/after geometry so undo restores it precisely. */
    fun recordTransform(
        strokes: List<Stroke>,
        beforePoints: List<List<InkPoint>>,
        beforeWidths: List<Float>,
        afterPoints: List<List<InkPoint>>,
        afterWidths: List<Float>
    ) {
        if (strokes.isEmpty()) return
        pushEdit(Edit.Transform(strokes.toList(), beforePoints, beforeWidths, afterPoints, afterWidths))
        onInkChanged()
    }

    fun undo() {
        val edit = undoStack.removeLastOrNull() ?: return
        when (edit) {
            is Edit.Add -> strokes.removeAll { it.id == edit.stroke.id }
            is Edit.Replace -> {
                strokes.removeAll(edit.added.toSet())
                strokes.addAll(edit.removed)
            }
            is Edit.Move -> edit.strokes.forEach { it.translateBy(-edit.dx, -edit.dy) }
            is Edit.Transform -> edit.strokes.forEachIndexed { index, stroke ->
                stroke.replacePoints(edit.beforePoints[index])
                stroke.width = edit.beforeWidths[index]
            }
        }
        redoStack.addLast(edit)
        updateUndoState()
        onInkChanged()
    }

    fun redo() {
        val edit = redoStack.removeLastOrNull() ?: return
        when (edit) {
            is Edit.Add -> strokes.add(edit.stroke)
            is Edit.Replace -> {
                strokes.removeAll(edit.removed.toSet())
                strokes.addAll(edit.added)
            }
            is Edit.Move -> edit.strokes.forEach { it.translateBy(edit.dx, edit.dy) }
            is Edit.Transform -> edit.strokes.forEachIndexed { index, stroke ->
                stroke.replacePoints(edit.afterPoints[index])
                stroke.width = edit.afterWidths[index]
            }
        }
        undoStack.addLast(edit)
        updateUndoState()
        onInkChanged()
    }

    fun clearInk() {
        if (strokes.isEmpty()) return
        val removed = strokes.toList()
        strokes.clear()
        pushEdit(Edit.Replace(removed, emptyList()))
        onInkChanged()
    }

    fun onViewportChanged(viewport: Viewport) {
        this.viewport = viewport
        viewportJob?.cancel()
        viewportJob = scope.launch {
            delay(500)
            prefs.edit().putString(viewportKey, viewport.toJson()).apply()
        }
    }

    fun savedViewport(): Viewport? =
        viewport ?: Viewport.fromJson(prefs.getString(viewportKey, null))

    /** Persists the journal, viewport and PDF immediately; used when tabs/files/app lose focus. */
    fun flush() {
        scope.launch { flushAndWait() }
    }

    /** Persists the journal without waiting for a potentially expensive PDF rewrite. */
    fun flushJournal() {
        scope.launch { flushJournalAndWait() }
    }

    /** Flushes all pending edits and returns false when the PDF could not be saved. */
    suspend fun flushAndWait(): Boolean {
        viewport?.let { prefs.edit().putString(viewportKey, it.toJson()).commit() }
        journalJob?.cancel()
        withContext(Dispatchers.IO) {
            writeJournal()
            exportMutex.withLock { exportNow() }
        }
        return !dirty
    }

    fun requestExport(delayMs: Long = 0L) {
        exportJob?.cancel()
        exportJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            exportMutex.withLock { exportNow() }
        }
    }

    private fun onInkChanged() {
        dirty = true
        editGeneration++
        inkRevision++
        saveState = SaveState(SaveState.Kind.DRAFT)
        journalJob?.cancel()
        journalJob = scope.launch {
            delay(350)
            writeJournal()
        }
        requestExport(EXPORT_DEBOUNCE_MS)
    }

    private fun pushEdit(edit: Edit) {
        undoStack.addLast(edit)
        while (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        updateUndoState()
    }

    private fun updateUndoState() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private suspend fun exportNow() {
        if (!dirty || !sourceFile.exists()) return
        val generation = editGeneration
        val snapshot = withContext(Dispatchers.Main) { strokes.toList() }
        withContext(Dispatchers.Main) { saveState = SaveState(SaveState.Kind.SAVING) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                exportTemp.delete()
                val written = PdfInkExporter.export(
                    sourceFile,
                    snapshot,
                    exportTemp,
                    removeForeign = foreignAdopted,
                )
                if (!PdfInkExporter.isStructurallyValid(exportTemp)) {
                    throw IOException("Exported PDF could not be read back")
                }
                // Record the size before touching the provider file: if the process dies right
                // after the write, the next launch recognises the file as our own export instead
                // of treating it as an external change and dropping the journal.
                prefs.edit().putLong(exportedSizeKey, exportTemp.length()).commit()
                writeToOriginal(exportTemp)
                written
            }
        }
        result.onSuccess {
            val stamp = readStamp()
            prefs.edit()
                .putLong(savedAtKey, System.currentTimeMillis())
                .putLong(exportedSizeKey, stamp?.size ?: -1L)
                .putString(stampKey, stamp?.text ?: prefs.getString(stampKey, null))
                .apply()
            exportTemp.delete()
            val stillDirty = editGeneration != generation
            dirty = stillDirty
            withContext(Dispatchers.Main) {
                saveState = if (stillDirty) {
                    SaveState(SaveState.Kind.DRAFT)
                } else {
                    SaveState(SaveState.Kind.SAVED, System.currentTimeMillis())
                }
            }
            if (stillDirty) requestExport(EXPORT_DEBOUNCE_MS)
        }.onFailure { error ->
            // A failed write may have truncated the provider document; put the pristine copy back.
            runCatching {
                writeToOriginal(sourceFile)
                val stamp = readStamp()
                prefs.edit()
                    .putLong(exportedSizeKey, stamp?.size ?: -1L)
                    .putString(stampKey, stamp?.text)
                    .commit()
            }
            withContext(Dispatchers.Main) {
                saveState = SaveState(
                    SaveState.Kind.FAILED,
                    message = error.message ?: "Could not save annotations"
                )
            }
        }
    }

    private suspend fun writeToOriginal(file: File) {
        // Preference 1: write through the content URI (in-app pickers hand out write access).
        val throughProvider = try {
            copyToProvider(file)
            true
        } catch (_: Exception) {
            false
        }
        if (throughProvider) return

        // Preference 2: a PDF opened from a file manager only grants reading. With all-files
        // access we can write the real file on shared storage instead.
        val target = directFile()
        if (target == null || !StorageAccess.isGranted(appContext)) {
            throw IOException(
                "This PDF only allows reading. Allow all-files access in iPDF so it can be saved.",
            )
        }
        copyDirectly(file, target)
    }

    private fun copyToProvider(file: File) {
        val resolver = appContext.contentResolver
        val output = runCatching { resolver.openOutputStream(uri, "rwt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: throw IOException("This document is read-only")
        output.use { out ->
            file.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    /** The real file behind a document URI, when it lives on shared storage. */
    private fun directFile(): File? {
        if (!StorageAccess.isGranted(appContext)) return null
        // File-manager providers (HyperOS' com.android.fileexplorer.myprovider and relatives)
        // expose the path in the URI itself, e.g. content://…/external_files/AAAMaster/x.pdf.
        uri.path?.let { path ->
            externalStorageRelative(path)?.let { relative ->
                return File(Environment.getExternalStorageDirectory(), relative)
            }
        }
        val path = when {
            uri.scheme == "file" -> uri.path
            uri.authority == "com.android.externalstorage.documents" -> {
                val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    ?: return null
                val parts = documentId.split(':', limit = 2)
                if (parts.size != 2) return null
                val root = when (parts[0]) {
                    "primary" -> Environment.getExternalStorageDirectory()
                    "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    else -> File("/storage/${parts[0]}")
                }
                File(root, parts[1]).absolutePath
            }
            uri.authority == "com.android.providers.downloads.documents" -> {
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                    ?.takeIf { it.startsWith("raw:") }
                    ?.removePrefix("raw:")
            }
            else -> null
        }
        return path?.let(::File)
    }

    /** Maps URI path hints like /external_files/AAAMaster/x.pdf to a path under the shared root. */
    private fun externalStorageRelative(path: String): String? {
        val markers = listOf("/external_files/", "/storage/emulated/0/", "/sdcard/")
        markers.forEach { marker ->
            val index = path.indexOf(marker)
            if (index >= 0) {
                val relative = path.substring(index + marker.length).trimStart('/')
                if (relative.isNotEmpty()) return relative
            }
        }
        return null
    }

    private fun copyDirectly(source: File, target: File) {
        if (!target.isFile) throw IOException("This PDF is no longer at ${target.path}")
        val parent = target.parentFile ?: throw IOException("Could not locate this PDF's folder")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not write to ${parent.path}")
        val temp = File(parent, ".${target.name}.ipdf.tmp")
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { out ->
                    input.copyTo(out, 64 * 1024)
                    out.flush()
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        // Keep the system file index in sync so file managers show the new size/time.
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                appContext,
                arrayOf(target.absolutePath),
                arrayOf("application/pdf"),
                null,
            )
        }
    }

    private suspend fun writeJournal() {
        if (!docDir.exists()) return
        val snapshot = withContext(Dispatchers.Main) { strokes.toList() }
        val root = JSONObject().put("version", 2).put("foreignAdopted", foreignAdopted)
        val array = JSONArray()
        snapshot.forEach { array.put(it.toJson()) }
        root.put("strokes", array)
        runCatching {
            docDir.mkdirs()
            val temp = File(docDir, "strokes.tmp")
            temp.writeText(root.toString())
            // Keep the previous journal as a manual-recovery copy before replacing it.
            if (journalFile.exists() && journalFile.length() > 0L) {
                runCatching { journalFile.copyTo(journalBackup, overwrite = true) }
            }
            if (journalFile.exists() && !journalFile.delete()) {
                temp.copyTo(journalFile, overwrite = true)
                temp.delete()
            } else if (!temp.renameTo(journalFile)) {
                temp.copyTo(journalFile, overwrite = true)
                temp.delete()
            }
            prefs.edit().putLong("journalAt:$docId", System.currentTimeMillis()).apply()
        }
    }

    private data class Journal(val strokes: List<Stroke>, val foreignAdopted: Boolean)

    private fun readJournal(): Journal {
        parseJournal(journalFile)?.let { return it }
        // A half-written journal is the one case where the previous copy is better.
        return parseJournal(journalBackup) ?: Journal(emptyList(), false)
    }

    private fun parseJournal(file: File): Journal? {
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("strokes") ?: return@runCatching null
            val result = ArrayList<Stroke>(array.length())
            for (i in 0 until array.length()) {
                Stroke.fromJson(array.getJSONObject(i))?.let { result.add(it) }
            }
            Journal(result, root.optBoolean("foreignAdopted", false))
        }.getOrNull()
    }

    private suspend fun importSource() {
        val input = openSourceStream() ?: throw IOException("Could not read this document")
        val temp = File(docDir, "source.tmp")
        try {
            input.use { source ->
                temp.outputStream().use { target -> source.copyTo(target, 128 * 1024) }
            }
            if (sourceFile.exists() && !sourceFile.delete()) {
                temp.copyTo(sourceFile, overwrite = true)
                temp.delete()
            } else if (!temp.renameTo(sourceFile)) {
                temp.copyTo(sourceFile, overwrite = true)
                temp.delete()
            }
            val stamp = readStamp()
            prefs.edit()
                .putString(stampKey, stamp?.text)
                .putLong(exportedSizeKey, -1L)
                .putBoolean(annotationsScannedKey, false)
                .apply()
            pageSizesFile.delete()
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private data class Stamp(val size: Long, val modified: Long) {
        val text: String get() = "$size:$modified"
    }

    /** Opens the original PDF, falling back to the real file when the URI grant has expired. */
    private fun openSourceStream(): java.io.InputStream? =
        runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
            ?: directFile()?.takeIf { it.exists() }?.let { file ->
                runCatching { file.inputStream() }.getOrNull()
            }

    private fun readStamp(): Stamp? {
        val resolver = appContext.contentResolver
        val projection = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val fromDocuments = runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                Stamp(size, modified)
            }
        }.getOrNull()
        if (fromDocuments != null) return fromDocuments
        val fromSizeOnly = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else Stamp(cursor.getLong(index), 0L)
            }
        }.getOrNull()
        if (fromSizeOnly != null) return fromSizeOnly
        return directFile()?.takeIf { it.exists() }?.let { Stamp(it.length(), it.lastModified()) }
    }

    private fun queryDisplayName(): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
            }
    }.getOrNull() ?: directFile()?.name

    private fun loadPageSizes(): List<PageSize>? {
        if (!pageSizesFile.isFile) return null
        return runCatching {
            val json = JSONObject(pageSizesFile.readText())
            if (json.optInt("version", 0) != 1) return@runCatching null
            val pages = json.optJSONArray("pages") ?: return@runCatching null
            val out = ArrayList<PageSize>(pages.length())
            for (index in 0 until pages.length()) {
                val page = pages.optJSONObject(index) ?: continue
                val width = page.optDouble("w", 0.0).toFloat()
                val height = page.optDouble("h", 0.0).toFloat()
                if (width > 0f && height > 0f) out += PageSize(width, height)
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun measurePageSizes(): List<PageSize>? = runCatching {
        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { pdf ->
                val out = ArrayList<PageSize>(pdf.pageCount)
                for (index in 0 until pdf.pageCount) {
                    pdf.openPage(index).use { page ->
                        out += PageSize(page.width.toFloat(), page.height.toFloat())
                    }
                }
                out
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun savePageSizes(sizes: List<PageSize>) {
        runCatching {
            val pages = JSONArray()
            sizes.forEach { page ->
                pages.put(JSONObject().put("w", page.width.toDouble()).put("h", page.height.toDouble()))
            }
            val json = JSONObject().put("version", 1).put("pages", pages)
            pageSizesFile.writeText(json.toString())
        }
    }

    /** Called by the page view when it had to measure sizes because no cache was available. */
    fun storePageSizes(sizes: List<PageSize>) {
        pageSizes = sizes
        scope.launch { savePageSizes(sizes) }
    }

    /** Persists the journal and viewport, then schedules the PDF export without blocking a tab switch. */
    suspend fun flushJournalAndWait() {
        viewport?.let { prefs.edit().putString(viewportKey, it.toJson()).apply() }
        journalJob?.cancel()
        withContext(Dispatchers.IO) { writeJournal() }
        if (dirty) requestExport(EXPORT_DEBOUNCE_MS)
    }

    /**
     * Public read-only access to the pristine copy. The renderer never reads the user's file, so a
     * background save can rewrite the original without invalidating the visible bitmaps.
     */
    fun pristineFile(): File? = sourceFile.takeIf { it.exists() && it.length() > 0L }
}
