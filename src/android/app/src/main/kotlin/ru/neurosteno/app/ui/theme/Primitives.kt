package ru.neurosteno.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Нейтральная поверхность Revolut DNA: крупный радиус, мягкая глубина только по запросу. */
@Composable
fun BrutalSurface(
    modifier: Modifier = Modifier,
    fill: Color = Brutal.surface,
    shadow: Dp = BrutalDims.Shadow,
    borderWidth: Dp = 0.dp,
    contentColor: Color = Color.Unspecified,
    shadowColor: Color = Brutal.ink,
    content: @Composable BoxScope.() -> Unit,
) {
    val fg = when {
        contentColor != Color.Unspecified -> contentColor
        fill == Brutal.ink -> Brutal.onInk
        fill == Brutal.cardDark -> Brutal.onCardDark
        fill == Brutal.accent || fill == Brutal.error -> Brutal.onAccent
        else -> Brutal.ink
    }
    val elevation = if (shadow > 0.dp) {
        Modifier.shadow(
            elevation = shadow.coerceAtMost(12.dp), shape = BrutalShape, clip = false,
            ambientColor = shadowColor.copy(alpha = 0.10f), spotColor = shadowColor.copy(alpha = 0.14f),
        )
    } else Modifier
    val outline = if (borderWidth > 0.dp) Modifier.border(borderWidth, Brutal.outlineSoft, BrutalShape) else Modifier
    Box(
        modifier
            .then(elevation)
            .background(fill, BrutalShape)
            .then(outline),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) { content() }
    }
}

@Composable
fun BrutalCard(
    modifier: Modifier = Modifier,
    fill: Color = Brutal.surface,
    shadow: Dp = BrutalDims.Shadow,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    contentColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val click = if (onClick != null) {
        Modifier
            .clip(BrutalShape)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else Modifier
    BrutalSurface(modifier, fill = fill, shadow = shadow, contentColor = contentColor) {
        Column(Modifier.then(click).padding(contentPadding)) { content() }
    }
}

enum class BrutalButtonStyle { Primary, Secondary, Ink, Danger }

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
    val scale by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, tween(100), label = "buttonPress")
    val fill = when (style) {
        BrutalButtonStyle.Primary -> Brutal.accent
        BrutalButtonStyle.Secondary -> Brutal.panel
        BrutalButtonStyle.Ink -> Brutal.cardDark
        BrutalButtonStyle.Danger -> Brutal.error
    }
    val contentColor = when (style) {
        BrutalButtonStyle.Secondary -> Brutal.ink
        BrutalButtonStyle.Ink -> Brutal.onCardDark
        else -> Brutal.onAccent
    }
    Row(
        modifier
            .defaultMinSize(minHeight = height)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .scale(scale)
            .clip(ControlShape)
            .background(if (enabled) fill else Brutal.panel)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides (if (enabled) contentColor else Brutal.muted)) { content() }
    }
}

@Composable
fun ButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalContentColor.current,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        modifier = modifier,
    )
}

