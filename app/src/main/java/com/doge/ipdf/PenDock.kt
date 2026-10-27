package com.doge.ipdf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pen dock plus the preset rail / detached preset bubble.
 *
 * - Tools are always single-selection (no "no tool" state).
 * - Pen, highlighter and eraser keep four presets expanded vertically; every swatch shows its
 *   size in the bottom-right corner. Tapping a preset selects it, tapping the selected preset
 *   opens the size/colour editor.
 * - The eraser editor chooses the tip size and whether to erase pixels or whole strokes.
 * - Long-pressing a colour in the editor opens a free colour wheel with opacity.
 * - The settings dialog can detach the presets into a draggable horizontal bubble.
 */
@Composable
fun PenDock(
    settings: PenSettings,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onEditBrush: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val showRail = !settings.collapsed && settings.hasPresets && !settings.floatingPresets

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = showRail,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PresetRail(settings = settings, onEditBrush = onEditBrush)
                Spacer(Modifier.width(8.dp))
            }
        }

        if (settings.collapsed) {
            Surface(
                onClick = { settings.toggleCollapsed() },
                modifier = Modifier
                    .size(52.dp)
                    .semantics { contentDescription = "Show pen tools" },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ToolGlyph(settings.tool, MaterialTheme.colorScheme.onSurface)
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    DockToolButton(PenTool.PEN, settings)
                    DockToolButton(PenTool.HIGHLIGHTER, settings)
                    DockToolButton(PenTool.ERASER, settings)
                    DockToolButton(PenTool.LASSO, settings)
                    HorizontalDivider(
                        modifier = Modifier
                            .width(26.dp)
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    HistoryButton(undo = true, enabled = canUndo, onClick = onUndo)
                    HistoryButton(undo = false, enabled = canRedo, onClick = onRedo)
                    HorizontalDivider(
                        modifier = Modifier
                            .width(26.dp)
                            .padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp, 32.dp)
                            .clickable { settings.toggleCollapsed() }
                            .semantics { contentDescription = "Hide pen tools" },
                        contentAlignment = Alignment.Center
                    ) {
                        ChevronGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRail(settings: PenSettings, onEditBrush: (Rect) -> Unit) {
    var railRect by remember { mutableStateOf(Rect.Zero) }
    val tool = settings.tool
    Surface(
        modifier = Modifier.onGloballyPositioned { railRect = it.boundsInWindow() },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            settings.presets.forEachIndexed { index, preset ->
                PresetSwatch(
                    preset = preset,
                    tool = tool,
                    selected = index == settings.selectedIndex,
                    onClick = {
                        if (index == settings.selectedIndex) onEditBrush(railRect)
                        else settings.selectPreset(index)
                    }
                )
            }
        }
    }
}

/** The detached, draggable horizontal preset bubble. */
@Composable
fun FloatingPresetBar(
    settings: PenSettings,
    containerSize: IntSize,
    onEditBrush: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val tool = settings.tool
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    var barRect by remember { mutableStateOf(Rect.Zero) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var initialized by remember { mutableStateOf(false) }
    val barWidthPx = with(density) { 218.dp.toPx() }
    val barHeightPx = with(density) { 56.dp.toPx() }

    LaunchedEffect(containerSize) {
        if (containerSize.width == 0 || containerSize.height == 0) return@LaunchedEffect
        if (!initialized) {
            offsetX = if (settings.hasPresetBarOffset) {
                with(density) { settings.presetBarX.dp.toPx() }
            } else {
                with(density) { 22.dp.toPx() }
            }
            offsetY = if (settings.hasPresetBarOffset) {
                with(density) { settings.presetBarY.dp.toPx() }
            } else {
                containerSize.height - barHeightPx - with(density) { 120.dp.toPx() }
            }
            initialized = true
        }
        offsetX = offsetX.coerceIn(0f, (containerSize.width - barWidthPx).coerceAtLeast(0f))
        offsetY = offsetY.coerceIn(0f, (containerSize.height - barHeightPx).coerceAtLeast(0f))
    }

    Surface(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .onGloballyPositioned { barRect = it.boundsInWindow() }
            .pointerInput(containerSize) {
                detectDragGestures(
                    onDragEnd = {
                        settings.savePresetBarOffset(
                            with(density) { offsetX.toDp().value },
                            with(density) { offsetY.toDp().value }
                        )
                    }
                ) { change, drag ->
                    change.consume()
                    offsetX = (offsetX + drag.x).coerceIn(0f, (containerSize.width - barWidthPx).coerceAtLeast(0f))
                    offsetY = (offsetY + drag.y).coerceIn(0f, (containerSize.height - barHeightPx).coerceAtLeast(0f))
                }
            },
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Canvas(
                Modifier
                    .size(14.dp, 26.dp)
                    .padding(horizontal = 2.dp)
            ) {
                val gap = 4.dp.toPx()
                drawCircle(outline, radius = 1.5.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f - gap))
                drawCircle(outline, radius = 1.5.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f))
                drawCircle(outline, radius = 1.5.dp.toPx(), center = Offset(size.width / 2f, size.height / 2f + gap))
            }
            settings.presets.forEachIndexed { index, preset ->
                PresetSwatch(
                    preset = preset,
                    tool = tool,
                    selected = index == settings.selectedIndex,
                    onClick = {
                        if (index == settings.selectedIndex) onEditBrush(barRect) else settings.selectPreset(index)
                    }
                )
            }
        }
    }
}

