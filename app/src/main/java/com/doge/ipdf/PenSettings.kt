package com.doge.ipdf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

enum class PenTool { NONE, PEN, HIGHLIGHTER, ERASER, LASSO }

enum class EraserMode { STROKE, PIXEL }

/**
 * Colour is ARGB (alpha included). [width] is a preset size shown in the dock and editor; it is
 * independent of the document: the page's own long edge decides how many PDF points it becomes, so
 * the same preset covers the same fraction of an A4 page and of a wide slide.
 */
data class BrushPreset(val color: Int, val width: Float)

/** Pen/tool preferences, persisted so brush choices survive restarts. */
class PenSettings(context: Context) {
    private val prefs = context.getSharedPreferences("pen_state", Context.MODE_PRIVATE)

    /**
     * No tool is selected when the app starts or a document is opened; the first stylus contact
     * selects the marker preset. Selecting the already-selected tool clears it again.
     */
    var tool by mutableStateOf(PenTool.NONE)
        private set
    var collapsed by mutableStateOf(prefs.getBoolean("collapsed", false))
        private set
    var penPresets by mutableStateOf(load("pen", defaultPenPresets()))
        private set
    var highlighterPresets by mutableStateOf(load("hl", defaultHighlighterPresets()))
        private set
    var eraserPresets by mutableStateOf(load("eraser", defaultEraserPresets()))
        private set

    /**
     * The colour row shown in the brush editor, shared by every preset of the same tool. Editing a
     * swatch (long-press → wheel) overwrites that swatch for the whole tool instead of only the
     * preset that happened to be open.
     */
    var penPalette by mutableStateOf(loadColors("penPalette", inkColors))
        private set
    var highlighterPalette by mutableStateOf(loadColors("hlPalette", highlighterColors))
        private set
    var penSelected by mutableStateOf(prefs.getInt("penSelected", 0).coerceIn(0, 3))
        private set
    var highlighterSelected by mutableStateOf(prefs.getInt("hlSelected", 0).coerceIn(0, 3))
        private set
    var eraserSelected by mutableStateOf(prefs.getInt("eraserSelected", 1).coerceIn(0, 3))
        private set

    /**
     * Erase mode per eraser preset, so the four eraser tips are independent: switching one tip to
     * pixel erasing leaves the other three in stroke mode. The old single [eraserMode] value is
     * only read as the starting point for users who never used the per-preset modes.
     */
    var eraserModes by mutableStateOf(
        List(ERASER_PRESET_COUNT) { index ->
            val stored = prefs.getString("$ERASER_MODE_KEY_PREFIX$index", null)
            if (stored != null) {
                runCatching { EraserMode.valueOf(stored) }.getOrDefault(EraserMode.STROKE)
            } else {
                legacyEraserMode()
            }
        }
    )
        private set

    /** Stroke stabilization: 0 = off (pixel-locked to the pen), 1 = strongest smoothing. */
    var stabilizer by mutableStateOf(prefs.getFloat("stabilizer", 0.3f).coerceIn(0f, 1f))
        private set

    /** Pinch zoom response: 1 = the platform's normal scale, below/above it is slower/faster. */
    var zoomSpeed by mutableStateOf(prefs.getFloat("zoomSpeed", 1f).coerceIn(0.5f, 2f))
        private set

    /** Finger-to-page scrolling response for both dragging and fling momentum. */
    var scrollSpeed by mutableStateOf(prefs.getFloat("scrollSpeed", 1f).coerceIn(0.5f, 2f))
        private set

    /** The pen/highlighter the stylus button restores after erasing. */
    var lastInkTool by mutableStateOf(
        runCatching { PenTool.valueOf(prefs.getString("lastInkTool", null) ?: "") }
            .getOrDefault(PenTool.PEN)
    )
        private set
    private var lastStylusToggleAt = 0L
    private var lastStylusButtonAt = 0L

    /** When true the preset swatches live in their own draggable horizontal bubble. */
    var floatingPresets by mutableStateOf(prefs.getBoolean("floatingPresets", false))
        private set

    /** Bubble offset in dp; negative means "not placed yet". */
    var presetBarX by mutableStateOf(prefs.getFloat("presetBarX", -1f))
        private set
    var presetBarY by mutableStateOf(prefs.getFloat("presetBarY", -1f))
        private set

    val hasPresetBarOffset: Boolean get() = presetBarX >= 0f && presetBarY >= 0f

