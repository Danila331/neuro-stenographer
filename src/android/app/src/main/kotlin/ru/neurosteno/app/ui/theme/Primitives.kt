package ru.neurosteno.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.BoxScope
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------------------------
// Базовая «жёсткая» поверхность: толстая обводка + тень без размытия со смещением. Тень вынесена в зарезервированный отступ
// (padding справа/снизу), поэтому её не обрезают родители (LazyColumn, Scaffold) и размер элемента честный.
// ---------------------------------------------------------------------------------------------------------------------

@Composable
fun BrutalSurface(
    modifier: Modifier = Modifier,
    fill: Color = Brutal.bg,
    shadow: Dp = BrutalDims.Shadow,
    borderWidth: Dp = BrutalDims.Border,
    contentColor: Color = Brutal.ink,
    shadowColor: Color = Brutal.ink,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .padding(end = shadow, bottom = shadow)
            .drawBehind {
                if (shadow > 0.dp) drawRect(shadowColor, Offset(shadow.toPx(), shadow.toPx()), size)
            }
            .background(fill, BrutalShape)
            .border(borderWidth, Brutal.ink, BrutalShape),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Карточка: белая (в тёмной теме чёрная) заливка, обводка и тень; [onClick] делает её нажимаемой. */
@Composable
fun BrutalCard(
    modifier: Modifier = Modifier,
    fill: Color = Brutal.bg,
    shadow: Dp = BrutalDims.Shadow,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val contentColor = if (fill == Brutal.ink) Brutal.onInk else if (fill == Brutal.bg || fill == Brutal.panel) Brutal.ink else Brutal.onAccent
    val click = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier
    BrutalSurface(modifier, fill = fill, shadow = shadow, contentColor = contentColor) {
        Column(Modifier.then(click).padding(contentPadding)) { content() }
    }
}

enum class BrutalButtonStyle { Primary, Secondary, Ink, Danger }

/**
 * Кнопка: Primary -- акцентная заливка, Secondary -- фон, Ink -- инверсия, Danger -- заливка ошибки. В нажатом состоянии «вдавливается»
 * (смещается на величину тени). Текст всегда читаем: чёрный на акценте и ошибке.
 */
@Composable
fun BrutalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BrutalButtonStyle = BrutalButtonStyle.Primary,
    enabled: Boolean = true,
    height: Dp = BrutalDims.MinTouch + 4.dp,
    contentDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shadow = BrutalDims.Shadow
    val fill = when (style) {
        BrutalButtonStyle.Primary -> Brutal.accent
        BrutalButtonStyle.Secondary -> Brutal.bg
        BrutalButtonStyle.Ink -> Brutal.ink
        BrutalButtonStyle.Danger -> Brutal.error
    }
    val contentColor = when (style) {
        BrutalButtonStyle.Ink -> Brutal.onInk
        BrutalButtonStyle.Secondary -> Brutal.ink
        else -> Brutal.onAccent
    }
    val shift = if (pressed && enabled) shadow else 0.dp
    Box(
        modifier
            .padding(end = shadow, bottom = shadow)
            .defaultMinSize(minHeight = height)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        propagateMinConstraints = true, // fillMaxWidth()/weight() вызывающего растягивают и лицо кнопки; без них -- ширина по содержимому
    ) {
        // тень остаётся на месте, лицо кнопки сдвигается к ней
        Box(Modifier.matchParentSize().offset(shadow, shadow).background(if (enabled) Brutal.ink else Brutal.muted, BrutalShape))
        Row(
            Modifier
                .offset(shift, shift)
                .defaultMinSize(minHeight = height)
                .background(if (enabled) fill else Brutal.panel, BrutalShape)
                .border(BrutalDims.BorderThick, if (enabled) Brutal.ink else Brutal.muted, BrutalShape)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides (if (enabled) contentColor else Brutal.muted)) { content() }
        }
    }
}

/** Текстовое содержимое кнопки с заглавным шрифтом системы. */
@Composable
fun ButtonLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Квадратная иконка-кнопка с обводкой (минимум 48 dp). */
@Composable
fun BrutalIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fill: Color = Brutal.bg,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(BrutalDims.MinTouch)
            .padding(2.dp)
            .background(if (enabled) fill else Brutal.panel, BrutalShape)
            .border(BrutalDims.Border, if (enabled) Brutal.ink else Brutal.muted, BrutalShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides (if (fill == Brutal.ink) Brutal.onInk else if (enabled) Brutal.ink else Brutal.muted)) { content() }
    }
}

/** Выбираемая «плашка» (замена FilterChip): выбранная заливается акцентом. */
@Composable
fun BrutalChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = BrutalDims.MinTouch, minWidth = 48.dp)
            .padding(vertical = 2.dp)
            .background(if (selected) Brutal.accent else Brutal.bg, BrutalShape)
            .border(BrutalDims.Border, if (enabled) Brutal.ink else Brutal.muted, BrutalShape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; stateDescription = if (selected) "выбрано" else "не выбрано" }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Brutal.onAccent else if (enabled) Brutal.ink else Brutal.muted)
    }
}

