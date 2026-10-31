package com.doge.ipdf

import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.InputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Converts /Ink and /Highlight annotations created by other apps into editable [Stroke]s.
 *
 * They are adopted once per document (the journal records `foreignAdopted`), so the normal eraser,
 * undo, lasso and export paths apply. The first export removes the original foreign annotations
 * from the PDF and writes the adopted strokes as this app's /Ink annotations, which keeps desktop
 * readers free of duplicates. Rotated pages and appearance-stream-only annotations are left
 * untouched and are not adopted.
 */
object ForeignInkReader {
    fun read(stream: InputStream): List<Stroke> = runCatching {
        stream.use { input ->
            PDDocument.load(input).use { document -> collect(document) }
        }
    }.getOrDefault(emptyList())

    fun read(file: File): List<Stroke> = runCatching {
        file.inputStream().use { read(it) }
    }.getOrDefault(emptyList())

    fun isAdoptableInk(dict: COSDictionary): Boolean {
        if (dict.getNameAsString(COSName.SUBTYPE) != "Ink") return false
        val inkList = dict.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray
            ?: return false
        for (i in 0 until inkList.size()) {
            val path = inkList.getObject(i) as? COSArray ?: continue
            if (path.size() >= 4) return true
        }
        return false
    }

    fun isAdoptableHighlight(dict: COSDictionary): Boolean {
        if (dict.getNameAsString(COSName.SUBTYPE) != "Highlight") return false
        val quads = dict.getDictionaryObject(COSName.getPDFName("QuadPoints")) as? COSArray
            ?: return false
        return quads.size() >= 8
    }

    private fun collect(document: PDDocument): List<Stroke> {
        val result = ArrayList<Stroke>()
        for (pageIndex in 0 until document.numberOfPages) {
            val page = document.getPage(pageIndex)
            if (page.rotation % 360 != 0) continue
            val crop = page.cropBox
            val left = crop.lowerLeftX
            val bottom = crop.lowerLeftY
            val height = crop.height
            val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            for (i in 0 until annots.size()) {
                val dict = annots.getObject(i) as? COSDictionary ?: continue
                if (PdfInkExporter.isOurInk(dict)) continue
                // The page long edge turns the annotation's absolute width into the page-relative
                // width this app now stores, so adopted ink keeps its on-page thickness.
                val pageLongEdgePt = max(crop.width, crop.height)
                when (dict.getNameAsString(COSName.SUBTYPE)) {
                    "Ink" -> addInk(dict, pageIndex, i, left, bottom, height, pageLongEdgePt, result)
                    "Highlight" ->
                        addHighlight(dict, pageIndex, i, left, bottom, height, pageLongEdgePt, result)
                }
            }
        }
        return result
    }

    internal fun addInk(
        dict: COSDictionary,
        page: Int,
        annotationIndex: Int,
        left: Float,
        bottom: Float,
        height: Float,
        pageLongEdgePt: Float,
        target: MutableList<Stroke>
    ) {
        val inkList = dict.getDictionaryObject(COSName.getPDFName("InkList")) as? COSArray ?: return
        val color = colorOf(dict)
        val width = widthOf(dict)
        val highlighter = isMultiply(dict)
        for (pathIndex in 0 until inkList.size()) {
            val path = inkList.getObject(pathIndex) as? COSArray ?: continue
            val points = readPoints(path, left, bottom, height)
            if (points.size < 2) continue
            val stroke = Stroke(
                id = "foreign-i-$page-$annotationIndex-$pathIndex",
                page = page,
                color = color,
                width = width,
                highlighter = highlighter,
                baseWidth = width * REFERENCE_PAGE_LONG_EDGE_PT / pageLongEdgePt.coerceAtLeast(1f),
            )
            stroke.points.addAll(points)
            target.add(stroke)
        }
    }

