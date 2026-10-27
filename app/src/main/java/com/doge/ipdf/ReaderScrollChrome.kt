package com.doge.ipdf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** The transient page-position control shown only after a manual scroll crosses a page. */
@Composable
fun ReaderScrollChrome(
    state: ScrollbarState,
    topInset: androidx.compose.ui.unit.Dp,
    onBeginDrag: () -> Unit,
    onDrag: (Float) -> Unit,
    onEndDrag: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible || state.pageCount < 2) return

    var pageDialogOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxHeight()) {
        Surface(
            onClick = { pageDialogOpen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topInset + 8.dp, end = 5.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 3.dp,
            shadowElevation = 5.dp,
        ) {
            Text(
                text = "${state.page + 1}/${state.pageCount}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            )
        }

        AnimatedVisibility(
            visible = state.visible,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = topInset + 46.dp, bottom = 16.dp, end = 2.dp),
            enter = fadeIn() + scaleIn(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f),
            ),
            exit = fadeOut() + scaleOut(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f),
            ),
        ) {
            ScrollbarRail(
                state = state,
                onBeginDrag = onBeginDrag,
                onDrag = onDrag,
                onEndDrag = onEndDrag,
            )
        }
    }

    if (pageDialogOpen) {
        PageJumpDialog(
            initialPage = state.page + 1,
            pageCount = state.pageCount,
            onDismiss = { pageDialogOpen = false },
            onConfirm = { page ->
                pageDialogOpen = false
                onJumpToPage(page - 1)
            },
        )
    }
}

@Composable
private fun ScrollbarRail(
    state: ScrollbarState,
    onBeginDrag: () -> Unit,
    onDrag: (Float) -> Unit,
    onEndDrag: () -> Unit,
) {
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val minThumbPx = with(LocalDensity.current) { 44.dp.toPx() }
    val thumbHeightPx = max(minThumbPx, trackHeightPx * state.thumbFraction)
    val usableHeightPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val thumbOffset = (usableHeightPx * state.fraction).roundToInt()

    BoxWithConstraints(
        modifier = Modifier
            .width(30.dp)
            .fillMaxHeight()
            .pointerInput(state.visible, state.thumbFraction) {
                detectDragGestures(
                    onDragStart = { position ->
                        dragY = position.y
                        onBeginDrag()
                    },
                    onDrag = { change, amount ->
                        dragY += amount.y
                        onDrag(((dragY - thumbHeightPx / 2f) / usableHeightPx).coerceIn(0f, 1f))
                    },
                    onDragEnd = onEndDrag,
                    onDragCancel = onEndDrag,
                )
            },
    ) {
        trackHeightPx = constraints.maxHeight.toFloat()
        val thumbHeight = thumbHeightPx.coerceAtMost(trackHeightPx).roundToInt().coerceAtLeast(1)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(4.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f),
                    RoundedCornerShape(4.dp),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbOffset) }
                .width(18.dp)
                .height(with(androidx.compose.ui.platform.LocalDensity.current) { thumbHeight.toDp() })
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
        )
    }
}

@Composable
private fun PageJumpDialog(
    initialPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember(initialPage) { mutableStateOf(initialPage.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to page") },
        text = {
            TextField(
                value = input,
                onValueChange = { value -> input = value.filter(Char::isDigit).take(7) },
                singleLine = true,
                label = { Text("Page 1–$pageCount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    input.toIntOrNull()?.takeIf { it in 1..pageCount }?.let(onConfirm)
                },
            ) { Text("Jump") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