/** Метка говорящего: неоновая заливка с чёрным текстом; цвет определяется номером говорящего и одинаков во всех экранах. */
@Composable
fun SpeakerChip(name: String, speakerId: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(speakerFill(speakerId), BrutalShape)
            .border(2.dp, Brutal.ink, BrutalShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = BrutalPalette.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Маленький цветной квадрат говорящего (в диалогах выбора и списках). */
@Composable
fun SpeakerSquare(speakerId: String, size: Dp = 18.dp) {
    Box(Modifier.size(size).background(speakerFill(speakerId), BrutalShape).border(2.dp, Brutal.ink, BrutalShape))
}

/** Тег: короткая моно-подпись в рамке (время, статус). */
@Composable
fun BrutalTag(text: String, modifier: Modifier = Modifier, fill: Color = Brutal.bg, textStyle: TextStyle = MonoStyle) {
    val fg = if (fill == Brutal.bg || fill == Brutal.panel) Brutal.ink else if (fill == Brutal.ink) Brutal.onInk else Brutal.onAccent
    Box(
        modifier
            .background(fill, BrutalShape)
            .border(2.dp, Brutal.ink, BrutalShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, style = textStyle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700)), color = fg, maxLines = 1)
    }
}

enum class PlaqueKind { Accent, Neutral, Error, Ink }

/** Плашка-сообщение: акцентная (важное), нейтральная, ошибка, инверсная. Читается как одно целое для TalkBack. */
@Composable
fun BrutalPlaque(
    text: String,
    modifier: Modifier = Modifier,
    kind: PlaqueKind = PlaqueKind.Neutral,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val fill = when (kind) {
        PlaqueKind.Accent -> Brutal.accent
        PlaqueKind.Neutral -> Brutal.panel
        PlaqueKind.Error -> Brutal.error
        PlaqueKind.Ink -> Brutal.ink
    }
    val fg = when (kind) {
        PlaqueKind.Neutral -> Brutal.ink
        PlaqueKind.Ink -> Brutal.onInk
        else -> Brutal.onAccent
    }
    BrutalSurface(modifier.fillMaxWidth().semantics { contentDescription = listOfNotNull(title, text).joinToString(". ") }, fill = fill, shadow = BrutalDims.ShadowSmall + 1.dp, contentColor = fg) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
            content()
        }
    }
}

/** Тумблер: квадратная дорожка, заливка акцентом во включённом состоянии, тёмный «ползунок» с обводкой. */
@Composable
fun BrutalSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val w = 56.dp
    val h = 32.dp
    val thumb = 22.dp
    Box(
        modifier
            .size(width = w + 4.dp, height = BrutalDims.MinTouch)
            .padding(2.dp)
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .semantics {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = if (checked) "включено" else "выключено"
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(w, h).background(if (checked) Brutal.accent else Brutal.bg, BrutalShape).border(BrutalDims.Border, Brutal.ink, BrutalShape),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(thumb).background(Brutal.ink, BrutalShape))
        }
    }
}

/** Линейный прогресс: рамка и акцентная заливка; без значения -- бегущий блок. */
@Composable
fun BrutalProgress(progress: Float?, modifier: Modifier = Modifier, height: Dp = 18.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Brutal.bg, BrutalShape).border(BrutalDims.Border, Brutal.ink, BrutalShape).clipToBounds()) {
        if (progress != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Brutal.accent))
            if (progress in 0.01f..0.99f) {
                // чёрная «кромка» заливки
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).drawBehind {
                    drawRect(androidx.compose.ui.graphics.Color.Black, Offset(size.width - 3.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                })
            }
        } else {
            val t = rememberInfiniteTransition(label = "indeterminate")
            val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "x")
            BoxWithFraction(x)
        }
    }
}

@Composable
private fun BoxScope.BoxWithFraction(x: Float) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val blockW = maxWidth * 0.3f
        Box(Modifier.offset { IntOffset(((maxWidth - blockW).toPx() * x).roundToInt(), 0) }.width(blockW).fillMaxHeight().background(Brutal.accent))
    }
}

/** Ползунок плеера: квадратный бегунок и рамка дорожки; значение 0..1. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BrutalSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished, modifier = modifier,
        thumb = { Box(Modifier.size(width = 14.dp, height = 28.dp).background(Brutal.ink, BrutalShape).border(2.dp, Brutal.bg, BrutalShape)) },
        track = { state: SliderState ->
            val frac = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(14.dp).background(Brutal.bg, BrutalShape).border(2.dp, Brutal.ink, BrutalShape).clipToBounds()) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Brutal.accent))
            }
        },
    )
}

/** Толстая разделительная линия. */
@Composable
fun BrutalDivider(modifier: Modifier = Modifier, thickness: Dp = BrutalDims.Border) {
    Box(modifier.fillMaxWidth().height(thickness).background(Brutal.ink))
}

/** Заголовок раздела: крупный Unbounded и акцентная «подчёркивающая» плашка. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = Brutal.ink)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(56.dp).height(6.dp).background(Brutal.accent).border(2.dp, Brutal.ink))
    }
}

val BrutalBorderStroke: BorderStroke @Composable get() = BorderStroke(BrutalDims.Border, Brutal.ink)

/**
 * Поле ввода: прямоугольная рамка 3 dp, подпись внутри сверху (моно-стиль), курсор чёрный. Подпись находится внутри самого поля,
 * поэтому `modifier` целиком относится к редактируемому элементу (теги для тестов и скриптов, фокус, вес в строке).
 */
@Composable
fun BrutalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Brutal.ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Brutal.ink),
        singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        modifier = modifier.defaultMinSize(minHeight = BrutalDims.MinTouch),
        decorationBox = { inner ->
            Column(
                Modifier.fillMaxWidth().background(Brutal.bg, BrutalShape).border(BrutalDims.BorderThick, Brutal.ink, BrutalShape).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = Brutal.muted)
                inner()
            }
        },
    )
}
