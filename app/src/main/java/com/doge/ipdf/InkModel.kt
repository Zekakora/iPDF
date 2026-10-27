package com.doge.ipdf

import android.graphics.Path
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Restorable reading position: page index plus an offset inside that page, in PDF points. */
data class Viewport(val page: Int, val offsetPt: Float, val zoom: Float) {
    fun toJson(): String = JSONObject()
        .put("p", page)
        .put("o", offsetPt.toDouble())
        .put("z", zoom.toDouble())
        .toString()

    companion object {
        fun fromJson(text: String?): Viewport? = try {
            text?.let {
                val o = JSONObject(it)
                Viewport(o.optInt("p", 0), o.optDouble("o", 0.0).toFloat(), o.optDouble("z", 1.0).toFloat())
            }
        } catch (_: Exception) {
            null
        }
    }
}

data class PageSize(val width: Float, val height: Float)

/** A point of a stroke, in PDF points with the origin at the page's top-left corner. */
data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)

/**
 * One pen stroke. Points are stored in page space (PDF points, top-left origin) so the stroke
 * survives zooming, scrolling and rotation, and can be written to a standard PDF /Ink annotation.
 */
class Stroke(
    val id: String,
    val page: Int,
    val color: Int,
    var width: Float,
    val highlighter: Boolean,
    val points: MutableList<InkPoint> = ArrayList()
) {
    private val bounds = RectF()
    private var boundsDirty = true
    private val inkPath = Path()
    private var pathDirty = true

    fun add(point: InkPoint) {
        points.add(point)
        boundsDirty = true
        pathDirty = true
    }

    fun copyPoints(): List<InkPoint> = points.map { InkPoint(it.x, it.y, it.pressure) }

    /** Creates a new stroke with the same style but a fresh identity, used by pixel erasing. */
    fun fork(newPoints: List<InkPoint>): Stroke = Stroke(
        id = UUID.randomUUID().toString(),
        page = page,
        color = color,
        width = width,
        highlighter = highlighter
    ).also { it.points.addAll(newPoints) }

    fun replacePoints(newPoints: List<InkPoint>) {
        points.clear()
        points.addAll(newPoints)
        boundsDirty = true
        pathDirty = true
    }

    fun translateBy(dx: Float, dy: Float) {
        for (index in points.indices) {
            val point = points[index]
            points[index] = InkPoint(point.x + dx, point.y + dy, point.pressure)
        }
        boundsDirty = true
        pathDirty = true
    }

    /** Positions the stroke at [origin] plus an offset, avoiding accumulated drag drift. */
    fun moveFrom(origin: List<InkPoint>, dx: Float, dy: Float) {
        points.clear()
        origin.forEach { points.add(InkPoint(it.x + dx, it.y + dy, it.pressure)) }
        boundsDirty = true
        pathDirty = true
    }

    /** Scales the stroke around an anchor, starting from [origin] so dragging never drifts. */
    fun scaleFrom(origin: List<InkPoint>, anchorX: Float, anchorY: Float, sx: Float, sy: Float) {
        points.clear()
        origin.forEach { point ->
            points.add(
                InkPoint(
                    anchorX + (point.x - anchorX) * sx,
                    anchorY + (point.y - anchorY) * sy,
                    point.pressure
                )
            )
        }
        boundsDirty = true
        pathDirty = true
    }

    fun bounds(): RectF {
        if (boundsDirty) computeBounds()
        return bounds
    }

    fun path(): Path {
        if (pathDirty) buildPath()
        return inkPath
    }

    fun averagePressure(): Float {
        if (points.isEmpty()) return 1f
        var sum = 0f
        points.forEach { sum += it.pressure }
        return (sum / points.size).coerceIn(0.15f, 1f)
    }

    /** Shortest distance from a page-space point to this stroke, used by the eraser. */
    fun distanceTo(x: Float, y: Float): Float {
        if (points.isEmpty()) return Float.MAX_VALUE
        if (points.size == 1) {
            val p = points[0]
            return hypot(x - p.x, y - p.y)
        }
        var best = Float.MAX_VALUE
        for (i in 0 until points.size - 1) {
            best = min(best, segmentDistance(x, y, points[i], points[i + 1]))
        }
        return best
    }

    private fun computeBounds() {
        if (points.isEmpty()) {
            bounds.setEmpty()
            boundsDirty = false
            return
        }
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        points.forEach {
            left = min(left, it.x)
            top = min(top, it.y)
            right = max(right, it.x)
            bottom = max(bottom, it.y)
        }
        bounds.set(left, top, right, bottom)
        boundsDirty = false
    }

    private fun buildPath() {
        inkPath.rewind()
        val pts = points
        when {
            pts.isEmpty() -> Unit
            pts.size == 1 -> {
                inkPath.moveTo(pts[0].x, pts[0].y)
                inkPath.lineTo(pts[0].x + 0.01f, pts[0].y)
            }

            else -> {
                inkPath.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size - 1) {
                    val midX = (pts[i].x + pts[i + 1].x) / 2f
                    val midY = (pts[i].y + pts[i + 1].y) / 2f
                    inkPath.quadTo(pts[i].x, pts[i].y, midX, midY)
                }
                val last = pts.last()
                inkPath.lineTo(last.x, last.y)
            }
        }
        pathDirty = false
    }

    fun toJson(): JSONObject {
        val pts = JSONArray()
        points.forEach { p ->
            pts.put(
                JSONArray()
                    .put(round(p.x))
                    .put(round(p.y))
                    .put(round(p.pressure))
            )
        }
        return JSONObject()
            .put("id", id)
            .put("page", page)
            .put("color", color)
            .put("width", round(width))
            .put("hl", highlighter)
            .put("pts", pts)
    }

    companion object {
        private fun round(value: Float): Double = (Math.round(value * 100f) / 100.0)

        fun fromJson(obj: JSONObject): Stroke? {
            return try {
                val pts = obj.getJSONArray("pts")
                if (pts.length() == 0) return null
                val stroke = Stroke(
                    id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    page = obj.getInt("page"),
                    color = obj.getInt("color"),
                    width = obj.optDouble("width", 2.0).toFloat(),
                    highlighter = obj.optBoolean("hl", false)
                )
                for (i in 0 until pts.length()) {
                    val p = pts.getJSONArray(i)
                    stroke.points.add(
                        InkPoint(
                            p.getDouble(0).toFloat(),
                            p.getDouble(1).toFloat(),
                            p.optDouble(2, 1.0).toFloat()
                        )
                    )
                }
                stroke
            } catch (_: Exception) {
                null
            }
        }

        private fun segmentDistance(x: Float, y: Float, a: InkPoint, b: InkPoint): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lengthSq = dx * dx + dy * dy
            if (lengthSq < 0.0001f) return hypot(x - a.x, y - a.y)
            var t = ((x - a.x) * dx + (y - a.y) * dy) / lengthSq
            t = t.coerceIn(0f, 1f)
            return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
        }
    }
}
