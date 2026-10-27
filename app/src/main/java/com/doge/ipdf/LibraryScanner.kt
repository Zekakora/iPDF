package com.doge.ipdf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class LibraryScanException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Recursively indexes a SAF tree:
 *
 *   root/                      (one root per import)
 *     Course 26ws/             (one course)
 *       Lecture/               (the only classification level)
 *         Chapter 1/...pdf     (everything below inherits Lecture)
 *       Exercise/
 *       Exam/
 *       Others/
 *
 * A second-level folder whose name is not exactly Lecture/Exercise/Exam/Others is grouped under
 * Others, keeping its real folder name in the tree.
 */
class LibraryScanner(private val resolver: ContentResolver) {

    data class Progress(val courses: Int, val files: Int)

    data class Result(
        val rootName: String,
        val entries: List<LibraryEntry>,
        val skippedRootFiles: Int,
        val truncated: Boolean,
    )

    private data class Doc(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
    ) {
        val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val isPdf: Boolean get() = mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)
        val isVisible: Boolean get() = !name.startsWith(".") && name !in SKIP_NAMES
    }

    suspend fun scan(
        treeUri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw LibraryScanException("This folder cannot be read", it) }
        val rootName = readDisplayName(treeUri, rootId)
            ?: treeUri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.let { Uri.decode(it) }
                ?.takeIf { it.isNotBlank() }
            ?: "Library"

        val rootChildren = readChildren(treeUri, rootId)
        val entries = ArrayList<LibraryEntry>()
        var skippedRootFiles = 0
        var courseCount = 0
        var truncated = false

        for (child in rootChildren) {
            currentCoroutineContext().ensureActive()
            if (!child.isDirectory) {
                if (child.isPdf) skippedRootFiles++
                continue
            }
            if (!child.isVisible) continue

            courseCount++
            val parsed = SemesterParser.parse(child.name)
            val courseFolder = child.name
            val courseChildren = readChildren(treeUri, child.id)

            for (sub in courseChildren) {
                currentCoroutineContext().ensureActive()
                if (!sub.isDirectory) {
                    if (sub.isPdf) {
                        entries += entry(
                            treeUri = treeUri,
                            doc = sub,
                            parsed = parsed,
                            courseFolder = courseFolder,
                            type = DocType.OTHERS,
                            relativeFolder = "",
                        )
                    }
                    continue
                }
                if (!sub.isVisible) continue

                val type = DocType.fromFolderName(sub.name) ?: DocType.OTHERS
                walk(
                    treeUri = treeUri,
                    dir = sub,
                    parsed = parsed,
                    courseFolder = courseFolder,
                    type = type,
                    path = sub.name,
                    depth = 1,
                    entries = entries,
                )
                if (entries.size >= MAX_ENTRIES) {
                    truncated = true
                    break
                }
            }

            onProgress(Progress(courseCount, entries.size))
            if (entries.size >= MAX_ENTRIES) {
                truncated = true
                break
            }
        }

        Result(rootName, entries, skippedRootFiles, truncated)
    }

    private suspend fun walk(
        treeUri: Uri,
        dir: Doc,
        parsed: ParsedCourse,
        courseFolder: String,
        type: DocType,
        path: String,
        depth: Int,
        entries: MutableList<LibraryEntry>,
    ) {
        currentCoroutineContext().ensureActive()
        if (depth > MAX_DEPTH || entries.size >= MAX_ENTRIES) return

        for (child in readChildren(treeUri, dir.id)) {
            currentCoroutineContext().ensureActive()
            if (entries.size >= MAX_ENTRIES) return
            if (child.isDirectory) {
                if (!child.isVisible) continue
                walk(
                    treeUri = treeUri,
                    dir = child,
                    parsed = parsed,
                    courseFolder = courseFolder,
                    type = type,
                    path = "$path/${child.name}",
                    depth = depth + 1,
                    entries = entries,
                )
            } else if (child.isPdf) {
                entries += entry(
                    treeUri = treeUri,
                    doc = child,
                    parsed = parsed,
                    courseFolder = courseFolder,
                    type = type,
                    relativeFolder = path,
                )
            }
        }
    }

    private fun entry(
        treeUri: Uri,
        doc: Doc,
        parsed: ParsedCourse,
        courseFolder: String,
        type: DocType,
        relativeFolder: String,
    ): LibraryEntry = LibraryEntry(
        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, doc.id).toString(),
        name = doc.name,
        course = parsed.base,
        courseFolder = courseFolder,
        semester = parsed.semester,
        type = type,
        relativeFolder = relativeFolder,
        size = doc.size,
        modified = doc.modified,
    )

    private fun readChildren(treeUri: Uri, parentId: String): List<Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<Doc>()
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0) return@use

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        0L
                    }
                    result += Doc(id, name, mime, size, modified)
                }
            }
        } catch (e: SecurityException) {
            throw LibraryScanException("Folder access was lost", e)
        } catch (e: Exception) {
            throw LibraryScanException("Could not read this folder", e)
        }
        return result
    }

    private fun readDisplayName(treeUri: Uri, documentId: String): String? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return runCatching {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_ENTRIES = 5000
        val SKIP_NAMES = setOf(
            "Android",
            "LOST.DIR",
            "\$RECYCLE.BIN",
            "System Volume Information",
            ".thumbnails",
        )
    }
}
