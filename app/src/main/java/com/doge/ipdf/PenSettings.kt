package com.doge.ipdf

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

enum class PenTool { NONE, PEN, HIGHLIGHTER, ERASER, LASSO }

enum class EraserMode { STROKE, PIXEL }

/** Colour is ARGB (alpha included); width is stored in PDF points (1/72 inch). */
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
    var eraserMode by mutableStateOf(
        runCatching { EraserMode.valueOf(prefs.getString("eraserMode", null) ?: "") }
            .getOrDefault(EraserMode.STROKE)
    )
        private set

    /** Stroke stabilization: 0 = off (pixel-locked to the pen), 1 = strongest smoothing. */
    var stabilizer by mutableStateOf(prefs.getFloat("stabilizer", 0.3f).coerceIn(0f, 1f))
        private set

    /** The pen/highlighter the stylus button restores after erasing. */
    var lastInkTool by mutableStateOf(
        runCatching { PenTool.valueOf(prefs.getString("lastInkTool", null) ?: "") }
            .getOrDefault(PenTool.PEN)
    )
        private set
    private var lastStylusToggleAt = 0L

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
    }

    val isDrawingTool: Boolean get() = tool == PenTool.PEN || tool == PenTool.HIGHLIGHTER

    val hasPresets: Boolean
        get() = tool == PenTool.PEN || tool == PenTool.HIGHLIGHTER || tool == PenTool.ERASER

    val eraserWidth: Float get() = eraserPresets[eraserSelected].width

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
                val clamped = width.coerceIn(4f, 48f)
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

    fun updateEraserMode(mode: EraserMode) {
        eraserMode = mode
        persist()
    }

    fun updateStabilizer(value: Float) {
        stabilizer = value.coerceIn(0f, 1f)
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
            .putString("eraserMode", eraserMode.name)
            .putFloat("stabilizer", stabilizer)
            .putString("lastInkTool", lastInkTool.name)
            .apply()
    }

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
