package com.doge.ipdf

import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes the in-memory strokes into the PDF as standard /Ink annotations.
 *
 * Every annotation also gets an /AP appearance stream (lines with the right colour) because desktop
 * readers such as Acrobat, Chrome and Edge use it directly; Android's
 * PdfRenderer ignores ink annotations entirely, so this app keeps drawing its own overlay.
 */
object PdfInkExporter {
    const val NAME_PREFIX = "RedmiPdfInk-"
    private const val AUTHOR = "Redmi PDF Reader"

    /** Exports [strokes] on top of [source] into [target]. Returns the number of ink annotations written. */
    fun export(
        source: File,
        strokes: List<Stroke>,
        target: File,
        removeForeign: Boolean = false,
    ): Int {
        var written = 0
        PDDocument.load(source).use { document ->
            removeManagedAnnotations(document, removeForeign)
            val perPage = strokes.groupBy { it.page }
            perPage.forEach { (pageIndex, pageStrokes) ->
                if (pageIndex < 0 || pageIndex >= document.numberOfPages) return@forEach
                val page = document.getPage(pageIndex)
                val annots = annotationsOf(page)
                pageStrokes.forEach { stroke ->
                    if (stroke.points.isEmpty()) return@forEach
                    annots.add(buildInkAnnotation(document, page, stroke))
                    written++
                }
                page.cosObject.setItem(COSName.ANNOTS, annots)
            }
            document.save(target)
        }
        return written
    }

    fun countOurAnnotations(file: File): Int {
        var count = 0
        PDDocument.load(file).use { document ->
            for (i in 0 until document.numberOfPages) {
                val annots = document.getPage(i).cosObject
                    .getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                for (j in 0 until annots.size()) {
                    val dict = annots.getObject(j) as? COSDictionary ?: continue
                    if (isOurInk(dict)) count++
                }
            }
        }
        return count
    }

    /**
     * Absolute ink width limits. The upper bound is generous because brush widths are
     * page-relative: a preset that looks right on A4 legitimately becomes wider on a large slide.
     */
    private fun inkWidthPt(width: Float): Float = width.coerceIn(0.3f, 120f)

    private fun annotationsOf(page: PDPage): COSArray {
        val existing = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray
        if (existing != null) return existing
        val created = COSArray()
        page.cosObject.setItem(COSName.ANNOTS, created)
        return created
    }

    /**
     * Removes this app's annotations. When [removeForeign] is true the /Ink and /Highlight
     * annotations that were adopted into the stroke list are removed as well; the adopted strokes
     * are then written back as this app's /Ink annotations. Annotations that could not be adopted
     * (rotated pages, no /InkList or /QuadPoints, other subtypes) are left untouched.
     */
    private fun removeManagedAnnotations(document: PDDocument, removeForeign: Boolean) {
        for (i in 0 until document.numberOfPages) {
            val page = document.getPage(i)
            val annots = page.cosObject
                .getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val foreignAdoptable = removeForeign && page.rotation % 360 == 0
            for (j in annots.size() - 1 downTo 0) {
                val dict = annots.getObject(j) as? COSDictionary ?: continue
                val remove = isOurInk(dict) ||
                    (foreignAdoptable &&
                        (ForeignInkReader.isAdoptableInk(dict) ||
                            ForeignInkReader.isAdoptableHighlight(dict)))
                if (remove) annots.remove(j)
            }
        }
    }

    /**
     * Recognises annotations written by this app. Older builds stored /NM as a name object
     * instead of a string, so the author entry is also checked; that keeps the PDF free of
     * orphaned strokes from those builds without touching annotations made by other apps.
     */
    fun isOurInk(dict: COSDictionary): Boolean {
        if ("Ink" != dict.getNameAsString(COSName.SUBTYPE)) return false
        if (AUTHOR == dict.getString(COSName.getPDFName("T"))) return true
        val nameKey = COSName.getPDFName("NM")
        val asString = dict.getString(nameKey)
        if (asString != null && asString.startsWith(NAME_PREFIX)) return true
        val asName = dict.getDictionaryObject(nameKey) as? COSName
        return asName?.name?.startsWith(NAME_PREFIX) == true
    }