    internal fun addHighlight(
        dict: COSDictionary,
        page: Int,
        annotationIndex: Int,
        left: Float,
        bottom: Float,
        height: Float,
        pageLongEdgePt: Float,
        target: MutableList<Stroke>
    ) {
        val quads = dict.getDictionaryObject(COSName.getPDFName("QuadPoints")) as? COSArray ?: return
        val color = colorOf(dict)
        var quad = 0
        var quadIndex = 0
        while (quad + 7 < quads.size()) {
            val values = FloatArray(8)
            var valid = true
            for (k in 0..7) {
                val number = quads.getObject(quad + k) as? COSNumber
                if (number == null) {
                    valid = false
                    break
                }
                values[k] = number.floatValue()
            }
            if (valid) {
                val x1 = values[0] - left
                val y1 = height - (values[1] - bottom)
                val x2 = values[2] - left
                val y2 = height - (values[3] - bottom)
                val x3 = values[4] - left
                val y3 = height - (values[5] - bottom)
                val x4 = values[6] - left
                val y4 = height - (values[7] - bottom)
                val startX = (x1 + x3) / 2f
                val startY = (y1 + y3) / 2f
                val endX = (x2 + x4) / 2f
                val endY = (y2 + y4) / 2f
                val width = hypot(x1 - x3, y1 - y3).coerceIn(1f, 80f)
                if (hypot(endX - startX, endY - startY) >= 0.5f) {
                    val stroke = Stroke(
                        id = "foreign-h-$page-$annotationIndex-$quadIndex",
                        page = page,
                        color = color,
                        width = width,
                        highlighter = true,
                        baseWidth = width * REFERENCE_PAGE_LONG_EDGE_PT /
                            pageLongEdgePt.coerceAtLeast(1f),
                    )
                    stroke.points.add(InkPoint(startX, startY, 1f))
                    stroke.points.add(InkPoint(endX, endY, 1f))
                    target.add(stroke)
                }
            }
            quad += 8
            quadIndex++
        }
    }

    private fun readPoints(path: COSArray, left: Float, bottom: Float, height: Float): List<InkPoint> {
        val points = ArrayList<InkPoint>(path.size() / 2)
        var index = 0
        while (index + 1 < path.size()) {
            val x = (path.getObject(index) as? COSNumber)?.floatValue() ?: break
            val y = (path.getObject(index + 1) as? COSNumber)?.floatValue() ?: break
            points.add(InkPoint(x - left, height - (y - bottom), 1f))
            index += 2
        }
        return points
    }

    private fun colorOf(dict: COSDictionary): Int {
        val array = dict.getDictionaryObject(COSName.C) as? COSArray
        val alpha = alphaOf(dict)
        if (array == null) return Color.argb(alpha, 0, 0, 0)
        val components = ArrayList<Float>(array.size())
        for (i in 0 until array.size()) {
            val number = array.getObject(i) as? COSNumber ?: continue
            components.add(number.floatValue().coerceIn(0f, 1f))
        }
        return when (components.size) {
            1 -> {
                val gray = channel(components[0])
                Color.argb(alpha, gray, gray, gray)
            }
            3 -> Color.argb(alpha, channel(components[0]), channel(components[1]), channel(components[2]))
            4 -> {
                val key = 1f - components[3]
                Color.argb(
                    alpha,
                    channel((1f - components[0]) * key),
                    channel((1f - components[1]) * key),
                    channel((1f - components[2]) * key)
                )
            }
            else -> Color.argb(alpha, 0, 0, 0)
        }
    }

    private fun alphaOf(dict: COSDictionary): Int {
        val key = COSName.getPDFName("CA")
        val value = if (dict.containsKey(key)) dict.getFloat(key) else 1f
        return (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun widthOf(dict: COSDictionary): Float {
        val style = dict.getDictionaryObject(COSName.getPDFName("BS")) as? COSDictionary
        val width = style?.getFloat(COSName.getPDFName("W")) ?: 1.5f
        return width.coerceIn(0.3f, 40f)
    }

    private fun isMultiply(dict: COSDictionary): Boolean {
        val blendMode = dict.getNameAsString(COSName.getPDFName("BM"))
        val subject = dict.getString(COSName.getPDFName("Subj")).orEmpty()
        return blendMode == "Multiply" || subject.equals("Highlight", ignoreCase = true)
    }

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()
}
