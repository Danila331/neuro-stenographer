package ru.neurosteno.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.speakerFill
import ru.neurosteno.core.Utterance
import ru.neurosteno.core.timeline.TimelineLayout
import ru.neurosteno.core.timeline.TimelineUtterance

/**
 * Дорожка говорящих (B54): полоса во всю ширину, отрезки реплик цветами говорящих (те же цвета, что у меток), перекрывающиеся
 * реплики на соседних дорожках, вертикальная метка текущей позиции; тап и протяжка перематывают. Реплики из [highlighted]
 * (добавлены повторной обработкой фрагмента) -- с чёрной штриховкой и толстой рамкой. Раскладку считает [TimelineLayout].
 */
@Composable
fun SpeakerTimeline(
    utterances: List<Utterance>,
    highlighted: Set<Pair<Long, Long>>,
    durationMs: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val items = remember(utterances, highlighted) {
        utterances.map { TimelineUtterance(it.speakerId, it.startMs, it.endMs, (it.startMs to it.endMs) in highlighted) }
    }
    val layout = remember(items, durationMs, widthPx) { TimelineLayout(items, durationMs, widthPx.coerceAtLeast(1f)) }
    val laneDp = 12f
    val gapDp = 2f
    val stripDp = TimelineLayout.heightDp(layout.lanes, laneDp, gapDp) + 8f // + рамка и отступы
    val shown = (dragMs ?: positionMs).coerceIn(0L, maxOf(durationMs, 1L))
    val total = maxOf(durationMs, 1L)
    val bg = Brutal.surface
    val ink = Brutal.ink
    val accent = Brutal.accent
    val addedCount = items.count { it.added }
    val description = "Дорожка говорящих. Позиция ${Format.clock(shown)} из ${Format.clock(durationMs)}." +
        (if (addedCount > 0) " Добавлено повторной обработкой: $addedCount." else "") + " Нажмите или проведите, чтобы перемотать"

    Box(
        modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("speaker_timeline")
            .semantics {
                contentDescription = description
                stateDescription = "${Format.clock(shown)} из ${Format.clock(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat() / total, 0f..1f)
                if (enabled) setProgress { v -> onSeek((v.coerceIn(0f, 1f) * total).toLong()); true }
            }
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(layout, enabled) {
                if (enabled) detectTapGestures { p -> onSeek(layout.xToMs(p.x)) }
            }
            .pointerInput(layout, enabled) {
                if (enabled) detectHorizontalDragGestures(
                    onDragStart = { p -> dragMs = layout.xToMs(p.x) },
                    onDragEnd = { dragMs?.let(onSeek); dragMs = null },
                    onDragCancel = { dragMs = null },
                    onHorizontalDrag = { change, _ -> change.consume(); dragMs = layout.xToMs(change.position.x) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(stripDp.dp)) {
            val laneH = laneDp.dp.toPx()
            val gap = gapDp.dp.toPx()
            val padY = 3.dp.toPx()
            drawRoundRect(
                color = bg,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
            for (segment in layout.segments) {
                val top = padY + segment.lane * (laneH + gap)
                val x0 = segment.x0.coerceIn(0f, size.width)
                val width = (segment.x1 - segment.x0).coerceAtLeast(1f)
                drawRoundRect(
                    color = speakerFill(segment.speakerId),
                    topLeft = Offset(x0, top),
                    size = Size(width, laneH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
                if (segment.added) {
                    clipRect(x0, top, x0 + width, top + laneH) {
                        var offset = -laneH
                        while (offset < width + laneH) {
                            drawLine(
                                ink.copy(alpha = 0.48f),
                                Offset(x0 + offset, top + laneH),
                                Offset(x0 + offset + laneH, top),
                                strokeWidth = 1.dp.toPx(),
                            )
                            offset += 4.dp.toPx()
                        }
                    }
                    drawRect(accent, Offset(x0, top), Size(width, laneH), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            val x = layout.msToX(shown).coerceIn(0f, size.width)
            val halo = 7.dp.toPx()
            drawRect(bg, Offset(x - halo / 2, 0f), Size(halo, size.height))
            drawRect(accent, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            drawRect(accent, Offset(x - 4.dp.toPx(), 0f), Size(8.dp.toPx(), 4.dp.toPx()))
        }
    }
}