    private fun buildInkAnnotation(document: PDDocument, page: PDPage, stroke: Stroke): COSDictionary {
        val pageHeight = page.mediaBox.height
        val pdfPoints = stroke.points.map { floatArrayOf(it.x, pageHeight - it.y) }

        var left = Float.MAX_VALUE
        var bottom = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        pdfPoints.forEach { p ->
            left = min(left, p[0])
            right = max(right, p[0])
            bottom = min(bottom, p[1])
            top = max(top, p[1])
        }
        val margin = max(stroke.width, 1f) / 2f + 2f
        left -= margin
        bottom -= margin
        right += margin
        top += margin

        val annot = COSDictionary()
        annot.setItem(COSName.TYPE, COSName.ANNOT)
        annot.setName(COSName.SUBTYPE, "Ink")
        annot.setInt(COSName.F, 4)
        annot.setString(COSName.getPDFName("NM"), NAME_PREFIX + stroke.id)
        annot.setString(COSName.getPDFName("T"), AUTHOR)
        if (stroke.highlighter) annot.setString(COSName.getPDFName("Subj"), "Highlight")

        val rect = COSArray()
        rect.add(COSFloat(left))
        rect.add(COSFloat(bottom))
        rect.add(COSFloat(right))
        rect.add(COSFloat(top))
        annot.setItem(COSName.RECT, rect)

        val inkList = COSArray()
        val pathArray = COSArray()
        pdfPoints.forEach { p ->
            pathArray.add(COSFloat(p[0]))
            pathArray.add(COSFloat(p[1]))
        }
        if (pdfPoints.size == 1) {
            // A tap is a dot: give readers a (nearly) zero-length segment to walk.
            pathArray.add(COSFloat(pdfPoints[0][0] + 0.02f))
            pathArray.add(COSFloat(pdfPoints[0][1] + 0.02f))
        }
        inkList.add(pathArray)
        annot.setItem(COSName.getPDFName("InkList"), inkList)

        val red = Color.red(stroke.color) / 255f
        val green = Color.green(stroke.color) / 255f
        val blue = Color.blue(stroke.color) / 255f
        val colorArray = COSArray()
        colorArray.add(COSFloat(red))
        colorArray.add(COSFloat(green))
        colorArray.add(COSFloat(blue))
        annot.setItem(COSName.C, colorArray)

        val alpha = (Color.alpha(stroke.color) / 255f).coerceIn(0f, 1f)
        annot.setFloat(COSName.getPDFName("CA"), alpha)

        val border = COSArray()
        border.add(COSFloat(0f))
        border.add(COSFloat(0f))
        border.add(COSFloat(stroke.width))
        annot.setItem(COSName.BORDER, border)

        val borderStyle = COSDictionary()
        borderStyle.setName(COSName.getPDFName("S"), "S")
        // Brush widths are page-relative, so a wide slide can legitimately need a wider ink line.
        borderStyle.setFloat(COSName.getPDFName("W"), inkWidthPt(stroke.width))
        annot.setItem(COSName.getPDFName("BS"), borderStyle)

        annot.setItem(COSName.AP, buildAppearance(document, stroke, pdfPoints, left, bottom, right, top))
        return annot
    }

    private fun buildAppearance(
        document: PDDocument,
        stroke: Stroke,
        points: List<FloatArray>,
        left: Float,
        bottom: Float,
        right: Float,
        top: Float
    ): COSDictionary {
        val width = max(right - left, 0.5f)
        val height = max(top - bottom, 0.5f)

        val appearance = PDAppearanceStream(document)
        appearance.setBBox(PDRectangle(0f, 0f, width, height))
        val resources = PDResources()
        val state = PDExtendedGraphicsState()
        // /CA on the annotation is the portable opacity field. Keep the appearance stream opaque
        // so compliant readers do not multiply the same alpha twice while compositing /AP.
        state.setStrokingAlphaConstant(1f)
        state.setNonStrokingAlphaConstant(1f)
        resources.put(COSName.getPDFName("GS0"), state)
        appearance.setResources(resources)

        val red = Color.red(stroke.color) / 255f
        val green = Color.green(stroke.color) / 255f
        val blue = Color.blue(stroke.color) / 255f
        val lineWidth = inkWidthPt(stroke.width)

        PDPageContentStream(document, appearance).use { content ->
            content.saveGraphicsState()
            content.appendRawCommands("/GS0 gs\n")
            content.setStrokingColor(red, green, blue)
            content.setNonStrokingColor(red, green, blue)
            content.setLineWidth(lineWidth)
            content.setLineCapStyle(1)
            content.setLineJoinStyle(1)

            if (points.size == 1) {
                val x = points[0][0] - left
                val y = points[0][1] - bottom
                content.addRect(x - lineWidth / 2f, y - lineWidth / 2f, lineWidth, lineWidth)
                content.fill()
            } else {
                points.forEachIndexed { index, p ->
                    val x = p[0] - left
                    val y = p[1] - bottom
                    if (index == 0) content.moveTo(x, y) else content.lineTo(x, y)
                }
                content.stroke()
            }
            content.restoreGraphicsState()
        }

        val ap = COSDictionary()
        ap.setItem(COSName.N, appearance)
        return ap
    }