    init {
        // Before this schema, highlighter colours were opaque and the renderer forced a fixed
        // transparency. Give those stored presets the default translucent alpha exactly once.
        if (prefs.getInt("schema", 0) < 2) {
            highlighterPresets = highlighterPresets.map { preset ->
                if (android.graphics.Color.alpha(preset.color) == 255) {
                    preset.copy(color = (preset.color and 0x00FFFFFF) or 0x8C000000.toInt())
                } else {
                    preset
                }
            }
            prefs.edit()
                .putString("hl", encode(highlighterPresets))
                .putInt("schema", 2)
                .apply()
        }
        // Schema 3 stores brush sizes relative to a reference page. Older builds saved absolute
        // PDF points (eraser tips 8/16/26/40, pen 1.6–4.8). Convert them once so existing presets
        // keep their on-screen size; the same conversion is applied to journaled strokes when they
        // are read back.
        if (prefs.getInt("schema", 0) < 3) {
            eraserPresets = eraserPresets.map { it.copy(width = legacyToRelative(it.width)) }
            penPresets = penPresets.map { it.copy(width = legacyToRelative(it.width)) }
            highlighterPresets = highlighterPresets.map { it.copy(width = legacyToRelative(it.width)) }
            prefs.edit()
                .putString("eraser", encode(eraserPresets))
                .putString("pen", encode(penPresets))
                .putString("hl", encode(highlighterPresets))
                .putInt("schema", 3)
                .apply()
        }
    }

    /**
     * Converts a width saved by an older build, which treated it as absolute points on an A4 page,
     * into the relative value that reproduces the same size on an A4 page today. The same
     * conversion is used for journaled strokes read back by [ReaderSession].
     */
    private fun legacyToRelative(widthPt: Float): Float = legacyWidthToRelative(widthPt)

    val isDrawingTool: Boolean get() = tool == PenTool.PEN || tool == PenTool.HIGHLIGHTER

    val hasPresets: Boolean
        get() = tool == PenTool.PEN || tool == PenTool.HIGHLIGHTER || tool == PenTool.ERASER

    val eraserWidth: Float get() = eraserPresets[eraserSelected].width

    /** Erase mode of the eraser tip that is currently selected in the rail. */
    val eraserMode: EraserMode get() = eraserModes[eraserSelected]

    val activePreset: BrushPreset
        get() = when (tool) {
            PenTool.PEN, PenTool.NONE -> penPresets[penSelected]
            PenTool.HIGHLIGHTER -> highlighterPresets[highlighterSelected]
            PenTool.ERASER -> eraserPresets[eraserSelected]
            PenTool.LASSO -> penPresets[penSelected]
        }

    val presets: List<BrushPreset>
        get() = when (tool) {
            PenTool.PEN -> penPresets
            PenTool.HIGHLIGHTER -> highlighterPresets
            PenTool.ERASER -> eraserPresets
            PenTool.NONE, PenTool.LASSO -> emptyList()
        }

    val selectedIndex: Int
        get() = when (tool) {
            PenTool.PEN, PenTool.NONE -> penSelected
            PenTool.HIGHLIGHTER -> highlighterSelected
            PenTool.ERASER -> eraserSelected
            PenTool.LASSO -> 0
        }

    fun selectTool(value: PenTool) {
        if (value == PenTool.NONE) {
            clearTool()
            return
        }
        if (value == PenTool.PEN || value == PenTool.HIGHLIGHTER) lastInkTool = value
        tool = value
        persist()
    }

    /** Dock behaviour: tapping the selected tool again leaves no tool selected. */
    fun toggleTool(value: PenTool) {
        if (tool == value) clearTool() else selectTool(value)
    }

    fun clearTool() {
        if (tool == PenTool.NONE) return
        tool = PenTool.NONE
        persist()
    }

