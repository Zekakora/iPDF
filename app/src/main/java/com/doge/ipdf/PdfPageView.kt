package com.doge.ipdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.ParcelFileDescriptor
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.SystemClock
import android.util.LruCache
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/**
 * Single surface that renders the PDF pages, owns vertical scrolling/flinging, horizontal panning,
 * focal pinch-zoom and the stylus ink layer. Nothing is delegated to a parent ScrollView, so there
 * is no interception race between finger scrolling and two-finger zooming.
 *
 * Coordinates:
 *  - page points (1/72 inch, origin at the page's top-left) are the canonical ink coordinates;
 *  - content pixels = points * density * zoom, with [scrollY] measured inside the content area;
 *  - [topInset] reserves the space covered by the floating top bar.
 */
class PdfPageView(context: Context) : View(context) {
    companion object {
        private const val MIN_ZOOM = 0.5f
        private const val MAX_ZOOM = 8f
        private const val TILE_SIZE_PX = 768
        private const val PAGE_GAP_PT = 10f
        private const val VIEWPORT_REPORT_MS = 250L
        private const val PALM_WINDOW_MS = 280L
    }

    var onViewportChanged: ((Viewport) -> Unit)? = null
    var onStylusButton: (() -> Unit)? = null

    /**
     * Called once after a document opens (or the reader switches to it), on the first stylus
     * contact. Returns the marker (pen) preset to start with, so the first stroke is already drawn
     * with it. Only fires while no tool is selected.
     */
    var onAutoMarker: (() -> BrushPreset?)? = null
    private var autoMarkerArmed = false

    var tool: PenTool = PenTool.NONE
        set(value) {
            if (field != value) {
                field = value
                clearLassoState()
            }
            invalidate()
        }

    var preset: BrushPreset = BrushPreset(Color.BLACK, 2f)
        set(value) {
            field = value
            invalidate()
        }

    var eraserWidthPt: Float = 16f
        set(value) {
            field = value
            invalidate()
        }

    /** When true the eraser removes only the pixels under the tip, not whole strokes. */
    var eraserPixel: Boolean = false

    /** Stroke stabilization strength: 0 = off (raw pen input), 1 = strongest smoothing. */
    var stabilizerStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private val inkStabilizer = StrokeStabilizer()

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pageGapPx get() = PAGE_GAP_PT * density

    private var session: ReaderSession? = null
    private var openedFile: File? = null
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile
    private var renderer: PdfRenderer? = null
    private val sizes = ArrayList<PageSize>()