    /** Cheap structural sanity check so a truncated PDF is never copied over the user's file. */
    fun isStructurallyValid(file: File): Boolean = try {
        PDDocument.load(file).use { it.numberOfPages > 0 }
    } catch (_: Throwable) {
        false
    }
}

/**
 * Reads back the /Ink annotations this app previously wrote, so a document whose local journal was
 * lost (app data cleared, file edited elsewhere) still shows its handwriting and can be edited.
 */
object PdfInkImporter {
    fun read(file: File): List<Stroke> = runCatching {
        file.inputStream().use { read(it) }
    }.getOrDefault(emptyList())

    fun read(stream: java.io.InputStream): List<Stroke> = stream.use { input ->
        runCatching {
            val strokes = ArrayList<Stroke>()
            PDDocument.load(input).use { document -> collect(document, strokes) }
            strokes
        }.getOrDefault(emptyList())
    }

    private fun collect(document: PDDocument, strokes: MutableList<Stroke>) {
        for (index in 0 until document.numberOfPages) {
            val page = document.getPage(index)
            val pageHeight = page.mediaBox.height
            val annots = page.cosObject
                .getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            for (i in 0 until annots.size()) {
                val dict = annots.getObject(i) as? COSDictionary ?: continue
                if (!PdfInkExporter.isOurInk(dict)) continue
                toStroke(dict, index, pageHeight, page.mediaBox.width)?.let { strokes.add(it) }
            }
        }
    }

    internal fun toStroke(
        dict: COSDictionary,
        page: Int,
        pageHeight: Float,
        pageWidth: Float = pageHeight,
    ): Stroke? {
        val pageLongEdgePt = max(pageWidth, pageHeight)
        val inkList = dict.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray
            ?: return null
        val points = ArrayList<InkPoint>()
        for (i in 0 until inkList.size()) {
            val path = inkList.getObject(i) as? COSArray ?: continue
            var index = 0
            while (index + 1 < path.size()) {
                val x = (path.getObject(index) as? COSFloat)?.floatValue() ?: break
                val y = (path.getObject(index + 1) as? COSFloat)?.floatValue() ?: break
                points.add(InkPoint(x, pageHeight - y, 1f))
                index += 2
            }
        }
        if (points.isEmpty()) return null

        val colorArray = dict.getDictionaryObject(COSName.C) as? COSArray
        val caKey = COSName.getPDFName("CA")
        val alpha = if (dict.containsKey(caKey)) dict.getFloat(caKey).coerceIn(0f, 1f) else 1f
        val color = if (colorArray != null && colorArray.size() >= 3) {
            Color.argb(
                (alpha * 255f).roundToInt().coerceIn(0, 255),
                channel(colorArray.getObject(0)),
                channel(colorArray.getObject(1)),
                channel(colorArray.getObject(2))
            )
        } else {
            Color.BLACK
        }
        val blendMode = dict.getNameAsString(COSName.getPDFName("BM"))
        val subject = dict.getString(COSName.getPDFName("Subj")).orEmpty()
        // Accept both the legacy Multiply marker and the portable subject marker written now.
        val highlighter = blendMode == "Multiply" || subject.equals("Highlight", ignoreCase = true)

        val style = dict.getDictionaryObject(COSName.getPDFName("BS")) as? COSDictionary
        val width = style?.getFloat(COSName.getPDFName("W")) ?: 2f
        val nameKey = COSName.getPDFName("NM")
        val annotationName = dict.getString(nameKey)
            ?: (dict.getDictionaryObject(nameKey) as? COSName)?.name.orEmpty()
        val id = annotationName.removePrefix(PdfInkExporter.NAME_PREFIX)
            .ifBlank { java.util.UUID.randomUUID().toString() }

        // The annotation stores absolute points; derive the page-relative width back from it.
        val widthPt = width.coerceIn(0.3f, 120f)
        return Stroke(
            id = id,
            page = page,
            color = color,
            width = widthPt,
            highlighter = highlighter,
            baseWidth = widthPt * REFERENCE_PAGE_LONG_EDGE_PT / pageLongEdgePt.coerceAtLeast(1f),
        ).also { it.points.addAll(points) }
    }

    private fun channel(value: Any?): Int {
        val float = (value as? COSFloat)?.floatValue() ?: 0f
        return (float * 255f).roundToInt().coerceIn(0, 255)
    }
}