@Composable
private fun PresetSwatch(
    preset: BrushPreset,
    tool: PenTool,
    selected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(46.dp)
            .semantics { contentDescription = "Brush preset" },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Box {
            Canvas(
                Modifier
                    .size(46.dp)
                    .padding(start = 7.dp, top = 6.dp, end = 7.dp, bottom = 10.dp)
            ) {
                when (tool) {
                    PenTool.HIGHLIGHTER -> {
                        val strokeWidth = (preset.width * 0.5f).coerceIn(4f, 11f).dp.toPx()
                        drawLine(
                            color = Color(preset.color),
                            start = Offset(2.dp.toPx(), size.height / 2f),
                            end = Offset(size.width - 2.dp.toPx(), size.height / 2f),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round
                        )
                    }

                    PenTool.ERASER -> {
                        val radius = (preset.width / 48f * size.minDimension).coerceIn(2.5.dp.toPx(), size.minDimension / 2f)
                        drawCircle(
                            color = contentColor.copy(alpha = 0.55f),
                            radius = radius,
                            style = Stroke(width = 1.3.dp.toPx())
                        )
                    }

                    else -> {
                        val radius = (preset.width * 1.5f).coerceIn(2f, 9f).dp.toPx()
                        drawCircle(Color(preset.color), radius = radius)
                    }
                }
            }
            Text(
                text = sizeLabel(tool, preset.width),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = contentColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 3.dp, bottom = 1.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 2.dp)
            )
        }
    }
}

private fun sizeLabel(tool: PenTool, width: Float): String =
    if (tool == PenTool.ERASER || width >= 10f) width.roundToInt().toString()
    else String.format(java.util.Locale.US, "%.1f", width)

@Composable
private fun DockToolButton(tool: PenTool, settings: PenSettings) {
    val selected = settings.tool == tool
    Surface(
        onClick = { settings.toggleTool(tool) },
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = toolLabel(tool) },
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Box(contentAlignment = Alignment.Center) {
            ToolGlyph(
                tool = tool,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HistoryButton(undo: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (undo) "Undo" else "Redo" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(21.dp)) { drawHistoryGlyph(tint, undo) }
    }
}

@Composable
fun PenSettingsDialog(settings: PenSettings, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        SettingsPanel(settings = settings, onDismiss = onDismiss)
    }
}

