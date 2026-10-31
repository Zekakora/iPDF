package com.doge.ipdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.ParcelFileDescriptor
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.util.Log
import android.util.LruCache
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.io.File
import java.util.UUID
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

data class ScrollbarState(
    val visible: Boolean = false,
    val dragging: Boolean = false,
    val page: Int = 0,
    val pageCount: Int = 0,
    val fraction: Float = 0f,
    val thumbFraction: Float = 1f,
)

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
        private const val TILE_SIZE_PX = 512
        private const val BASE_MAX_DIM_PX = 1280
        private const val PAGE_GAP_PT = 10f
        private const val VIEWPORT_REPORT_MS = 250L
        private const val SCROLLBAR_HIDE_MS = 1700L

        /** Below this lift speed a drag stops where the finger left it. */
        private const val FLING_MIN_VELOCITY = 400f

        private const val TOUCH_TAG = "iPdfTouch"
        private const val OPEN_TAG = "iPdfOpen"

        /**
         * Tiles are requested from the draw pass, but only a few per frame: the renderer is a single
         * PDF thread, so queueing a whole screen at once only makes every tile arrive later. Tiles
         * that are no longer visible when their turn comes are skipped instead of rendered.
         */
        private const val MAX_TILE_REQUESTS_PER_FRAME = 6
        private const val MAX_PENDING_TILES = 32
        private const val PRIORITY_BASE = 0
        private const val PRIORITY_TILE = 1

        private const val BASE_CACHE_PAGES = 4
    }

    var onViewportChanged: ((Viewport) -> Unit)? = null
    var onScrollbarChanged: ((ScrollbarState) -> Unit)? = null
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

    /**
     * Eraser tip size as a page-relative preset value (see [BrushPreset]); it is converted to the
     * page's points with [brushWidthToPagePt] whenever it is used, so the tip covers the same part
     * of an A4 page and of a wide slide.
     */
    var eraserWidth: Float = 16f
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

    /**
     * All finger/stylus ownership decisions live in this pure state machine. The view only converts
     * MotionEvents into frames and applies the resulting effects, which keeps every threshold in one
     * place and makes the tricky contact add/remove/cancel sequences testable off-device.
     */
    private val inputArbiter = TouchArbiter(
        TouchArbiter.Config(
            panArmPx = max(touchSlop * 2f, 18f * density),
            tapSlopPx = touchSlop.toFloat(),
            pinchArmSpanPx = max(touchSlop * 2.5f, 24f * density),
            pinchArmContactPx = max(touchSlop * 1.5f, 12f * density),
            pinchMinSpanPx = 20f * density,
            // Contact geometry is optional: panels that report no meaningful major/minor simply
            // never reach these limits. On panels that do, a palm-sized ellipse is rejected whole.
            palmMajorPx = 96f * density,
            palmMinorPx = 72f * density,
        )
    )

    private var session: ReaderSession? = null
    private var openedFile: File? = null
    private var descriptor: ParcelFileDescriptor? = null
    @Volatile
    private var renderer: PdfRenderer? = null
    private val sizes = ArrayList<PageSize>()

    // PDF pages are rendered in two layers. A coarse full-page "base" bitmap is drawn first, so a
    // zoom change, a pan or a slow render can never expose the bare white page; visible tiles are
    // painted on top and progressively sharpen it. A tile is rendered at the nearest power-of-two
    // zoom bucket and then lightly scaled for intermediate pinch values.
    private class RenderTask(val priority: Int, private val block: () -> Unit) : Runnable {
        override fun run() = block()
    }

    private fun renderPriority(task: Runnable): Int =
        (task as? RenderTask)?.priority ?: PRIORITY_TILE

    // Base renders jump ahead of queued tile renders: showing a soft page quickly matters more than
    // finishing an off-screen tile.
    private val renderExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(
            16,
            Comparator<Runnable> { first, second ->
                renderPriority(first).compareTo(renderPriority(second))
            },
        ),
        ThreadFactory { runnable -> Thread(runnable, "ipdf-page-render").apply { isDaemon = true } },
    )
    private val renderLock = Any()
    @Volatile
    private var renderGeneration = 0
    private data class TileKey(
        val page: Int,
        val x: Int,
        val y: Int,
        val zoomBucket: Float,
    )

    private class TileRequest(val key: TileKey, val distanceSq: Float)

    /** Result of a background render; [skipped] means the work became unnecessary, not that it failed. */
    private class RenderResult(val bitmap: Bitmap?, val skipped: Boolean = false)

    private val pendingTiles = HashSet<TileKey>()
    private val failedTiles = HashSet<TileKey>()

    // One coarse full-page bitmap per recently visible page. It lives outside the tile LRU so cache
    // pressure can never evict the layer that guarantees full page coverage. Access order lets us
    // keep recently seen pages, and pages currently on screen are pinned by [visiblePages].
    private val baseCache = LinkedHashMap<Int, Bitmap>(BASE_CACHE_PAGES, 0.75f, true)
    private val visiblePages = HashSet<Int>()
    /** Immutable per-frame snapshot read by the render thread to discard obsolete base work. */
    @Volatile
    private var wantedBasePages: Set<Int> = emptySet()
    private val pendingBases = HashSet<Int>()
    private val failedBases = HashSet<Int>()

    /**
     * Tiles wanted by the current frame. A render task whose tile is no longer in this set is
     * dropped without rendering, so a fast pan cannot leave a long useless queue behind.
     */
    @Volatile
    private var wantedTiles: Set<TileKey> = emptySet()

    private var zoom = 1f
    var zoomSpeed: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 2f)
        }
    var scrollSpeed: Float = 1f
        set(value) {
            field = value.coerceIn(0.5f, 2f)
        }
    private val pinching: Boolean get() = inputArbiter.pinching
    // Input diagnostics are a development tool only; even setting the log tag cannot enable them
    // in a release build.
    private val touchLogging = BuildConfig.DEBUG && Log.isLoggable(TOUCH_TAG, Log.DEBUG)
    private var scrollY = 0f
    private var panX = 0f
    private var topInset = 0
    private var pendingRestore: Viewport? = null
    private var scrollbarVisible = false
    private var scrollbarDragging = false
    private var manualScrollStartPage = -1
    private var lastScrollbarState = ScrollbarState()
    private val hideScrollbarRunnable = Runnable {
        if (!scrollbarDragging) {
            scrollbarVisible = false
            emitScrollbarState()
        }
    }

    // A 512 px ARGB tile is 1 MiB, so a quarter of the heap keeps ~2.5 screens of tiles on a
    // 256 MiB device. The old eighth-of-heap budget could only hold ~14 tiles of the old 768 px
    // tiles (2.25 MiB each), barely one screen, which made every bucket change evict its fallback.
    private val maxCacheKb = (Runtime.getRuntime().maxMemory() / 1024L / 4L).toInt()
        .coerceIn(24 * 1024, 96 * 1024)
    private val tileCache = object : LruCache<TileKey, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.byteCount / 1024
    }

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val emptyStatePaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private var liveStroke: Stroke? = null
    private var eraserX = Float.NaN
    private var eraserY = Float.NaN
    private var eraserPage = -1
    /** Diameter of the eraser tip on its page, in that page's points. */
    private var eraserTipPt = 16f
    private var erasing = false

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

    private enum class PanAxis { NONE, VERTICAL, HORIZONTAL }

    private var panAxis = PanAxis.NONE
    /** Zoom when the current pinch was armed; the arbiter emits a scale relative to this point. */
    private var pinchBaseZoom = 1f
    /**
     * Tile bucket at the instant a pinch is armed. While the fingers are down the tile pyramid is
     * frozen on this bucket: existing full-page coverage is scaled with the gesture, but no new
     * tile grid is requested until the zoom settles. Chasing every intermediate bucket with the
     * single PDF thread evicts the very fallbacks that keep the page visible.
     */
    private var frozenPinchBucket = 1f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var fingerDownScrollY = 0f
    private var fingerDragExtent = 0f

    private val viewportReporter = Runnable { reportViewport() }
    private var lastViewport: Viewport? = null

    fun setTopInset(px: Int) {
        if (px == topInset) return
        topInset = px
        scrollY = scrollY.coerceIn(0f, maxScroll())
        emitScrollbarState()
        invalidate()
    }

    /**
     * Opens [source] for rendering. Returns false when the renderer could not be created (the
     * document is encrypted, truncated or gone); the view is left empty so the caller can report it
     * instead of showing a permanently dark page.
     */
    fun openDocument(
        source: File,
        restore: Viewport?,
        cachedSizes: List<PageSize> = emptyList(),
    ): Boolean {
        if (openedFile == source && renderer != null) return true
        // A rebuild (tab switch, re-import) attaches the new session before the view is replaced.
        // closeDocument() reports the old viewport to that new session, which would overwrite the
        // position and dimension keys it just restored, so park those callbacks meanwhile.
        val notifyViewport = onViewportChanged
        val notifyScrollbar = onScrollbarChanged
        onViewportChanged = null
        onScrollbarChanged = null
        try {
            closeDocument()
            val pfd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            val pdf = try {
                PdfRenderer(pfd)
            } catch (t: Throwable) {
                // The previous document is already closed at this point; never keep a stale or
                // half-opened renderer around, the page would show nothing at all.
                pfd.close()
                lastOpenError = t.message
                Log.e(OPEN_TAG, "PdfRenderer failed for ${source.name}: ${t.message}", t)
                return false
            }
            lastOpenError = null
            activateDocument(source, pfd, pdf, restore, cachedSizes)
            return true
        } finally {
            onViewportChanged = notifyViewport
            onScrollbarChanged = notifyScrollbar
        }
    }

    /** Most recent failure from [openDocument]; null after a successful open. */
    var lastOpenError: String? = null
        private set

    private fun activateDocument(
        source: File,
        pfd: ParcelFileDescriptor,
        pdf: PdfRenderer,
        restore: Viewport?,
        cachedSizes: List<PageSize>,
    ) {
        descriptor = pfd
        renderer = pdf
        openedFile = source
        zoom = 1f
        scrollY = 0f
        panX = 0f
        scrollbarVisible = false
        scrollbarDragging = false
        manualScrollStartPage = -1
        removeCallbacks(hideScrollbarRunnable)
        emitScrollbarState()
        if (cachedSizes.size == pdf.pageCount) {
            sizes.addAll(cachedSizes)
        } else {
            // Measure directly: openPage() inside the loop must not be allowed to leak a page
            // when one of them fails, or every later openPage call throws and the view stays dark.
            try {
                for (index in 0 until pdf.pageCount) {
                    val page = pdf.openPage(index)
                    try {
                        sizes.add(PageSize(page.width.toFloat(), page.height.toFloat()))
                    } finally {
                        page.close()
                    }
                }
                session?.storePageSizes(sizes.toList())
            } catch (_: Throwable) {
                // Keep whatever pages could be measured; rendering will show placeholders.
            }
        }
        if (sizes.isNotEmpty()) {
            // Start the coarse page before the first layout pass so the first frame is not white.
            val page = (restore?.page ?: 0).coerceIn(0, sizes.lastIndex)
            wantedBasePages = setOf(page)
            requestBase(page)
        }
        pendingRestore = restore
        requestLayout()
        invalidate()
        applyPendingRestore()
    }

    fun closeDocument() {
        reportViewport()
        tileCache.evictAll()
        baseCache.clear()
        visiblePages.clear()
        wantedBasePages = emptySet()
        pendingTiles.clear()
        failedTiles.clear()
        pendingBases.clear()
        failedBases.clear()
        wantedTiles = emptySet()
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
        inputArbiter.reset()
        panAxis = PanAxis.NONE
        velocity.clear()
        lastTapTime = 0L
        zoom = 1f
        scrollY = 0f
        panX = 0f
        pendingRestore = null
        scrollbarVisible = false
        scrollbarDragging = false
        manualScrollStartPage = -1
        removeCallbacks(hideScrollbarRunnable)
        emitScrollbarState()
        invalidate()
        if (oldRenderer != null || oldDescriptor != null) {
            // Close ahead of any queued tile; the generation check makes their results harmless.
            submitRender(PRIORITY_BASE) {
                synchronized(renderLock) {
                    runCatching { oldRenderer?.close() }
                    runCatching { oldDescriptor?.close() }
                }
            }
        }
    }

    fun attachSession(value: ReaderSession) {
        session = value
        autoMarkerArmed = true
        invalidate()
    }

    /** Session currently attached; used to avoid re-opening the same document on every update. */
    var boundSession: ReaderSession? = null
    /** Bumped by the reader when the pristine copy is replaced, forcing a re-open. */
    var boundEpoch: Int = -1

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
        emitScrollbarState()
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
        emitScrollbarState()
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

    private fun readingPage(): Int {
        if (sizes.isEmpty()) return 0
        return pageAtContentPt((scrollY / scale()).coerceAtLeast(0f))
            .coerceIn(0, sizes.lastIndex)
    }

    private fun emitScrollbarState() {
        val max = maxScroll()
        val canScroll = sizes.isNotEmpty() && max > 0.5f
        val state = if (!canScroll || (!scrollbarVisible && !scrollbarDragging)) {
            ScrollbarState(pageCount = sizes.size)
        } else {
            ScrollbarState(
                visible = scrollbarVisible,
                dragging = scrollbarDragging,
                page = readingPage(),
                pageCount = sizes.size,
                fraction = (scrollY / max).coerceIn(0f, 1f),
                thumbFraction = (viewportHeight() / contentHeightPx()).coerceIn(0.045f, 1f),
            )
        }
        if (state != lastScrollbarState) {
            lastScrollbarState = state
            onScrollbarChanged?.invoke(state)
        }
    }

    private fun revealScrollbarAfterPageCrossing() {
        if (manualScrollStartPage < 0 || sizes.isEmpty()) return
        if (readingPage() != manualScrollStartPage) {
            scrollbarVisible = true
            emitScrollbarState()
            if (!scrollbarDragging) {
                removeCallbacks(hideScrollbarRunnable)
                postDelayed(hideScrollbarRunnable, SCROLLBAR_HIDE_MS)
            }
        }
    }

    private fun scheduleScrollbarHide() {
        if (!scrollbarVisible || scrollbarDragging) return
        removeCallbacks(hideScrollbarRunnable)
        postDelayed(hideScrollbarRunnable, SCROLLBAR_HIDE_MS)
    }

    /** Starts a drag from the overlay scrollbar and keeps it visible until the drag ends. */
    fun beginScrollbarDrag() {
        if (sizes.isEmpty() || maxScroll() <= 0.5f) return
        abortFling()
        removeCallbacks(hideScrollbarRunnable)
        scrollbarVisible = true
        scrollbarDragging = true
        emitScrollbarState()
    }

    /** Moves the document to a normalized scrollbar position. */
    fun dragScrollbarTo(fraction: Float) {
        if (sizes.isEmpty() || maxScroll() <= 0.5f) return
        scrollY = (maxScroll() * fraction.coerceIn(0f, 1f)).coerceIn(0f, maxScroll())
        scrollbarVisible = true
        scheduleViewportReport()
        emitScrollbarState()
        invalidate()
    }

    fun endScrollbarDrag() {
        if (!scrollbarDragging) return
        scrollbarDragging = false
        emitScrollbarState()
        scheduleScrollbarHide()
        reportViewport()
    }

    /** Keeps the transient page indicator alive while its page-jump dialog is being edited. */
    fun pauseScrollbarHide() {
        if (!scrollbarVisible) return
        removeCallbacks(hideScrollbarRunnable)
        emitScrollbarState()
    }

    fun resumeScrollbarHide() {
        scheduleScrollbarHide()
    }

    /** Jumps to the top of a one-based page selected in the transient page indicator. */
    fun jumpToPage(pageIndex: Int) {
        if (sizes.isEmpty()) return
        abortFling()
        val page = pageIndex.coerceIn(0, sizes.lastIndex)
        // scrollY is measured in document coordinates. The top inset is only applied while
        // drawing the page below the reader bar; subtracting it here leaves the viewport in the
        // previous page, so entering 52 used to land/read as page 51.
        scrollY = (pageTopPt(page) * scale()).coerceIn(0f, maxScroll())
        scrollbarVisible = maxScroll() > 0.5f
        emitScrollbarState()
        scheduleScrollbarHide()
        scheduleViewportReport()
        invalidate()
    }

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
        val drawStartedAt = System.nanoTime()
        super.onDraw(canvas)
        val scale = scale()
        canvas.save()
        if (topInset > 0) canvas.clipRect(0f, topInset.toFloat(), width.toFloat(), height.toFloat())
        canvas.drawColor(Color.rgb(31, 33, 38))

        if (sizes.isEmpty()) {
            wantedTiles = emptySet()
            visiblePages.clear()
            canvas.restore()
            return
        }

        // Freeze the tile pyramid while a pinch is in progress. Zooming out grows the visible area
        // quadratically; requesting a new grid every frame floods the single PDF thread, evicts the
        // fallbacks that keep the page covered and leaves white tile-shaped holes. The existing
        // bucket and the full-page base are scaled with the gesture until the fingers settle.
        val pinchingNow = pinching
        val bucket = if (pinchingNow) frozenPinchBucket else renderZoomBucket()
        val wanted = HashSet<TileKey>()
        val missing = ArrayList<TileRequest>()
        val missingBases = HashSet<Int>()
        visiblePages.clear()
        for (index in sizes.indices) {
            val size = sizes[index]
            val drawWidth = size.width * scale
            val drawHeight = size.height * scale
            val top = pageTopPt(index) * scale - scrollY + topInset
            if (top > height || top + drawHeight < topInset) continue
            visiblePages.add(index)
            val left = (width - drawWidth) / 2f + panX
            drawPageTiles(
                canvas = canvas,
                pageIndex = index,
                left = left,
                top = top,
                drawWidth = drawWidth,
                drawHeight = drawHeight,
                currentScale = scale,
                bucket = bucket,
                allowNewTiles = !pinchingNow,
                wanted = wanted,
                missing = missing,
                missingBases = missingBases,
            )
            drawInk(canvas, index, left, top, drawWidth, drawHeight, scale)
        }
        if (visiblePages.isEmpty()) {
            // A renderer with measured sizes should always have a visible page; if not, the layout
            // or geometry is unusable and showing the bare dark surface would look like a black
            // page. Say what happened instead so the problem is diagnosable.
            drawEmptyStateMessage(canvas)
        }
        trimBaseCache()
        // Failures are scoped to what is currently visible: a transient allocation failure is
        // forgotten once the page/tile leaves the viewport, so re-entering it retries instead of
        // leaving a permanently uncovered region.
        failedBases.retainAll(visiblePages)
        if (!pinchingNow) failedTiles.retainAll(wanted)
        wantedBasePages = HashSet(visiblePages)
        for (page in missingBases) requestBase(page)
        if (pinchingNow) {
            // No new tile work until the pinch ends; in-flight tasks are skipped by wantedTiles.
            wantedTiles = emptySet()
        } else {
            wantedTiles = wanted
            requestMissingTiles(missing)
        }
        canvas.restore()
        if (touchLogging) {
            val took = (System.nanoTime() - drawStartedAt) / 1_000_000
            if (pinching || took >= 8) {
                Log.d(
                    TOUCH_TAG,
                    "draw zoom=%.3f pan=%.0f scroll=%.0f took=${took}ms".format(zoom, panX, scrollY),
                )
            }
        }
    }

    /** Centred hint drawn over the dark background when no page could be laid out. */
    private fun drawEmptyStateMessage(canvas: Canvas) {
        val message = lastOpenError?.let { "This PDF could not be opened\n$it" }
            ?: "This PDF could not be displayed"
        emptyStatePaint.textAlign = Paint.Align.CENTER
        emptyStatePaint.color = 0xFFB8BCC4.toInt()
        emptyStatePaint.textSize = 15f * density
        val lines = message.split('\n')
        val lineHeight = emptyStatePaint.textSize * 1.35f
        var y = height / 2f - (lines.size - 1) * lineHeight / 2f
        lines.forEach { line ->
            canvas.drawText(line, width / 2f, y, emptyStatePaint)
            y += lineHeight
        }
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
        bucket: Float,
        allowNewTiles: Boolean,
        wanted: MutableSet<TileKey>,
        missing: MutableList<TileRequest>,
        missingBases: MutableSet<Int>,
    ) {
        pagePaint.color = Color.WHITE
        canvas.drawRect(left, top, left + drawWidth, top + drawHeight, pagePaint)

        // The base layer has already been rendered at a fixed, modest resolution. It is what turns
        // a missing tile from a white block into a temporarily soft but readable patch.
        val base = baseCache[pageIndex]
        if (base != null) {
            canvas.drawBitmap(base, null, RectF(left, top, left + drawWidth, top + drawHeight), pagePaint)
        } else {
            missingBases.add(pageIndex)
        }

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

        val viewportCenterX = width * 0.5f
        val viewportCenterY = height * 0.5f

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
                wanted.add(key)

                val bitmap = tileCache.get(key)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, RectF(tileLeft, tileTop, tileRight, tileBottom), pagePaint)
                } else if (allowNewTiles) {
                    // Any older bucket still in the cache improves on the base layer; it is never
                    // the only content on screen, so partial coverage is harmless now.
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
                    if (key !in failedTiles) {
                        val centerX = (tileLeft + tileRight) * 0.5f
                        val centerY = (tileTop + tileBottom) * 0.5f
                        val dx = centerX - viewportCenterX
                        val dy = centerY - viewportCenterY
                        missing.add(TileRequest(key, dx * dx + dy * dy))
                    }
                }
            }
        }
    }

    /**
     * Requests only the most useful missing tiles for this frame: nearest to the viewport center
     * first, a few at a time. Further tiles are requested as earlier ones arrive, which keeps the
     * single PDF render thread focused on what the user is actually looking at.
     */
    private fun requestMissingTiles(missing: MutableList<TileRequest>) {
        if (missing.isEmpty()) return
        missing.sortBy { it.distanceSq }
        var budget = MAX_TILE_REQUESTS_PER_FRAME
        for (request in missing) {
            if (budget <= 0) break
            if (pendingTiles.size >= MAX_PENDING_TILES) break
            if (requestTile(request.key)) budget--
        }
    }

    private fun putBase(pageIndex: Int, bitmap: Bitmap) {
        baseCache[pageIndex] = bitmap
        trimBaseCache()
    }

    /**
     * Keeps the page bases proportional to the current viewport. Pages on screen are pinned even
     * when the cache is over budget; eviction starts with the least recently used off-screen page.
     */
    private fun trimBaseCache() {
        if (baseCache.size <= BASE_CACHE_PAGES) return
        val iterator = baseCache.entries.iterator()
        while (baseCache.size > BASE_CACHE_PAGES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in visiblePages) iterator.remove()
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

    private fun requestTile(key: TileKey): Boolean {
        if (key.page !in sizes.indices) return false
        if (key in failedTiles) return false
        if (!pendingTiles.add(key)) return false
        val pdf = renderer
        if (pdf == null || renderExecutor.isShutdown) {
            pendingTiles.remove(key)
            return false
        }
        val generation = renderGeneration
        submitRender(PRIORITY_TILE) {
            val result = synchronized(renderLock) {
                if (generation != renderGeneration || renderer !== pdf || key !in wantedTiles) {
                    // Panning/zooming moved on while this task was queued: skip it instead of
                    // spending 20-40 ms on a tile that nobody will see.
                    RenderResult(bitmap = null, skipped = true)
                } else {
                    try {
                        val size = sizes[key.page]
                        val tileScale = density * key.zoomBucket
                        val tilePageSize = TILE_SIZE_PX / tileScale
                        val leftPt = key.x * tilePageSize
                        val topPt = key.y * tilePageSize
                        val widthPt = (size.width - leftPt).coerceAtMost(tilePageSize)
                        val heightPt = (size.height - topPt).coerceAtMost(tilePageSize)
                        if (widthPt <= 0f || heightPt <= 0f) {
                            RenderResult(bitmap = null, skipped = true)
                        } else {
                            val width = max(1, ceil(widthPt * tileScale).toInt())
                            val height = max(1, ceil(heightPt * tileScale).toInt())
                            // Map the tile's page rectangle to the full allocated bitmap. Using the
                            // nominal scale here would leave the ceil() rounding column/row white at
                            // the right/bottom edge, which shows as a seam between tiles.
                            val tileSx = width / widthPt
                            val tileSy = height / heightPt
                            pdf.openPage(key.page).use { page ->
                                val value = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                value.eraseColor(Color.WHITE)
                                val transform = Matrix().apply {
                                    setScale(tileSx, tileSy)
                                    postTranslate(-leftPt * tileSx, -topPt * tileSy)
                                }
                                page.render(value, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                RenderResult(value)
                            }
                        }
                    } catch (_: Throwable) {
                        RenderResult(bitmap = null)
                    }
                }
            }
            post {
                pendingTiles.remove(key)
                val bitmap = result.bitmap
                if (bitmap != null) {
                    // Do not let work for a viewport that has already moved on evict tiles that are
                    // still covering the screen. Obsolete results are dropped instead of cached.
                    if (generation == renderGeneration && renderer === pdf && key in wantedTiles) {
                        tileCache.put(key, bitmap)
                    }
                } else if (!result.skipped && generation == renderGeneration) {
                    failedTiles.add(key)
                }
                invalidate()
            }
        }
        return true
    }

    /**
     * Renders the coarse whole-page layer. It is deliberately small (1280 px on the long edge) and
     * kept outside the tile cache so it can always cover the page while sharp tiles are missing.
     */
    private fun requestBase(pageIndex: Int) {
        if (pageIndex !in sizes.indices) return
        if (pageIndex in failedBases) return
        if (!pendingBases.add(pageIndex)) return
        val pdf = renderer
        if (pdf == null || renderExecutor.isShutdown) {
            pendingBases.remove(pageIndex)
            return
        }
        val generation = renderGeneration
        val size = sizes[pageIndex]
        submitRender(PRIORITY_BASE) {
            val result = synchronized(renderLock) {
                if (generation != renderGeneration || renderer !== pdf ||
                    pageIndex !in wantedBasePages
                ) {
                    RenderResult(bitmap = null, skipped = true)
                } else {
                    try {
                        val baseScale = (BASE_MAX_DIM_PX / max(size.width, size.height)).coerceAtLeast(0.01f)
                        val width = max(1, (size.width * baseScale).roundToInt())
                        val height = max(1, (size.height * baseScale).roundToInt())
                        val value = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        value.eraseColor(Color.WHITE)
                        pdf.openPage(pageIndex).use { page ->
                            val transform = Matrix().apply {
                                setScale(width / size.width, height / size.height)
                            }
                            page.render(value, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                        RenderResult(value)
                    } catch (_: Throwable) {
                        RenderResult(bitmap = null)
                    }
                }
            }
            post {
                pendingBases.remove(pageIndex)
                val bitmap = result.bitmap
                if (bitmap != null) {
                    if (generation == renderGeneration && renderer === pdf) putBase(pageIndex, bitmap)
                } else if (!result.skipped && generation == renderGeneration) {
                    failedBases.add(pageIndex)
                }
                invalidate()
            }
        }
    }

    private fun submitRender(priority: Int, block: () -> Unit) {
        if (renderExecutor.isShutdown) return
        try {
            renderExecutor.execute(RenderTask(priority, block))
        } catch (_: Throwable) {
            // The executor can be shut down between the check and the submission.
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
        // At high zoom only a small part of the page is on screen, but every stroke on it used to be
        // re-tessellated at that scale on every frame — hundreds of paths scaled by 20x, which is
        // enough to miss frames exactly when the page is being pinched. Skip the ones outside view.
        val visibleLeft = -left / scale - 32f
        val visibleTop = (topInset - top) / scale - 32f
        val visibleRight = (width - left) / scale + 32f
        val visibleBottom = (height - top) / scale + 32f
        strokes.forEach { stroke ->
            if (stroke.page != pageIndex) return@forEach
            if (!stroke.bounds().intersects(visibleLeft, visibleTop, visibleRight, visibleBottom)) {
                return@forEach
            }
            drawStroke(canvas, stroke)
        }
        if (live != null && live.page == pageIndex) drawStroke(canvas, live)
        if (hasSelection) drawSelectionBounds(canvas, selection)
        if (hasLasso) drawLassoPath(canvas)
        if (eraserPage == pageIndex && !eraserX.isNaN()) {
            eraserPaint.color = Color.argb(70, 255, 255, 255)
            eraserPaint.strokeWidth = 1.5f
            canvas.drawCircle(eraserX, eraserY, eraserTipPt / 2f, eraserPaint)
            eraserPaint.color = Color.argb(220, 255, 255, 255)
            eraserPaint.strokeWidth = 0.8f
            canvas.drawCircle(eraserX, eraserY, eraserTipPt / 2f, eraserPaint)
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
        // drawInk has already scaled the canvas to page points, so the stored width is drawn as is.
        inkPaint.strokeWidth = (stroke.width * pressure).coerceAtLeast(0.4f)
        inkPaint.color = stroke.color
        // Keep screen rendering in normal source-over mode. Multiply makes translucent highlight
        // colours look muddy over dark text and is not consistently implemented by PDF viewers.
        inkPaint.xfermode = null
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
        val frame = inputFrame(event) ?: return true
        applyInputEffects(event, inputArbiter.onFrame(frame))

        // The stylus owns the stream whenever it is in the pointer list. This includes the event
        // that lifts it while fingers are still down: the drawing gesture ends, and the arbiter
        // keeps those fingers rejected until they all lift.
        val penIndex = frame.pointers.indexOfFirst {
            it.tool == TouchArbiter.PointerTool.STYLUS ||
                it.tool == TouchArbiter.PointerTool.ERASER
        }
        if (penIndex >= 0) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) lastTapTime = 0L
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_POINTER_UP
            ) {
                logTouch(event, "pen")
            }
            handleStylus(event, penIndex)
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A new touch always stops a fling. Whether it may then pan/zoom is the arbiter's job.
            abortFling()
            if (inputArbiter.blocked) {
                lastTapTime = 0L
                logTouch(event, "touch blocked")
            } else {
                logTouch(event, "finger down")
                claimDirectSelection(event)
            }
        }
        return true
    }

    private fun inputFrame(event: MotionEvent): TouchArbiter.Frame? {
        val count = event.pointerCount
        if (count <= 0) return null
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchArbiter.Action.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> TouchArbiter.Action.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> TouchArbiter.Action.MOVE
            MotionEvent.ACTION_POINTER_UP -> TouchArbiter.Action.POINTER_UP
            MotionEvent.ACTION_UP -> TouchArbiter.Action.UP
            MotionEvent.ACTION_CANCEL -> TouchArbiter.Action.CANCEL
            else -> return null
        }
        val pointers = ArrayList<TouchArbiter.Pointer>(count)
        for (index in 0 until count) {
            pointers += TouchArbiter.Pointer(
                id = event.getPointerId(index),
                tool = when (event.getToolType(index)) {
                    MotionEvent.TOOL_TYPE_FINGER -> TouchArbiter.PointerTool.FINGER
                    MotionEvent.TOOL_TYPE_STYLUS -> TouchArbiter.PointerTool.STYLUS
                    MotionEvent.TOOL_TYPE_ERASER -> TouchArbiter.PointerTool.ERASER
                    else -> TouchArbiter.PointerTool.OTHER
                },
                x = event.getX(index),
                y = event.getY(index),
                touchMajor = event.getTouchMajor(index),
                touchMinor = event.getTouchMinor(index),
            )
        }
        val actionIndex = event.actionIndex.coerceIn(0, count - 1)
        return TouchArbiter.Frame(
            action = action,
            actionPointerId = event.getPointerId(actionIndex),
            eventTime = event.eventTime,
            pointers = pointers,
        )
    }

    private fun applyInputEffects(
        event: MotionEvent,
        effects: List<TouchArbiter.Effect>,
    ) {
        for (effect in effects) {
            when (effect) {
                is TouchArbiter.Effect.PanStart -> startPan(event, effect)
                is TouchArbiter.Effect.Pan -> {
                    velocity.addMovement(event)
                    applyPan(effect.dx, effect.dy)
                }
                is TouchArbiter.Effect.PanEnd -> endPan(event, effect)
                TouchArbiter.Effect.PanCancel -> cancelPan()
                is TouchArbiter.Effect.PinchStart -> {
                    pinchBaseZoom = zoom
                    frozenPinchBucket = renderZoomBucket()
                    lastTapTime = 0L
                    if (touchLogging) {
                        Log.d(
                            TOUCH_TAG,
                            "pinch begin at=%.0f,%.0f zoom=%.3f".format(
                                effect.focusX,
                                effect.focusY,
                                zoom,
                            ),
                        )
                    }
                }
                is TouchArbiter.Effect.Pinch -> applyPinch(effect)
                TouchArbiter.Effect.PinchEnd -> {
                    scheduleViewportReport()
                    invalidate()
                    if (touchLogging) Log.d(TOUCH_TAG, "pinch end zoom=$zoom")
                }
                is TouchArbiter.Effect.Tap -> {
                    if (touchLogging) {
                        Log.d(TOUCH_TAG, "tap at=${effect.x.toInt()},${effect.y.toInt()}")
                    }
                    handleTap(effect.x, effect.y, effect.eventTime)
                }
                is TouchArbiter.Effect.DirectMove -> updateTransformGesture(effect.x, effect.y)
                is TouchArbiter.Effect.DirectEnd -> {
                    if (movingSelection || scalingSelection) {
                        finishTransformGesture(effect.cancelled)
                    }
                }
            }
        }
    }

    private fun startPan(event: MotionEvent, start: TouchArbiter.Effect.PanStart) {
        abortFling()
        lastTapTime = 0L
        panAxis = if (maxPan() > 0f && abs(start.totalDx) > abs(start.totalDy) * 1.25f) {
            PanAxis.HORIZONTAL
        } else {
            PanAxis.VERTICAL
        }
        // The movement that armed the pan is spent, not applied: rebasing here is what prevents the
        // page from jumping by a touch slop the instant the gesture becomes a drag.
        manualScrollStartPage = if (panAxis == PanAxis.VERTICAL) readingPage() else -1
        fingerDownScrollY = scrollY
        fingerDragExtent = 0f
        velocity.clear()
        velocity.addMovement(event)
        if (touchLogging) {
            Log.d(
                TOUCH_TAG,
                "pan start axis=$panAxis moved=%.0f,%.0f".format(start.totalDx, start.totalDy),
            )
        }
    }

    private fun applyPan(dx: Float, dy: Float) {
        when (panAxis) {
            PanAxis.VERTICAL -> {
                fingerDragExtent += abs(dy)
                scrollY = (scrollY - dy * scrollSpeed).coerceIn(0f, maxScroll())
                revealScrollbarAfterPageCrossing()
                scheduleViewportReport()
                invalidate()
            }
            PanAxis.HORIZONTAL -> {
                fingerDragExtent += abs(dx)
                panX = (panX + dx * scrollSpeed).coerceIn(-maxPan(), maxPan())
                invalidate()
            }
            PanAxis.NONE -> Unit
        }
    }

    private fun endPan(event: MotionEvent, end: TouchArbiter.Effect.PanEnd) {
        velocity.addMovement(event)
        var flingVelocity = 0f
        if (panAxis == PanAxis.VERTICAL) {
            velocity.computeCurrentVelocity(1000)
            flingVelocity = velocity.yVelocity
            if (abs(flingVelocity) > FLING_MIN_VELOCITY) fling(flingVelocity * scrollSpeed)
        }
        revealScrollbarAfterPageCrossing()
        if (scroller.isFinished) {
            manualScrollStartPage = -1
            scheduleScrollbarHide()
        }
        if (touchLogging) {
            Log.d(
                TOUCH_TAG,
                "finger up moved=${fingerDragExtent.toInt()}px " +
                    "scrolled=${(scrollY - fingerDownScrollY).toInt()}px " +
                    "fling=${flingVelocity.toInt()} at=${end.x.toInt()},${end.y.toInt()}",
            )
        }
        panAxis = PanAxis.NONE
        velocity.clear()
        reportViewport()
    }

    private fun cancelPan() {
        abortFling()
        panAxis = PanAxis.NONE
        velocity.clear()
        manualScrollStartPage = -1
        scheduleScrollbarHide()
        scheduleViewportReport()
    }

    private fun applyPinch(effect: TouchArbiter.Effect.Pinch) {
        if (effect.spanRatio <= 0f) return
        val adjusted = exp(ln(effect.spanRatio.toDouble()) * zoomSpeed.toDouble()).toFloat()
        val target = (pinchBaseZoom * adjusted).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(target - zoom) < 0.0005f) return
        zoomAround(effect.focusX, effect.focusY, target)
        if (touchLogging) {
            Log.d(
                TOUCH_TAG,
                "pinch ratio=%.3f target=%.3f zoom=%.3f".format(effect.spanRatio, target, zoom),
            )
        }
    }

    /**
     * Finger-driven lasso movement is a direct manipulation rather than a pan. It must be claimed
     * explicitly so it owns the single contact; a second finger or the stylus cancels and reverts it.
     */
    private fun claimDirectSelection(event: MotionEvent) {
        if (tool != PenTool.LASSO || selectedStrokes.isEmpty()) return
        if (inputArbiter.state != TouchArbiter.State.CANDIDATE) return
        val pointerId = event.getPointerId(0)
        if (!beginSelectionGesture(event.x, event.y)) return
        if (inputArbiter.claimDirect(pointerId)) {
            transformPointerId = pointerId
            lastTapTime = 0L
            manualScrollStartPage = -1
            logTouch(event, "selection drag")
        } else {
            finishTransformGesture(cancelled = true)
        }
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            handleStylusButtonState(event)
            val near = when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> true
                MotionEvent.ACTION_HOVER_EXIT -> false
                else -> null
            }
            if (near != null) {
                if (near) lastTapTime = 0L
                applyInputEffects(event, inputArbiter.onHover(near))
                if (touchLogging && event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    Log.d(
                        TOUCH_TAG,
                        "pen hover enter distance=${event.getAxisValue(MotionEvent.AXIS_DISTANCE)}",
                    )
                } else if (touchLogging && event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                    Log.d(TOUCH_TAG, "pen hover exit")
                }
            }
            return true
        }
        return super.onHoverEvent(event)
    }

    private fun logTouch(event: MotionEvent, decision: String) {
        if (!touchLogging) return
        val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        Log.d(
            TOUCH_TAG,
            "action=${event.actionMasked} pointers=${event.pointerCount} " +
                "tool=${event.getToolType(index)} size=${"%.2f".format(event.getSize(index))} " +
                "major=${"%.1f".format(event.getTouchMajor(index))} " +
                "minor=${"%.1f".format(event.getTouchMinor(index))} " +
                "at=${event.getX(index).toInt()},${event.getY(index).toInt()} " +
                "-> $decision",
        )
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        handleStylusButtonState(event)
        return super.onGenericMotionEvent(event)
    }

    /** Standard stylus side-button (USI/other pens); the Focus Pen Pro uses F19 instead. */
    private fun handleStylusButtonState(event: MotionEvent) {
        val stylusSource = (event.source and android.view.InputDevice.SOURCE_STYLUS) != 0 ||
            (0 until event.pointerCount).any { pointer ->
                val tool = event.getToolType(pointer)
                tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
            }
        if (!stylusSource) return
        val pressed = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY) ||
            event.isButtonPressed(MotionEvent.BUTTON_STYLUS_SECONDARY)
        if (pressed && !stylusButtonDown) onStylusButton?.invoke()
        stylusButtonDown = pressed
    }

    /**
     * [index] is the pointer that carries the stylus. It is not always pointer 0: when a hand is
     * already resting on the screen, the pen arrives as an extra pointer via ACTION_POINTER_DOWN.
     *
     * The arbiter has already ended any finger gesture and rejected the remaining contacts before
     * this runs, so this function only owns the ink/eraser/lasso side of the stylus gesture.
     */
    private fun handleStylus(event: MotionEvent, index: Int) {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            finishPenGesture(cancelled = true)
            return
        }
        val penDown = action == MotionEvent.ACTION_DOWN ||
            (action == MotionEvent.ACTION_POINTER_DOWN && event.actionIndex == index)
        val penUp = action == MotionEvent.ACTION_UP ||
            (action == MotionEvent.ACTION_POINTER_UP && event.actionIndex == index)
        if (sizes.isEmpty()) {
            if (penUp) finishPenGesture(cancelled = false)
            return
        }
        val x = event.getX(index)
        val y = event.getY(index)
        when {
            penDown -> {
                abortFling()
                panAxis = PanAxis.NONE
                manualScrollStartPage = -1
                lastTapTime = 0L
                velocity.clear()
                val eraserTip = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
                if (autoMarkerArmed && !eraserTip) {
                    // Only a document opened with no tool chosen falls back to the marker. Once the
                    // user picked a pen/highlighter and a preset, that choice is respected across
                    // documents; the preset already set in the view is used for this stroke.
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
                        eraseAt(x, y)
                    }

                    tool == PenTool.NONE -> Unit
                    tool == PenTool.LASSO -> beginLassoOrMove(x, y)
                    else -> startStroke(x, y, event.getPressure(index))
                }
                invalidate()
            }

            penUp -> finishPenGesture(cancelled = false, endX = x, endY = y)

            action == MotionEvent.ACTION_MOVE -> when {
                erasing -> eraseAt(x, y)
                tool == PenTool.LASSO -> updateLassoOrMove(x, y)
                liveStroke != null -> {
                    appendHistory(event, index)
                    appendPoint(x, y, event.getPressure(index))
                    invalidate()
                }
            }
        }
    }

    private fun startStroke(x: Float, y: Float, pressure: Float) {
        val hit = mapToPage(x, y) ?: return
        if (hit.yPt < 0f || hit.yPt > sizes[hit.page].height) return
        val highlighter = tool == PenTool.HIGHLIGHTER
        val pageEdge = pageLongEdge(hit.page)
        liveStroke = Stroke(
            id = UUID.randomUUID().toString(),
            page = hit.page,
            color = preset.color,
            // The preset is page-independent; this page decides its actual size in points.
            width = brushWidthToPagePt(preset.width, pageEdge),
            highlighter = highlighter,
            baseWidth = preset.width,
        ).also { it.add(InkPoint(hit.xPt, hit.yPt, pressure.coerceIn(0.05f, 1f))) }
        inkStabilizer.start(x, y, StrokeStabilizer.radiusFor(stabilizerStrength, density))
    }

    /** Long edge of a page in points; the reference for every page-relative brush size. */
    fun pageLongEdge(page: Int): Float {
        val size = sizes.getOrNull(page) ?: return REFERENCE_PAGE_LONG_EDGE_PT
        return maxOf(size.width, size.height).coerceAtLeast(1f)
    }

    private fun appendHistory(event: MotionEvent, index: Int) {
        for (history in 0 until event.historySize) {
            appendPoint(
                event.getHistoricalX(index, history),
                event.getHistoricalY(index, history),
                event.getHistoricalPressure(index, history),
            )
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
        // One eraser preset therefore removes the same relative area on every page size.
        val tipPt = brushWidthToPagePt(eraserWidth, pageLongEdge(hit.page))
        eraserTipPt = tipPt
        session?.eraseAt(hit.page, hit.xPt, hit.yPt, tipPt / 2f, eraserPixel)
        invalidate()
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

    private fun handleTap(x: Float, y: Float, time: Long) {
        val isDouble = time - lastTapTime < 320L &&
            hypot(x - lastTapX, y - lastTapY) < 110f * density
        if (isDouble) {
            lastTapTime = 0L
            val target = if (zoom < 1.7f) (zoom * 2f).coerceAtMost(MAX_ZOOM) else 1f
            if (touchLogging) Log.d(TOUCH_TAG, "double tap zoom ${zoom} -> $target")
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
            revealScrollbarAfterPageCrossing()
            emitScrollbarState()
            scheduleViewportReport()
            postInvalidateOnAnimation()
        } else if (manualScrollStartPage >= 0 && !inputArbiter.panning) {
            revealScrollbarAfterPageCrossing()
            manualScrollStartPage = -1
            scheduleScrollbarHide()
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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            // No ACTION_CANCEL is guaranteed when focus moves to a dialog/system window.
            resetInputState()
        }
    }

    override fun onDetachedFromWindow() {
        reportViewport()
        closeDocument()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            // Home / recents / screen off: stop any gesture, keep the reading position and push
            // pending ink to disk.
            resetInputState()
            reportViewport()
            session?.flush()
        }
    }

    /**
     * Drops every in-flight contact without applying viewport motion. A cancelled stroke is
     * discarded rather than committed, and a selection transform is reverted.
     */
    private fun resetInputState() {
        inputArbiter.reset()
        abortFling()
        finishPenGesture(cancelled = true)
        if (movingSelection || scalingSelection) finishTransformGesture(cancelled = true)
        panAxis = PanAxis.NONE
        velocity.clear()
        lastTapTime = 0L
        manualScrollStartPage = -1
        scheduleScrollbarHide()
    }
}