    /**
     * Xiaomi Focus Pen Pro's side button arrives as KEYCODE_F19 from its Bluetooth HID keyboard.
     * One press switches to the eraser, the next press restores the pen/highlighter used before.
     */
    fun toggleEraserFromStylus() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStylusToggleAt < 250L) return
        lastStylusToggleAt = now
        if (tool == PenTool.ERASER) {
            val restore = lastInkTool.takeIf { it == PenTool.PEN || it == PenTool.HIGHLIGHTER }
                ?: PenTool.PEN
            selectTool(restore)
        } else {
            selectTool(PenTool.ERASER)
        }
    }

    /** Single press toggles eraser; a quick second press toggles lasso and the previous ink tool. */
    fun handleStylusButtonPress() {
        val now = android.os.SystemClock.uptimeMillis()
        val doublePress = now - lastStylusButtonAt in 80L..450L
        lastStylusButtonAt = if (doublePress) 0L else now
        if (doublePress) {
            toggleLassoFromStylus()
        } else {
            toggleEraserFromStylus()
        }
    }

    fun toggleLassoFromStylus() {
        if (tool == PenTool.LASSO) {
            selectTool(lastInkTool.takeIf { it == PenTool.PEN || it == PenTool.HIGHLIGHTER } ?: PenTool.PEN)
        } else {
            selectTool(PenTool.LASSO)
        }
    }

    fun toggleCollapsed() {
        collapsed = !collapsed
        persist()
    }

    fun useFloatingPresets(enabled: Boolean) {
        floatingPresets = enabled
        persist()
    }

    fun savePresetBarOffset(xDp: Float, yDp: Float) {
        presetBarX = xDp
        presetBarY = yDp
        prefs.edit().putFloat("presetBarX", xDp).putFloat("presetBarY", yDp).apply()
    }

    fun selectPreset(index: Int) {
        when (tool) {
            PenTool.PEN -> penSelected = index.coerceIn(0, penPresets.size - 1)
            PenTool.HIGHLIGHTER -> highlighterSelected = index.coerceIn(0, highlighterPresets.size - 1)
            PenTool.ERASER -> eraserSelected = index.coerceIn(0, eraserPresets.size - 1)
            PenTool.NONE, PenTool.LASSO -> Unit
        }
        persist()
    }

    fun updateWidth(width: Float) {
        when (tool) {
            PenTool.PEN -> {
                val clamped = width.coerceIn(0.5f, 10f)
                penPresets = penPresets.toMutableList().also { it[penSelected] = it[penSelected].copy(width = clamped) }
            }

            PenTool.HIGHLIGHTER -> {
                val clamped = width.coerceIn(4f, 24f)
                highlighterPresets = highlighterPresets.toMutableList()
                    .also { it[highlighterSelected] = it[highlighterSelected].copy(width = clamped) }
            }

            PenTool.ERASER -> {
                // Eraser tips are stored on the same relative scale as the other brushes.
                val clamped = width.coerceIn(0.4f, 8f)
                eraserPresets = eraserPresets.toMutableList()
                    .also { it[eraserSelected] = it[eraserSelected].copy(width = clamped) }
            }

            PenTool.NONE, PenTool.LASSO -> Unit
        }
        persist()
    }

    fun updateColor(color: Int) {
        when (tool) {
            PenTool.PEN -> penPresets = penPresets.toMutableList()
                .also { it[penSelected] = it[penSelected].copy(color = color) }

            PenTool.HIGHLIGHTER -> highlighterPresets = highlighterPresets.toMutableList()
                .also { it[highlighterSelected] = it[highlighterSelected].copy(color = color) }

            PenTool.NONE, PenTool.ERASER, PenTool.LASSO -> Unit
        }
        persist()
    }

    fun paletteFor(tool: PenTool): List<Int> = when (tool) {
        PenTool.PEN -> penPalette
        PenTool.HIGHLIGHTER -> highlighterPalette
        PenTool.NONE, PenTool.ERASER, PenTool.LASSO -> emptyList()
    }

    /**
     * Overwrites the shared swatch at [index] with [color]. Every preset of that tool that used the
     * old swatch follows the new colour, and the active preset switches to it as well, so the row
     * stays a single shared palette.
     */
    fun replacePaletteColor(tool: PenTool, index: Int, color: Int) {
        when (tool) {
            PenTool.PEN -> {
                if (index !in penPalette.indices) return
                val previous = penPalette[index]
                penPalette = penPalette.toMutableList().also { it[index] = color }
                penPresets = penPresets.map { if (it.color == previous) it.copy(color = color) else it }
            }

            PenTool.HIGHLIGHTER -> {
                if (index !in highlighterPalette.indices) return
                val previous = highlighterPalette[index]
                highlighterPalette = highlighterPalette.toMutableList().also { it[index] = color }
                highlighterPresets = highlighterPresets.map {
                    if (it.color == previous) it.copy(color = color) else it
                }
            }

            PenTool.NONE, PenTool.ERASER, PenTool.LASSO -> return
        }
        updateColor(color)
    }

    /** Changes the erase mode of the currently selected eraser tip only. */
    fun updateEraserMode(mode: EraserMode) {
        updateEraserMode(eraserSelected, mode)
    }

    fun updateEraserMode(index: Int, mode: EraserMode) {
        if (index !in eraserModes.indices) return
        eraserModes = eraserModes.toMutableList().also { it[index] = mode }
        persist()
    }

    fun updateStabilizer(value: Float) {
        stabilizer = value.coerceIn(0f, 1f)
        persist()
    }

    fun updateZoomSpeed(value: Float) {
        zoomSpeed = value.coerceIn(0.5f, 2f)
        persist()
    }

    fun updateScrollSpeed(value: Float) {
        scrollSpeed = value.coerceIn(0.5f, 2f)
        persist()
    }

    private fun persist() {
        prefs.edit()
            .putBoolean("collapsed", collapsed)
            .putBoolean("floatingPresets", floatingPresets)
            .putString("pen", encode(penPresets))
            .putString("hl", encode(highlighterPresets))
            .putString("eraser", encode(eraserPresets))
            .putString("penPalette", encodeColors(penPalette))
            .putString("hlPalette", encodeColors(highlighterPalette))
            .putInt("penSelected", penSelected)
            .putInt("hlSelected", highlighterSelected)
            .putInt("eraserSelected", eraserSelected)
            .putFloat("stabilizer", stabilizer)
            .putFloat("zoomSpeed", zoomSpeed)
            .putFloat("scrollSpeed", scrollSpeed)
            .putString("lastInkTool", lastInkTool.name)
            .also { editor ->
                eraserModes.forEachIndexed { index, mode ->
                    editor.putString("$ERASER_MODE_KEY_PREFIX$index", mode.name)
                }
            }
            .apply()
    }

    /** The pre-per-preset mode, used to seed the four tips on first launch after this change. */
    private fun legacyEraserMode(): EraserMode =
        runCatching { EraserMode.valueOf(prefs.getString("eraserMode", null) ?: "") }
            .getOrDefault(EraserMode.STROKE)

    private fun loadColors(key: String, fallback: List<Int>): List<Int> {
        val raw = prefs.getString(key, null) ?: return fallback
        return runCatching {
            val array = JSONArray(raw)
            val result = ArrayList<Int>(array.length())
            for (i in 0 until array.length()) result.add(array.getInt(i))
            if (result.isEmpty()) fallback else result
        }.getOrDefault(fallback)
    }

    private fun load(key: String, fallback: List<BrushPreset>): List<BrushPreset> = try {
        val raw = prefs.getString(key, null) ?: return fallback
        val array = JSONArray(raw)
        val result = ArrayList<BrushPreset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(BrushPreset(obj.getInt("c"), obj.optDouble("w", 2.0).toFloat()))
        }
        if (result.isEmpty()) fallback else result
    } catch (_: Exception) {
        fallback
    }

    companion object {
        val ERASER_COLOR = 0xFF374151.toInt()

        private const val ERASER_MODE_KEY_PREFIX = "eraserMode."
        private const val ERASER_PRESET_COUNT = 4

        /** A4 long edge, the page size older builds assumed when they stored absolute widths. */
        private const val LEGACY_REFERENCE_PAGE_PT = 841.89f

        /** The same A4-to-reference conversion [legacyToRelative] applies, for other readers. */
        fun legacyWidthToRelative(widthPt: Float): Float =
            widthPt * REFERENCE_PAGE_LONG_EDGE_PT / LEGACY_REFERENCE_PAGE_PT

        val inkColors = listOf(
            0xFF202124.toInt(),
            0xFFDC2626.toInt(),
            0xFF2563EB.toInt(),
            0xFF16A34A.toInt(),
            0xFFF59E0B.toInt(),
            0xFFDB2777.toInt()
        )

        val highlighterColors = listOf(
            0x8CFFD400.toInt(),
            0x8C86EFAC.toInt(),
            0x8C93C5FD.toInt(),
            0x8CF9A8D4.toInt(),
            0x8CFDBA74.toInt(),
            0x8CC4B5FD.toInt()
        )

        private fun defaultPenPresets() = listOf(
            BrushPreset(0xFF202124.toInt(), 1.6f),
            BrushPreset(0xFF2563EB.toInt(), 2.4f),
            BrushPreset(0xFFDC2626.toInt(), 3.4f),
            BrushPreset(0xFF16A34A.toInt(), 4.8f)
        )

        private fun defaultHighlighterPresets() = listOf(
            BrushPreset(0x8CFFD400.toInt(), 10f),
            BrushPreset(0x8C86EFAC.toInt(), 10f),
            BrushPreset(0x8C93C5FD.toInt(), 10f),
            BrushPreset(0x8CF9A8D4.toInt(), 10f)
        )

        // On the 840 pt reference page these are 8 / 16 / 26 / 40 pt tips; on any other page they
        // scale with its long edge so the effective tip keeps the same relative size.
        private fun defaultEraserPresets() = listOf(
            BrushPreset(ERASER_COLOR, 8f),
            BrushPreset(ERASER_COLOR, 16f),
            BrushPreset(ERASER_COLOR, 26f),
            BrushPreset(ERASER_COLOR, 40f)
        )

        private fun encode(presets: List<BrushPreset>): String {
            val array = JSONArray()
            presets.forEach { preset ->
                array.put(JSONObject().put("c", preset.color).put("w", preset.width.toDouble()))
            }
            return array.toString()
        }

        private fun encodeColors(colors: List<Int>): String {
            val array = JSONArray()
            colors.forEach { array.put(it) }
            return array.toString()
        }
    }
}