@Composable
fun BrutalIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fill: Color = Color.Unspecified,
    enabled: Boolean = true,
    contentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    val background = when {
        !enabled -> Brutal.panel
        fill == Color.Unspecified -> Color.Transparent
        else -> fill
    }
    Box(
        modifier
            .size(BrutalDims.MinTouch)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val iconColor = when {
            !enabled -> Brutal.muted
            contentColor != Color.Unspecified -> contentColor
            fill == Brutal.ink -> Brutal.onInk
            fill == Brutal.cardDark -> Brutal.onCardDark
            fill == Brutal.accent -> Brutal.onAccent
            else -> Brutal.ink
        }
        CompositionLocalProvider(LocalContentColor provides iconColor) { content() }
    }
}

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
            .clip(PillShape)
            .background(if (selected) Brutal.accentSoft else Brutal.panel)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; stateDescription = if (selected) "выбрано" else "не выбрано" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (!enabled) Brutal.muted else if (selected) Brutal.accent else Brutal.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SpeakerChip(name: String, speakerId: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Brutal.panel, PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(8.dp).background(speakerFill(speakerId), CircleShape))
        Text(name, style = MaterialTheme.typography.labelMedium, color = Brutal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SpeakerSquare(speakerId: String, size: Dp = 18.dp) {
    Box(Modifier.size(size).background(speakerFill(speakerId), CircleShape))
}

@Composable
fun BrutalTag(text: String, modifier: Modifier = Modifier, fill: Color = Brutal.panel, textStyle: TextStyle = MonoStyle) {
    val fg = if (fill == Brutal.ink) Brutal.onInk else if (fill == Brutal.accent || fill == Brutal.error) Brutal.onAccent else Brutal.ink
    Box(modifier.background(fill, PillShape).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Text(text, style = textStyle, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

enum class PlaqueKind { Accent, Neutral, Error, Ink }

@Composable
fun BrutalPlaque(
    text: String,
    modifier: Modifier = Modifier,
    kind: PlaqueKind = PlaqueKind.Neutral,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val fill = when (kind) {
        PlaqueKind.Accent -> Brutal.accentSoft
        PlaqueKind.Neutral -> Brutal.panel
        PlaqueKind.Error -> Brutal.errorSoft
        PlaqueKind.Ink -> Brutal.ink
    }
    val fg = when (kind) {
        PlaqueKind.Ink -> Brutal.onInk
        PlaqueKind.Error -> Brutal.error
        else -> Brutal.ink
    }
    BrutalSurface(
        modifier.fillMaxWidth().semantics { contentDescription = listOfNotNull(title, text).joinToString(". ") },
        fill = fill,
        borderWidth = 0.dp,
        contentColor = fg,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
            content()
        }
    }
}

@Composable
fun BrutalSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Box(
        modifier
            .size(width = 60.dp, height = BrutalDims.MinTouch)
            .clip(PillShape)
            .clickable(role = Role.Switch, onClick = { onCheckedChange(!checked) })
            .semantics {
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                stateDescription = if (checked) "включено" else "выключено"
                if (contentDescription != null) this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .background(if (checked) Brutal.accent else Brutal.panel, PillShape)
                .border(BrutalDims.Border, if (checked) Brutal.accent else Brutal.outline, PillShape)
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .background(if (checked) Brutal.onAccent else Brutal.muted, CircleShape),
            )
        }
    }
}

@Composable
fun BrutalProgress(progress: Float?, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Brutal.panel, PillShape).clip(PillShape)) {
        if (progress != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Brutal.accent))
        } else {
            val transition = rememberInfiniteTransition(label = "indeterminate")
            val x by transition.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
                label = "x",
            )
            BoxWithFraction(x)
        }
    }
}

@Composable
private fun BoxScope.BoxWithFraction(x: Float) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
        val blockWidth = maxWidth * 0.3f
        Box(
            Modifier
                .offset { IntOffset(((maxWidth - blockWidth).toPx() * x).roundToInt(), 0) }
                .width(blockWidth)
                .fillMaxHeight()
                .background(Brutal.accent),
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BrutalSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        thumb = { Box(Modifier.size(18.dp).background(Brutal.accent, CircleShape).border(3.dp, Brutal.surface, CircleShape)) },
        track = { state: SliderState ->
            val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).background(Brutal.panel, PillShape).clip(PillShape)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Brutal.accent))
            }
        },
    )
}

@Composable
fun BrutalDivider(modifier: Modifier = Modifier, thickness: Dp = BrutalDims.Border) {
    Box(modifier.fillMaxWidth().height(thickness).background(Brutal.outlineSoft))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Brutal.ink, modifier = modifier)
}

val BrutalBorderStroke: BorderStroke @Composable get() = BorderStroke(BrutalDims.Border, Brutal.outlineSoft)

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
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        interactionSource = interaction,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) Brutal.ink else Brutal.muted),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Brutal.accent),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        modifier = modifier.defaultMinSize(minHeight = BrutalDims.MinTouch),
        decorationBox = { inner ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (enabled) Brutal.panel else Brutal.bg, ControlShape)
                    .border(if (focused) 2.dp else 0.dp, if (focused) Brutal.accent else Color.Transparent, ControlShape)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = Brutal.muted)
                inner()
            }
        },
    )
}