    // PDF pages are rendered as visible tiles. Keep PdfRenderer work off the UI thread and draw a
    // white placeholder until a tile is ready, so opening, switching and scrolling never block a
    // frame. A tile is rendered at the nearest power-of-two zoom level and then lightly scaled for
    // intermediate pinch values; crossing a bucket requests a new, sharper set of tiles.
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ipdf-page-render").apply { isDaemon = true }
    }
    private val renderLock = Any()
    @Volatile
    private var renderGeneration = 0
    private data class TileKey(
        val page: Int,
        val x: Int,
        val y: Int,
        val zoomBucket: Float,
    )

    private val pendingTiles = HashSet<TileKey>()
    private val failedTiles = HashSet<TileKey>()

    private var zoom = 1f
    var zoomSpeed: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 2f)
        }
    var scrollSpeed: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 2f)
        }
    private var pinching = false
    private var pinchRenderBucket = 1f
    private var scrollY = 0f
    private var panX = 0f
    private var topInset = 0
    private var pendingRestore: Viewport? = null

    private val maxCacheKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceAtLeast(16 * 1024)
    private val tileCache = object : LruCache<TileKey, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.byteCount / 1024
    }

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE02563EB.toInt()
        strokeWidth = 1.6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(7f, 5f), 0f)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB02563EB.toInt()
        strokeWidth = 1.2f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val selectionGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x332563EB
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2563EB.toInt()
        strokeWidth = 1.2f
    }
    private val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)

    private var liveStroke: Stroke? = null
    private var eraserX = Float.NaN
    private var eraserY = Float.NaN
    private var eraserPage = -1
    private var erasing = false
    private var lastPenEventTime = 0L

    private var lassoPoints: MutableList<InkPoint>? = null
    private var lassoPage = -1
    private var selectedStrokes: List<Stroke> = emptyList()
    private var selectionPage = -1
    private var movingSelection = false
    private var moveOrigin: Map<String, List<InkPoint>> = emptyMap()
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var moveDx = 0f
    private var moveDy = 0f

    private var scalingSelection = false
    private var scaleAnchorX = 0f
    private var scaleAnchorY = 0f
    private var scaleCornerX = 0f
    private var scaleCornerY = 0f
    private var scaleOrigin: Map<String, List<InkPoint>> = emptyMap()
    private var scaleWidths: Map<String, Float> = emptyMap()
    private var scaleSx = 1f
    private var scaleSy = 1f
    private var transformPointerId = MotionEvent.INVALID_POINTER_ID
    private var stylusButtonDown = false

    private val scroller = OverScroller(context)
    private val velocity = VelocityTracker.obtain()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            abortFling()
            dragMode = DragMode.PINCH
            pinching = true
            pinchRenderBucket = renderZoomBucket()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val rawFactor = detector.scaleFactor.coerceAtLeast(0.001f).toDouble()
            val adjustedFactor = exp(ln(rawFactor) * zoomSpeed.toDouble()).toFloat()
            val target = (zoom * adjustedFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (abs(target - zoom) < 0.0005f) return true
            zoomAround(detector.focusX, detector.focusY, target)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            pinching = false
            scheduleViewportReport()
            invalidate()
        }
    })

    private enum class DragMode { NONE, UNDECIDED, VERTICAL, HORIZONTAL, PINCH }

    private var dragMode = DragMode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val viewportReporter = Runnable { reportViewport() }
    private var lastViewport: Viewport? = null

    fun setTopInset(px: Int) {
        if (px == topInset) return
        topInset = px
        scrollY = scrollY.coerceIn(0f, maxScroll())
        invalidate()
    }

    fun openDocument(source: File, restore: Viewport?, cachedSizes: List<PageSize> = emptyList()) {
        if (openedFile == source && renderer != null) return
        closeDocument()
        val pfd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try {
            PdfRenderer(pfd)
        } catch (t: Throwable) {
            pfd.close()
            throw t
        }
        descriptor = pfd
        renderer = pdf
        openedFile = source
        zoom = 1f
        pinching = false
        scrollY = 0f
        panX = 0f
        if (cachedSizes.size == pdf.pageCount) {
            sizes.addAll(cachedSizes)
        } else {
            try {
                for (index in 0 until pdf.pageCount) {
                    pdf.openPage(index).use { page ->
                        sizes.add(PageSize(page.width.toFloat(), page.height.toFloat()))
                    }
                }
                session?.storePageSizes(sizes.toList())
            } catch (_: Throwable) {
                // Keep whatever pages could be measured; rendering will show placeholders.
            }
        }
        pendingRestore = restore
        requestLayout()
        invalidate()
        applyPendingRestore()
    }

    fun closeDocument() {
        reportViewport()
        tileCache.evictAll()
        pendingTiles.clear()
        failedTiles.clear()
        renderGeneration++
        val oldRenderer = renderer
        val oldDescriptor = descriptor
        renderer = null
        descriptor = null
        openedFile = null
        sizes.clear()
        liveStroke = null
        erasing = false
        eraserPage = -1
        clearLassoState()
        zoom = 1f
        scrollY = 0f
        panX = 0f
        pendingRestore = null
        invalidate()
        if (oldRenderer != null || oldDescriptor != null) {
            // Close behind any in-flight render; the generation check makes its result harmless.
            runCatching {
                renderExecutor.execute {
                    synchronized(renderLock) {
                        runCatching { oldRenderer?.close() }
                        runCatching { oldDescriptor?.close() }
                    }
                }
            }
        }
    }

    fun attachSession(value: ReaderSession) {
        session = value
        autoMarkerArmed = true
        invalidate()
    }

    /** Releases the renderer and its background worker when Compose removes this view. */
    fun release() {
        if (renderExecutor.isShutdown) return
        closeDocument()
        renderExecutor.shutdown()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && oldh != 0) {
            scrollY = scrollY.coerceIn(0f, maxScroll())
            clampPan()
        }
        applyPendingRestore()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyPendingRestore()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyPendingRestore()
    }

    private fun applyPendingRestore() {
        val viewport = pendingRestore ?: return
        if (width == 0 || height == 0 || sizes.isEmpty()) return
        zoom = viewport.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        panX = 0f
        val page = viewport.page.coerceIn(0, sizes.size - 1)
        scrollY = ((pageTopPt(page) + viewport.offsetPt) * density * zoom).coerceIn(0f, maxScroll())
        pendingRestore = null
        invalidate()
        reportViewport()
    }

    // ---------------------------------------------------------------- geometry

    private fun pageTopPt(index: Int): Float {
        var top = PAGE_GAP_PT
        for (i in 0 until index) top += sizes[i].height + PAGE_GAP_PT
        return top
    }

    private fun contentHeightPt(): Float {
        if (sizes.isEmpty()) return 0f
        var height = PAGE_GAP_PT
        sizes.forEach { height += it.height + PAGE_GAP_PT }
        return height
    }

    private fun scale(): Float = density * zoom

    private fun contentHeightPx(): Float = contentHeightPt() * scale()

    private fun viewportHeight(): Float = max(0, height - topInset).toFloat()

    private fun maxScroll(): Float = (contentHeightPx() - viewportHeight()).coerceAtLeast(0f)

    private fun maxPan(): Float {
        if (sizes.isEmpty()) return 0f
        val widest = sizes.maxOf { it.width }
        return ((widest * scale() - width) / 2f).coerceAtLeast(0f)
    }

    private fun clampPan() {
        panX = panX.coerceIn(-maxPan(), maxPan())
    }

    private fun pageAtContentPt(contentPt: Float): Int {
        if (sizes.isEmpty()) return 0
        for (index in sizes.indices) {
            val top = pageTopPt(index)
            if (contentPt < top + sizes[index].height + PAGE_GAP_PT / 2f) return index
        }
        return sizes.size - 1
    }

    private data class PageHit(val page: Int, val xPt: Float, val yPt: Float)

    private fun mapToPage(x: Float, y: Float): PageHit? {
        if (sizes.isEmpty()) return null
        val contentPt = (y + scrollY - topInset) / scale()
        val page = pageAtContentPt(contentPt)
        val size = sizes[page]
        if (contentPt < pageTopPt(page) - PAGE_GAP_PT / 2f) return null
        val topPx = pageTopPt(page) * scale() - scrollY + topInset
        val leftPx = (width - size.width * scale()) / 2f + panX
        return PageHit(page, (x - leftPx) / scale(), (y - topPx) / scale())
    }

    private fun mapToPage(page: Int, x: Float, y: Float): PageHit {
        val size = sizes[page]
        val topPx = pageTopPt(page) * scale() - scrollY + topInset
        val leftPx = (width - size.width * scale()) / 2f + panX
        return PageHit(page, (x - leftPx) / scale(), (y - topPx) / scale())
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = scale()
        canvas.save()
        if (topInset > 0) canvas.clipRect(0f, topInset.toFloat(), width.toFloat(), height.toFloat())
        canvas.drawColor(Color.rgb(31, 33, 38))

        if (sizes.isEmpty()) {
            canvas.restore()
            return
        }

        for (index in sizes.indices) {
            val size = sizes[index]
            val drawWidth = size.width * scale
            val drawHeight = size.height * scale
            val top = pageTopPt(index) * scale - scrollY + topInset
            if (top > height || top + drawHeight < topInset) continue
            val left = (width - drawWidth) / 2f + panX
            drawPageTiles(canvas, index, left, top, drawWidth, drawHeight, scale)
            drawInk(canvas, index, left, top, drawWidth, drawHeight, scale)
        }
        canvas.restore()
    }

    private fun renderZoomBucket(): Float = when {
        zoom < 0.707f -> 0.5f
        zoom < 1.414f -> 1f
        zoom < 2.828f -> 2f
        zoom < 5.657f -> 4f
        else -> 8f
    }

    private fun drawPageTiles(
        canvas: Canvas,
        pageIndex: Int,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
        currentScale: Float,
    ) {
        pagePaint.color = Color.WHITE
        canvas.drawRect(left, top, left + drawWidth, top + drawHeight, pagePaint)

        val bucket = if (pinching) pinchRenderBucket else renderZoomBucket()
        val tileScale = density * bucket
        val tilePageSize = TILE_SIZE_PX / tileScale
        val page = sizes[pageIndex]

        val visibleLeft = ((-left) / currentScale).coerceAtLeast(0f)
        val visibleRight = ((width - left) / currentScale).coerceAtMost(page.width)
        val visibleTop = ((topInset - top) / currentScale).coerceAtLeast(0f)
        val visibleBottom = ((height - top) / currentScale).coerceAtMost(page.height)
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return

        val firstX = floor(visibleLeft / tilePageSize).toInt().coerceAtLeast(0)
        val lastX = floor((visibleRight - 0.001f) / tilePageSize).toInt()
        val firstY = floor(visibleTop / tilePageSize).toInt().coerceAtLeast(0)
        val lastY = floor((visibleBottom - 0.001f) / tilePageSize).toInt()

        for (tileY in firstY..lastY) {
            for (tileX in firstX..lastX) {
                val key = TileKey(pageIndex, tileX, tileY, bucket)
                val tileLeftPt = tileX * tilePageSize
                val tileTopPt = tileY * tilePageSize
                val tileWidthPt = (page.width - tileLeftPt).coerceAtMost(tilePageSize)
                val tileHeightPt = (page.height - tileTopPt).coerceAtMost(tilePageSize)
                if (tileWidthPt <= 0f || tileHeightPt <= 0f) continue

                val tileLeft = left + tileLeftPt * currentScale
                val tileTop = top + tileTopPt * currentScale
                val tileRight = tileLeft + tileWidthPt * currentScale
                val tileBottom = tileTop + tileHeightPt * currentScale
                val bitmap = tileCache.get(key)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, RectF(tileLeft, tileTop, tileRight, tileBottom), pagePaint)
                } else {
                    drawFallbackTiles(
                        canvas = canvas,
                        pageIndex = pageIndex,
                        pageLeft = left,
                        pageTop = top,
                        currentScale = currentScale,
                        targetLeftPt = tileLeftPt,
                        targetTopPt = tileTopPt,
                        targetRightPt = tileLeftPt + tileWidthPt,
                        targetBottomPt = tileTopPt + tileHeightPt,
                        targetLeft = tileLeft,
                        targetTop = tileTop,
                        targetRight = tileRight,
                        targetBottom = tileBottom,
                        targetBucket = bucket,
                    )
                    if (key !in failedTiles) requestTile(key)
                }
            }
        }
    }

    /**
     * Keeps the previous zoom bucket visible while the sharper bucket is rendered. The page-space
     * tile grids differ by bucket, so fallback tiles are found by their overlapping page rectangle
     * instead of reusing the same tile coordinates.
     */
    private fun drawFallbackTiles(
        canvas: Canvas,
        pageIndex: Int,
        pageLeft: Float,
        pageTop: Float,
        currentScale: Float,
        targetLeftPt: Float,
        targetTopPt: Float,
        targetRightPt: Float,
        targetBottomPt: Float,
        targetLeft: Float,
        targetTop: Float,
        targetRight: Float,
        targetBottom: Float,
        targetBucket: Float,
    ) {
        val fallbackBuckets = when (targetBucket) {
            0.5f -> floatArrayOf(1f, 2f, 4f, 8f)
            1f -> floatArrayOf(2f, 0.5f, 4f, 8f)
            2f -> floatArrayOf(1f, 4f, 0.5f, 8f)
            4f -> floatArrayOf(2f, 8f, 1f, 0.5f)
            else -> floatArrayOf(4f, 2f, 1f, 0.5f)
        }
        val targetClip = RectF(targetLeft, targetTop, targetRight, targetBottom)
        for (fallbackBucket in fallbackBuckets) {
            val fallbackTilePageSize = TILE_SIZE_PX / (density * fallbackBucket)
            val firstX = floor(targetLeftPt / fallbackTilePageSize).toInt().coerceAtLeast(0)
            val lastX = floor((targetRightPt - 0.001f) / fallbackTilePageSize).toInt()
            val firstY = floor(targetTopPt / fallbackTilePageSize).toInt().coerceAtLeast(0)
            val lastY = floor((targetBottomPt - 0.001f) / fallbackTilePageSize).toInt()
            var drew = false

            canvas.save()
            canvas.clipRect(targetClip)
            for (tileY in firstY..lastY) {
                for (tileX in firstX..lastX) {
                    val key = TileKey(pageIndex, tileX, tileY, fallbackBucket)
                    val bitmap = tileCache.get(key) ?: continue
                    val tileLeftPt = tileX * fallbackTilePageSize
                    val tileTopPt = tileY * fallbackTilePageSize
                    val page = sizes[pageIndex]
                    val tileWidthPt = (page.width - tileLeftPt).coerceAtMost(fallbackTilePageSize)
                    val tileHeightPt = (page.height - tileTopPt).coerceAtMost(fallbackTilePageSize)
                    if (tileWidthPt <= 0f || tileHeightPt <= 0f) continue
                    val tileLeft = pageLeft + tileLeftPt * currentScale
                    val tileTop = pageTop + tileTopPt * currentScale
                    val tileRight = tileLeft + tileWidthPt * currentScale
                    val tileBottom = tileTop + tileHeightPt * currentScale
                    canvas.drawBitmap(bitmap, null, RectF(tileLeft, tileTop, tileRight, tileBottom), pagePaint)
                    drew = true
                }
            }
            canvas.restore()
            if (drew) return
        }
    }

    private fun requestTile(key: TileKey) {
        if (key.page !in sizes.indices) return
        if (!pendingTiles.add(key)) return
        val pdf = renderer
        if (pdf == null || renderExecutor.isShutdown) {
            pendingTiles.remove(key)
            return
        }
        val generation = renderGeneration
        renderExecutor.execute {
            val bitmap = synchronized(renderLock) {
                if (generation != renderGeneration || renderer !== pdf) return@synchronized null
                try {
                    val size = sizes[key.page]
                    val tileScale = density * key.zoomBucket
                    val tilePageSize = TILE_SIZE_PX / tileScale
                    val leftPt = key.x * tilePageSize
                    val topPt = key.y * tilePageSize
                    val widthPt = (size.width - leftPt).coerceAtMost(tilePageSize)
                    val heightPt = (size.height - topPt).coerceAtMost(tilePageSize)
                    if (widthPt <= 0f || heightPt <= 0f) return@synchronized null
                    val width = max(1, ceil(widthPt * tileScale).toInt())
                    val height = max(1, ceil(heightPt * tileScale).toInt())
                    pdf.openPage(key.page).use { page ->
                        val value = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        value.eraseColor(Color.WHITE)
                        val transform = Matrix().apply {
                            setScale(tileScale, tileScale)
                            postTranslate(-leftPt * tileScale, -topPt * tileScale)
                        }
                        page.render(value, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        value
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            post {
                pendingTiles.remove(key)
                if (bitmap == null) {
                    if (generation == renderGeneration) failedTiles.add(key)
                } else if (generation == renderGeneration && renderer === pdf) {
                    tileCache.put(key, bitmap)
                }
                invalidate()
            }
        }
    }

    private fun drawInk(
        canvas: Canvas,
        pageIndex: Int,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
        scale: Float
    ) {
        val strokes = session?.strokes ?: return
        val live = liveStroke
        val selection = activeSelection()
        val hasSelection = selection.isNotEmpty() && selectionPage == pageIndex
        val hasLasso = lassoPage == pageIndex && lassoPoints != null
        if (strokes.none { it.page == pageIndex } && (live == null || live.page != pageIndex) &&
            eraserPage != pageIndex && !hasSelection && !hasLasso
        ) {
            return
        }
        canvas.save()
        canvas.clipRect(left, top, left + drawWidth, top + drawHeight)
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        if (hasSelection) {
            selection.forEach { if (it.page == pageIndex) drawSelectionGlow(canvas, it) }
        }
        strokes.forEach { stroke ->
            if (stroke.page == pageIndex) drawStroke(canvas, stroke)
        }
        if (live != null && live.page == pageIndex) drawStroke(canvas, live)
        if (hasSelection) drawSelectionBounds(canvas, selection)
        if (hasLasso) drawLassoPath(canvas)
        if (eraserPage == pageIndex && !eraserX.isNaN()) {
            eraserPaint.color = Color.argb(70, 255, 255, 255)
            eraserPaint.strokeWidth = 1.5f
            canvas.drawCircle(eraserX, eraserY, eraserWidthPt / 2f, eraserPaint)
            eraserPaint.color = Color.argb(220, 255, 255, 255)
            eraserPaint.strokeWidth = 0.8f
            canvas.drawCircle(eraserX, eraserY, eraserWidthPt / 2f, eraserPaint)
        }
        canvas.restore()
    }

    private fun drawSelectionGlow(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        selectionGlow.strokeWidth = stroke.width + 5f
        if (stroke.points.size == 1) {
            val point = stroke.points[0]
            canvas.drawCircle(point.x, point.y, selectionGlow.strokeWidth / 2f, selectionGlow)
        } else {
            canvas.drawPath(stroke.path(), selectionGlow)
        }
    }

    private fun drawSelectionBounds(canvas: Canvas, selection: List<Stroke>) {
        val bounds = selectionBounds(selection)
        if (bounds.width() < 0.5f && bounds.height() < 0.5f) return
        canvas.drawRoundRect(bounds, 5f, 5f, selectionPaint)
        val safeZoom = zoom.coerceAtLeast(0.1f)
        val radius = 6f / safeZoom
        handleStroke.strokeWidth = 1.4f / safeZoom
        canvas.drawCircle(bounds.left, bounds.top, radius, handleFill)
        canvas.drawCircle(bounds.left, bounds.top, radius, handleStroke)
        canvas.drawCircle(bounds.right, bounds.top, radius, handleFill)
        canvas.drawCircle(bounds.right, bounds.top, radius, handleStroke)
        canvas.drawCircle(bounds.right, bounds.bottom, radius, handleFill)
        canvas.drawCircle(bounds.right, bounds.bottom, radius, handleStroke)
        canvas.drawCircle(bounds.left, bounds.bottom, radius, handleFill)
        canvas.drawCircle(bounds.left, bounds.bottom, radius, handleStroke)
    }

    private fun drawLassoPath(canvas: Canvas) {
        val points = lassoPoints ?: return
        if (points.size == 1) {
            canvas.drawCircle(points[0].x, points[0].y, 2f, lassoPaint)
            return
        }
        if (points.size < 2) return
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (index in 1 until points.size) path.lineTo(points[index].x, points[index].y)
        path.close()
        canvas.drawPath(path, lassoPaint)
    }

    private fun activeSelection(): List<Stroke> {
        if (selectedStrokes.isEmpty()) return emptyList()
        val all = session?.strokes ?: return emptyList()
        return selectedStrokes.filter { selected -> all.any { it === selected } }
    }

    private fun selectionBounds(strokes: List<Stroke>): RectF {
        val bounds = RectF()
        var first = true
        strokes.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            if (first) {
                bounds.set(stroke.bounds())
                first = false
            } else {
                bounds.union(stroke.bounds())
            }
        }
        if (!first) bounds.inset(-4f, -4f)
        return bounds
    }

    private fun clearLassoState() {
        lassoPoints = null
        lassoPage = -1
        selectedStrokes = emptyList()
        selectionPage = -1
        movingSelection = false
        moveOrigin = emptyMap()
        scalingSelection = false
        scaleOrigin = emptyMap()
        scaleWidths = emptyMap()
        scaleSx = 1f
        scaleSy = 1f
        transformPointerId = MotionEvent.INVALID_POINTER_ID
    }

    /** Returns 0..3 for the four corner handles, or -1 when the point misses them. */
    private fun hitSelectionHandle(x: Float, y: Float, bounds: RectF): Int {
        val slop = 14f / zoom.coerceAtLeast(0.1f)
        val corners = floatArrayOf(
            bounds.left, bounds.top,
            bounds.right, bounds.top,
            bounds.right, bounds.bottom,
            bounds.left, bounds.bottom
        )
        for (index in 0 until 4) {
            val dx = x - corners[index * 2]
            val dy = y - corners[index * 2 + 1]
            if (hypot(dx, dy) <= slop) return index
        }
        return -1
    }

    private fun beginSelectionGesture(x: Float, y: Float): Boolean {
        val hit = mapToPage(x, y) ?: return false
        val selection = activeSelection()
        if (selection.isEmpty() || selectionPage != hit.page) return false
        val bounds = selectionBounds(selection)
        val handle = hitSelectionHandle(hit.xPt, hit.yPt, bounds)
        if (handle >= 0) {
            scalingSelection = true
            scaleOrigin = selection.associate { it.id to it.copyPoints() }
            scaleWidths = selection.associate { it.id to it.width }
            scaleCornerX = if (handle == 0 || handle == 3) bounds.left else bounds.right
            scaleCornerY = if (handle == 0 || handle == 1) bounds.top else bounds.bottom
            scaleAnchorX = if (handle == 0 || handle == 3) bounds.right else bounds.left
            scaleAnchorY = if (handle == 0 || handle == 1) bounds.bottom else bounds.top
            scaleSx = 1f
            scaleSy = 1f
            return true
        }
        if (bounds.contains(hit.xPt, hit.yPt)) {
            movingSelection = true
            moveOrigin = selection.associate { it.id to it.copyPoints() }
            moveStartX = hit.xPt
            moveStartY = hit.yPt
            moveDx = 0f
            moveDy = 0f
            return true
        }
        return false
    }

    private fun updateTransformGesture(x: Float, y: Float) {
        if (selectionPage < 0) return
        val hit = mapToPage(selectionPage, x, y)
        if (movingSelection) {
            val dx = hit.xPt - moveStartX
            val dy = hit.yPt - moveStartY
            activeSelection().forEach { stroke ->
                moveOrigin[stroke.id]?.let { stroke.moveFrom(it, dx, dy) }
            }
            moveDx = dx
            moveDy = dy
            invalidate()
            return
        }
        if (scalingSelection) {
            val axisX = scaleCornerX - scaleAnchorX
            val axisY = scaleCornerY - scaleAnchorY
            val lengthSq = axisX * axisX + axisY * axisY
            // Project the pointer onto the selection diagonal: proportional resize and stable for
            // thin selections such as a single horizontal handwritten line.
            val factor = if (lengthSq > 1f) {
                (((hit.xPt - scaleAnchorX) * axisX + (hit.yPt - scaleAnchorY) * axisY) / lengthSq)
                    .coerceIn(0.15f, 8f)
            } else {
                1f
            }
            activeSelection().forEach { stroke ->
                val origin = scaleOrigin[stroke.id] ?: return@forEach
                stroke.scaleFrom(origin, scaleAnchorX, scaleAnchorY, factor, factor)
                stroke.width = (scaleWidths[stroke.id] ?: stroke.width) * factor
            }
            scaleSx = factor
            scaleSy = factor
            invalidate()
        }
    }

    private fun finishTransformGesture(cancelled: Boolean) {
        transformPointerId = MotionEvent.INVALID_POINTER_ID
        if (movingSelection) {
            movingSelection = false
            val selection = activeSelection()
            if (cancelled) {
                selection.forEach { stroke -> moveOrigin[stroke.id]?.let { stroke.replacePoints(it) } }
            } else if (abs(moveDx) > 0.05f || abs(moveDy) > 0.05f) {
                session?.recordMove(selection, moveDx, moveDy)
            }
            moveOrigin = emptyMap()
            moveDx = 0f
            moveDy = 0f
            invalidate()
            return
        }
        if (scalingSelection) {
            scalingSelection = false
            val selection = activeSelection()
            if (cancelled) {
                selection.forEach { stroke ->
                    scaleOrigin[stroke.id]?.let { stroke.replacePoints(it) }
                    scaleWidths[stroke.id]?.let { stroke.width = it }
                }
            } else if (abs(scaleSx - 1f) > 0.01f || abs(scaleSy - 1f) > 0.01f) {
                val beforePoints = selection.map { scaleOrigin[it.id] ?: it.points.toList() }
                val beforeWidths = selection.map { scaleWidths[it.id] ?: it.width }
                val afterPoints = selection.map { it.points.toList() }
                val afterWidths = selection.map { it.width }
                session?.recordTransform(selection, beforePoints, beforeWidths, afterPoints, afterWidths)
            }
            scaleOrigin = emptyMap()
            scaleWidths = emptyMap()
            scaleSx = 1f
            scaleSy = 1f
            invalidate()
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        val pressure = if (stroke.highlighter) 1f else 0.7f + 0.45f * stroke.averagePressure()
        inkPaint.strokeWidth = (stroke.width * pressure).coerceAtLeast(0.4f)
        inkPaint.color = stroke.color
        inkPaint.xfermode = if (stroke.highlighter) multiply else null
        if (stroke.points.size == 1) {
            val point = stroke.points[0]
            inkPaint.style = Paint.Style.FILL
            canvas.drawCircle(point.x, point.y, inkPaint.strokeWidth / 2f, inkPaint)
            inkPaint.style = Paint.Style.STROKE
        } else {
            canvas.drawPath(stroke.path(), inkPaint)
        }
        inkPaint.xfermode = null
    }

    // ---------------------------------------------------------------- gestures

    override fun onTouchEvent(event: MotionEvent): Boolean {
        handleStylusButtonState(event)
        val stylusPointer = (0 until event.pointerCount).firstOrNull {
            val tool = event.getToolType(it)
            tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        }
        if (stylusPointer != null && stylusPointer == 0) {
            handleStylus(event)
            return true
        }
        if (liveStroke != null || erasing || lassoPoints != null) {
            // The pen owns the gesture; palm and finger contacts are ignored until it lifts.
            return true
        }
        return handleFinger(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        handleStylusButtonState(event)
        return super.onGenericMotionEvent(event)
    }

    /** Standard stylus side-button (USI/other pens); the Focus Pen Pro uses F19 instead. */
    private fun handleStylusButtonState(event: MotionEvent) {
        val stylusSource = (event.source and android.view.InputDevice.SOURCE_STYLUS) != 0 ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        if (!stylusSource) return
        val pressed = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)
        if (pressed && !stylusButtonDown) onStylusButton?.invoke()
        stylusButtonDown = pressed
    }

    private fun handleStylus(event: MotionEvent) {
        lastPenEventTime = SystemClock.uptimeMillis()
        if (event.pointerCount != 1 || sizes.isEmpty()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> finishPenGesture(cancelled = false)
                MotionEvent.ACTION_CANCEL -> finishPenGesture(cancelled = true)
            }
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                abortFling()
                dragMode = DragMode.NONE
                velocity.clear()
                val eraserTip = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                if (autoMarkerArmed && !eraserTip) {
                    // First pen contact after opening this PDF: when nothing is selected yet, start
                    // with the marker (pen) and its first preset, so this very stroke uses it.
                    autoMarkerArmed = false
                    if (tool == PenTool.NONE) {
                        onAutoMarker?.invoke()?.let { marker ->
                            tool = PenTool.PEN
                            preset = marker
                        }
                    }
                }
                when {
                    eraserTip || tool == PenTool.ERASER -> {
                        erasing = true
                        session?.beginErase()
                        eraseAt(event.x, event.y)
                    }

                    tool == PenTool.NONE -> Unit
                    tool == PenTool.LASSO -> beginLassoOrMove(event.x, event.y)
                    else -> startStroke(event.x, event.y, event.pressure)
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    erasing -> eraseAt(event.x, event.y)
                    tool == PenTool.LASSO -> updateLassoOrMove(event.x, event.y)
                    liveStroke != null -> {
                        appendHistory(event)
                        appendPoint(event.x, event.y, event.pressure)
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP -> finishPenGesture(cancelled = false, endX = event.x, endY = event.y)
            MotionEvent.ACTION_CANCEL -> finishPenGesture(cancelled = true)
        }
    }

    private fun startStroke(x: Float, y: Float, pressure: Float) {
        val hit = mapToPage(x, y) ?: return
        if (hit.yPt < 0f || hit.yPt > sizes[hit.page].height) return
        val highlighter = tool == PenTool.HIGHLIGHTER
        liveStroke = Stroke(
            id = UUID.randomUUID().toString(),
            page = hit.page,
            color = preset.color,
            width = preset.width,
            highlighter = highlighter
        ).also { it.add(InkPoint(hit.xPt, hit.yPt, pressure.coerceIn(0.05f, 1f))) }
        inkStabilizer.start(x, y, StrokeStabilizer.radiusFor(stabilizerStrength, density))
    }

    private fun appendHistory(event: MotionEvent) {
        for (index in 0 until event.historySize) {
            appendPoint(event.getHistoricalX(0, index), event.getHistoricalY(0, index), event.getHistoricalPressure(0, index))
        }
    }

    private fun appendPoint(x: Float, y: Float, pressure: Float) {
        val stroke = liveStroke ?: return
        if (!inkStabilizer.add(x, y)) return
        val hit = mapToPage(stroke.page, inkStabilizer.x, inkStabilizer.y)
        stroke.add(InkPoint(hit.xPt, hit.yPt, pressure.coerceIn(0.05f, 1f)))
    }

    private fun eraseAt(x: Float, y: Float) {
        val hit = mapToPage(x, y) ?: return
        eraserPage = hit.page
        eraserX = hit.xPt
        eraserY = hit.yPt
        val changed = session?.eraseAt(hit.page, hit.xPt, hit.yPt, eraserWidthPt / 2f, eraserPixel) ?: false
        if (changed) invalidate()
        else invalidate()
    }

    private fun finishPenGesture(
        cancelled: Boolean,
        endX: Float = Float.NaN,
        endY: Float = Float.NaN,
    ) {
        val stroke = liveStroke
        if (stroke != null && !cancelled && stroke.points.isNotEmpty()) {
            if (stabilizerStrength > 0f && !endX.isNaN() && !endY.isNaN()) {
                // The filtered point trails the pen; land the stroke exactly where it was lifted.
                val hit = mapToPage(stroke.page, endX, endY)
                val last = stroke.points.last()
                if (hypot(hit.xPt - last.x, hit.yPt - last.y) > 0.05f) {
                    stroke.add(InkPoint(hit.xPt, hit.yPt, last.pressure))
                }
            }
            session?.addStroke(stroke)
        }
        liveStroke = null
        inkStabilizer.reset()
        if (erasing) {
            session?.endErase()
            erasing = false
        }
        if (tool == PenTool.LASSO) finishLassoOrMove(cancelled)
        eraserPage = -1
        eraserX = Float.NaN
        eraserY = Float.NaN
        invalidate()
    }

    // ---------------------------------------------------------------- lasso

    private fun beginLassoOrMove(x: Float, y: Float) {
        if (beginSelectionGesture(x, y)) return
        val hit = mapToPage(x, y) ?: return
        selectedStrokes = emptyList()
        selectionPage = hit.page
        lassoPage = hit.page
        lassoPoints = mutableListOf(InkPoint(hit.xPt, hit.yPt, 1f))
    }

    private fun updateLassoOrMove(x: Float, y: Float) {
        if (movingSelection || scalingSelection) {
            updateTransformGesture(x, y)
            return
        }
        val page = lassoPage
        if (page < 0) return
        val points = lassoPoints ?: return
        val hit = mapToPage(page, x, y)
        val last = points.lastOrNull()
        if (last == null || hypot(hit.xPt - last.x, hit.yPt - last.y) > 0.8f) {
            points.add(InkPoint(hit.xPt, hit.yPt, 1f))
            invalidate()
        }
    }

    private fun finishLassoOrMove(cancelled: Boolean) {
        if (movingSelection || scalingSelection) {
            finishTransformGesture(cancelled)
            return
        }
        val points = lassoPoints
        lassoPoints = null
        if (!cancelled && points != null && points.size >= 3) {
            selectByLasso(points)
        }
        invalidate()
    }

    private fun selectByLasso(points: List<InkPoint>) {
        val page = lassoPage
        if (page < 0 || sizes.isEmpty()) return
        val all = session?.strokes ?: return
        val hits = all.filter { stroke ->
            stroke.page == page && stroke.points.any { pointInPolygon(it.x, it.y, points) }
        }
        selectedStrokes = hits
        selectionPage = if (hits.isEmpty()) -1 else page
    }

    private fun pointInPolygon(x: Float, y: Float, polygon: List<InkPoint>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y
            if ((yi > y) != (yj > y)) {
                val denominator = yj - yi
                if (abs(denominator) > 0.00001f) {
                    val crossX = (xj - xi) * (y - yi) / denominator + xi
                    if (x < crossX) inside = !inside
                }
            }
            j = i
        }
        return inside
    }

    private fun handleFinger(event: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_DOWN && now - lastPenEventTime < PALM_WINDOW_MS) {
            return true
        }
        if (movingSelection || scalingSelection) {
            val index = event.findPointerIndex(transformPointerId)
            if (index < 0) return true
            val x = event.getX(index)
            val y = event.getY(index)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updateTransformGesture(x, y)
                MotionEvent.ACTION_UP -> finishTransformGesture(false)
                MotionEvent.ACTION_CANCEL -> finishTransformGesture(true)
            }
            return true
        }
        if (tool == PenTool.LASSO && event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.pointerCount == 1 && selectedStrokes.isNotEmpty()
        ) {
            if (beginSelectionGesture(event.x, event.y)) {
                transformPointerId = event.getPointerId(0)
                abortFling()
                dragMode = DragMode.NONE
                return true
            }
        }
        scaleDetector.onTouchEvent(event)
        if (dragMode == DragMode.PINCH) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                // Continue with the finger that is still down, without a positional jump.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                downX = lastX
                downY = lastY
                dragMode = DragMode.UNDECIDED
                scheduleViewportReport()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                dragMode = DragMode.NONE
            }
            return true
        }

        val pointerIndex = if (activePointerId == MotionEvent.INVALID_POINTER_ID) 0
        else event.findPointerIndex(activePointerId).coerceAtLeast(0)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                abortFling()
                activePointerId = event.getPointerId(0)
                downX = x
                downY = y
                lastX = x
                lastY = y
                dragMode = DragMode.UNDECIDED
                velocity.clear()
                velocity.addMovement(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                abortFling()
                dragMode = DragMode.PINCH
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                if (dragMode == DragMode.UNDECIDED) {
                    val dx = x - downX
                    val dy = y - downY
                    if (hypot(dx, dy) > touchSlop) {
                        dragMode = if (maxPan() > 0f && abs(dx) > abs(dy) * 1.25f) {
                            DragMode.HORIZONTAL
                        } else {
                            DragMode.VERTICAL
                        }
                    }
                }
                when (dragMode) {
                    DragMode.VERTICAL -> {
                        scrollY = (scrollY - (y - lastY) * scrollSpeed).coerceIn(0f, maxScroll())
                        scheduleViewportReport()
                        invalidate()
                    }

                    DragMode.HORIZONTAL -> {
                        panX = (panX + (x - lastX) * scrollSpeed).coerceIn(-maxPan(), maxPan())
                        invalidate()
                    }

                    else -> Unit
                }
                lastX = x
                lastY = y
                velocity.addMovement(event)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.actionIndex != pointerIndex) {
                    // A secondary finger lifted; keep tracking the original one.
                    lastX = x
                    lastY = y
                }
            }

            MotionEvent.ACTION_UP -> {
                val tap = dragMode == DragMode.UNDECIDED &&
                    hypot(x - downX, y - downY) < touchSlop
                if (dragMode == DragMode.VERTICAL) {
                    velocity.computeCurrentVelocity(1000)
                    val vy = velocity.yVelocity
                    if (abs(vy) > 400f) fling(vy * scrollSpeed)
                }
                if (tap) handleTap(x, y, event.eventTime)
                activePointerId = MotionEvent.INVALID_POINTER_ID
                dragMode = DragMode.NONE
                reportViewport()
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                dragMode = DragMode.NONE
                reportViewport()
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float, time: Long) {
        val isDouble = time - lastTapTime < 320L &&
            hypot(x - lastTapX, y - lastTapY) < 110f * density
        if (isDouble) {
            lastTapTime = 0L
            val target = if (zoom < 1.7f) (zoom * 2f).coerceAtMost(MAX_ZOOM) else 1f
            zoomAround(x, y, target)
        } else {
            lastTapTime = time
            lastTapX = x
            lastTapY = y
        }
    }

    private fun fling(velocityY: Float) {
        scroller.fling(
            0, scrollY.toInt(),
            0, (-velocityY).toInt(),
            0, 0,
            0, maxScroll().toInt()
        )
        postInvalidateOnAnimation()
    }

    private fun abortFling() {
        if (!scroller.isFinished) scroller.abortAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            scheduleViewportReport()
            postInvalidateOnAnimation()
        }
    }

    private fun zoomAround(focusX: Float, focusY: Float, targetZoom: Float) {
        if (sizes.isEmpty()) {
            zoom = targetZoom
            return
        }
        val oldScale = scale()
        val contentPt = (scrollY + focusY - topInset) / oldScale
        val page = pageAtContentPt(contentPt)
        val pageSize = sizes[page]
        val oldLeft = (width - pageSize.width * oldScale) / 2f + panX
        val docX = (focusX - oldLeft) / oldScale
        val newScale = density * targetZoom
        zoom = targetZoom
        panX = focusX - docX * newScale - (width - pageSize.width * newScale) / 2f
        clampPan()
        scrollY = (contentPt * newScale - (focusY - topInset)).coerceIn(0f, maxScroll())
        invalidate()
        scheduleViewportReport()
    }

    // ---------------------------------------------------------------- viewport

    private fun scheduleViewportReport() {
        removeCallbacks(viewportReporter)
        postDelayed(viewportReporter, VIEWPORT_REPORT_MS)
    }

    private fun reportViewport() {
        removeCallbacks(viewportReporter)
        if (sizes.isEmpty() || scale() <= 0f) return
        val contentPt = scrollY / scale()
        val page = pageAtContentPt(contentPt).coerceIn(0, sizes.size - 1)
        val offset = (contentPt - pageTopPt(page)).coerceAtLeast(0f)
        val viewport = Viewport(page, offset, zoom)
        if (viewport != lastViewport) {
            lastViewport = viewport
            onViewportChanged?.invoke(viewport)
        }
    }

    fun currentViewport(): Viewport? {
        if (sizes.isEmpty()) return null
        val contentPt = scrollY / scale()
        val page = pageAtContentPt(contentPt).coerceIn(0, sizes.size - 1)
        return Viewport(page, (contentPt - pageTopPt(page)).coerceAtLeast(0f), zoom)
    }

    fun pageCount(): Int = sizes.size
    fun currentZoom(): Float = zoom

    override fun onDetachedFromWindow() {
        reportViewport()
        closeDocument()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            // Home / recents / screen off: keep the reading position and push pending ink to disk.
            reportViewport()
            session?.flush()
        }
    }
}