@Composable
private fun SettingsPanel(settings: PenSettings, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 14.dp,
        modifier = Modifier.width(310.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Pen settings",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating preset bar", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Detach the four presets into a draggable horizontal bubble that cannot be collapsed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = settings.floatingPresets,
                    onCheckedChange = { settings.useFloatingPresets(it) }
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Stroke stabilization", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    stabilizerLabel(settings.stabilizer),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Smooths hand jitter while writing. Higher values give steadier lines but trail the pen a little.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = settings.stabilizer,
                onValueChange = { settings.updateStabilizer(it) },
                valueRange = 0f..1f
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Off",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Strong",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "Done",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

private fun stabilizerLabel(value: Float): String {
    val percent = (value * 100).roundToInt()
    return when {
        percent <= 0 -> "Off"
        percent < 35 -> "Light $percent%"
        percent < 70 -> "Medium $percent%"
        else -> "Strong $percent%"
    }
}

/** Size/colour editor for the currently selected preset (or the eraser size and mode). */
@Composable
fun BrushEditorPopup(settings: PenSettings, anchor: Rect, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(anchor, gapPx) { SidePopupPosition(anchor, gapPx, gapPx) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        BrushEditor(settings = settings, onDone = onDismiss)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrushEditor(settings: PenSettings, onDone: () -> Unit) {
    val tool = settings.tool
    val preset = settings.activePreset
    var customColor by remember { mutableStateOf<Int?>(null) }
    var customColorIndex by remember { mutableStateOf(-1) }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 14.dp,
        modifier = Modifier.width(258.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = toolLabel(tool),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${sizeLabel(tool, preset.width)} pt",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            when (tool) {
                PenTool.ERASER -> EraserEditor(settings)
                else -> {
                    PreviewSwatch(tool = tool, preset = preset)
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = preset.width,
                        onValueChange = { settings.updateWidth(it) },
                        valueRange = if (tool == PenTool.HIGHLIGHTER) 4f..22f else 0.5f..8f
                    )
                    Spacer(Modifier.height(4.dp))
                    ColorRow(
                        settings = settings,
                        palette = settings.paletteFor(tool),
                        preset = preset,
                        onLongPressColor = { index, baseColor ->
                            customColorIndex = index
                            customColor = baseColor
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    onClick = onDone,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "Done",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    customColor?.let { initial ->
        ColorPickerDialog(
            initialColor = initial,
            onDismiss = {
                customColor = null
                customColorIndex = -1
            },
            onPick = {
                settings.replacePaletteColor(tool, customColorIndex, it)
                customColor = null
                customColorIndex = -1
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorRow(
    settings: PenSettings,
    palette: List<Int>,
    preset: BrushPreset,
    onLongPressColor: (Int, Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        palette.forEachIndexed { index, color ->
            val selected = preset.color == color
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        CircleShape
                    )
                    .padding(2.5.dp)
                    .background(Color(color), CircleShape)
                    .combinedClickable(
                        onClick = { settings.updateColor(color) },
                        onLongClick = { onLongPressColor(index, color) }
                    )
                    .semantics { contentDescription = "Colour" }
            )
        }
    }
}

@Composable
private fun EraserEditor(settings: PenSettings) {
    val preset = settings.activePreset
    Text(
        "Tip size",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = preset.width,
        onValueChange = { settings.updateWidth(it) },
        valueRange = 4f..48f
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Erase mode",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    EraserModeRow(
        title = "Erase pixels",
        subtitle = "Only the part under the tip disappears",
        selected = settings.eraserMode == EraserMode.PIXEL,
        onClick = { settings.updateEraserMode(EraserMode.PIXEL) }
    )
    EraserModeRow(
        title = "Erase strokes",
        subtitle = "Remove the whole stroke",
        selected = settings.eraserMode == EraserMode.STROKE,
        onClick = { settings.updateEraserMode(EraserMode.STROKE) }
    )
}

@Composable
private fun EraserModeRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val ringColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(Modifier.size(18.dp)) {
                drawCircle(
                    color = ringColor,
                    radius = 7.dp.toPx(),
                    style = Stroke(width = 1.6.dp.toPx())
                )
                if (selected) {
                    drawCircle(ringColor, radius = 4.dp.toPx())
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PreviewSwatch(tool: PenTool, preset: BrushPreset) {
    val color = Color(preset.color)
    val background = MaterialTheme.colorScheme.surfaceVariant
    val strokeWidth = if (tool == PenTool.HIGHLIGHTER) (preset.width * 1.4f).coerceIn(4f, 18f)
    else (preset.width * 1.4f).coerceIn(1.5f, 12f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .background(background, RoundedCornerShape(14.dp))
    ) {
        val y = size.height / 2f
        val start = size.width * 0.12f
        val end = size.width * 0.88f
        val path = Path().apply {
            moveTo(start, y + strokeWidth.dp.toPx() * 0.6f)
            cubicTo(
                start + (end - start) * 0.3f, y - 14.dp.toPx(),
                start + (end - start) * 0.65f, y + 14.dp.toPx(),
                end, y - strokeWidth.dp.toPx() * 0.4f
            )
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** Free HSV wheel with brightness and opacity, opened by long-pressing a colour. */
@Composable
private fun ColorPickerDialog(initialColor: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(hsv[1]) }
    var value by remember(initialColor) { mutableFloatStateOf(hsv[2]) }
    var alpha by remember(initialColor) {
        mutableFloatStateOf(android.graphics.Color.alpha(initialColor) / 255f)
    }
    val argb = android.graphics.Color.HSVToColor(
        (alpha * 255f).roundToInt().coerceIn(0, 255),
        floatArrayOf(hue, saturation, value)
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 16.dp,
            modifier = Modifier.width(380.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Custom colour",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                ColorWheel(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onChange = { h, s ->
                        hue = h
                        saturation = s
                    }
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Brightness",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0f..1f
                )
                Text(
                    "Opacity ${(alpha * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    valueRange = 0f..1f
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(Color(argb), RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            "Cancel",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = { onPick(argb) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Apply",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorWheel(hue: Float, saturation: Float, value: Float, onChange: (Float, Float) -> Unit) {
    var measured by remember { mutableStateOf(IntSize.Zero) }
    fun pick(position: Offset) {
        if (measured.width == 0 || measured.height == 0) return
        val cx = measured.width / 2f
        val cy = measured.height / 2f
        val radius = min(measured.width, measured.height) / 2f
        val dx = position.x - cx
        val dy = position.y - cy
        val distance = hypot(dx, dy)
        val sat = (distance / radius).coerceIn(0f, 1f)
        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (degrees < 0f) degrees += 360f
        onChange(degrees, sat)
    }

    Canvas(
        Modifier
            .size(244.dp)
            .onSizeChanged { measured = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pick(it) }
                ) { change, _ ->
                    change.consume()
                    pick(change.position)
                }
            }
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val hueColors = (0..12).map { step ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf(step * 30f % 360f, 1f, 1f)))
        }
        drawCircle(
            brush = Brush.sweepGradient(colors = hueColors, center = center),
            radius = radius,
            center = center
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
        if (value < 1f) {
            drawCircle(
                color = Color.Black.copy(alpha = (1f - value).coerceIn(0f, 1f)),
                radius = radius,
                center = center
            )
        }
        val angle = Math.toRadians(hue.toDouble())
        val thumb = Offset(
            center.x + cos(angle).toFloat() * saturation * radius,
            center.y + sin(angle).toFloat() * saturation * radius
        )
        drawCircle(Color.White, 7.dp.toPx(), thumb, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.45f), 8.5.dp.toPx(), thumb, style = Stroke(width = 1.dp.toPx()))
    }
}

private fun toolLabel(tool: PenTool): String = when (tool) {
    PenTool.NONE -> "No tool"
    PenTool.PEN -> "Pen"
    PenTool.HIGHLIGHTER -> "Highlighter"
    PenTool.ERASER -> "Eraser"
    PenTool.LASSO -> "Lasso"
}

/** Places a popup next to an anchor, preferring the left side and clamping to the window. */
private class SidePopupPosition(
    private val anchor: Rect,
    private val gapPx: Int,
    private val marginPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val hasAnchor = anchor != Rect.Zero
        val leftOfAnchor = anchor.left.roundToInt() - popupContentSize.width - gapPx
        val rightOfAnchor = anchor.right.roundToInt() + gapPx
        val x = when {
            !hasAnchor -> windowSize.width - popupContentSize.width - marginPx
            leftOfAnchor >= marginPx -> leftOfAnchor
            rightOfAnchor + popupContentSize.width <= windowSize.width - marginPx -> rightOfAnchor
            else -> marginPx
        }
        val y = (if (hasAnchor) anchor.center.y.roundToInt() - popupContentSize.height / 2
        else windowSize.height / 2 - popupContentSize.height / 2)
            .coerceIn(marginPx, (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx))
        return IntOffset(x, y)
    }
}

@Composable
private fun ToolGlyph(tool: PenTool, tint: Color) {
    Canvas(Modifier.size(23.dp)) {
        when (tool) {
            PenTool.NONE -> drawPenGlyph(tint)
            PenTool.PEN -> drawPenGlyph(tint)
            PenTool.HIGHLIGHTER -> drawHighlighterGlyph(tint)
            PenTool.ERASER -> drawEraserGlyph(tint)
            PenTool.LASSO -> drawLassoGlyph(tint)
        }
    }
}

private fun DrawScope.drawPenGlyph(tint: Color) {
    val w = size.minDimension / 24f
    val line = Stroke(width = 1.7f * w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val body = Path().apply {
        moveTo(5.4f * w, 18.6f * w)
        lineTo(6.4f * w, 14.6f * w)
        lineTo(15.2f * w, 5.8f * w)
        lineTo(18.4f * w, 9.0f * w)
        lineTo(9.6f * w, 17.8f * w)
        close()
    }
    drawPath(body, tint, style = line)
    drawLine(tint, Offset(14.6f * w, 4.4f * w), Offset(19.8f * w, 9.6f * w), 2.4f * w, StrokeCap.Round)
}

private fun DrawScope.drawHighlighterGlyph(tint: Color) {
    val w = size.minDimension / 24f
    val line = Stroke(width = 1.7f * w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val body = Path().apply {
        moveTo(6.2f * w, 15.2f * w)
        lineTo(12.4f * w, 4.2f * w)
        lineTo(18.2f * w, 7.4f * w)
        lineTo(12.0f * w, 18.4f * w)
        close()
    }
    drawPath(body, tint, style = line)
    drawLine(tint, Offset(4.4f * w, 21.4f * w), Offset(19.6f * w, 21.4f * w), 2.6f * w, StrokeCap.Round)
}

private fun DrawScope.drawEraserGlyph(tint: Color) {
    val w = size.minDimension / 24f
    rotate(degrees = -38f, pivot = center) {
        drawRoundRect(
            color = tint,
            topLeft = Offset(5.4f * w, 9.2f * w),
            size = Size(13.2f * w, 6.6f * w),
            cornerRadius = CornerRadius(2.2f * w),
            style = Stroke(width = 1.7f * w)
        )
        drawLine(
            tint,
            Offset(9.4f * w, 9.4f * w),
            Offset(9.4f * w, 15.6f * w),
            1.4f * w,
            StrokeCap.Round
        )
    }
}

private fun DrawScope.drawLassoGlyph(tint: Color) {
    val w = size.minDimension / 24f
    val loop = Path().apply {
        moveTo(5.2f * w, 12.4f * w)
        cubicTo(5.2f * w, 5.4f * w, 18.8f * w, 5.4f * w, 18.8f * w, 12.4f * w)
        cubicTo(18.8f * w, 19.4f * w, 5.2f * w, 19.4f * w, 5.2f * w, 12.4f * w)
        close()
    }
    drawPath(
        loop,
        tint,
        style = Stroke(
            width = 1.6f * w,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.6f * w, 2.8f * w))
        )
    )
    drawLine(tint, Offset(5.6f * w, 13.6f * w), Offset(3.6f * w, 20.4f * w), 1.5f * w, StrokeCap.Round)
}

private fun DrawScope.drawHistoryGlyph(tint: Color, undo: Boolean) {
    val w = size.minDimension / 24f
    if (!undo) {
        rotate(degrees = 180f, pivot = center) { drawHistoryGlyph(tint, true) }
        return
    }
    drawArc(
        color = tint,
        startAngle = 205f,
        sweepAngle = 235f,
        useCenter = false,
        topLeft = Offset(4.6f * w, 5.4f * w),
        size = Size(14.8f * w, 13.2f * w),
        style = Stroke(width = 1.9f * w, cap = StrokeCap.Round)
    )
    val head = Path().apply {
        moveTo(3.6f * w, 7.0f * w)
        lineTo(9.8f * w, 6.2f * w)
        lineTo(5.6f * w, 11.6f * w)
        close()
    }
    drawPath(head, tint)
}

@Composable
private fun ChevronGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.minDimension / 24f
        drawLine(tint, Offset(9f * w, 6f * w), Offset(15f * w, 12f * w), 2f * w, StrokeCap.Round)
        drawLine(tint, Offset(15f * w, 12f * w), Offset(9f * w, 18f * w), 2f * w, StrokeCap.Round)
    }
}

@Composable
fun GearGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.minDimension / 24f
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(tint, radius = 6.2f * w, center = Offset(cx, cy), style = Stroke(width = 1.8f * w))
        for (index in 0 until 8) {
            val angle = Math.toRadians(index * 45.0)
            val startX = cx + cos(angle).toFloat() * 7.4f * w
            val startY = cy + sin(angle).toFloat() * 7.4f * w
            val endX = cx + cos(angle).toFloat() * 9.6f * w
            val endY = cy + sin(angle).toFloat() * 9.6f * w
            drawLine(tint, Offset(startX, startY), Offset(endX, endY), 2f * w, StrokeCap.Round)
        }
        drawCircle(tint, radius = 2.1f * w, center = Offset(cx, cy))
    }
}
