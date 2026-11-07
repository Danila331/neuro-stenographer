package ru.neurosteno.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared presentation only. Old names retain compatibility with the existing screen contracts. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BrutalSurface(
    modifier: Modifier = Modifier,
    fill: Color = Brutal.surface,
    shadow: Dp = BrutalDims.Shadow,
    borderWidth: Dp = BrutalDims.Border,
    contentColor: Color = Brutal.ink,
    shadowColor: Color = Brutal.ink,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.clip(BrutalShape).background(fill).border(borderWidth.coerceAtMost(1.dp), Brutal.outline, BrutalShape)) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Composable
fun BrutalCard(
    modifier: Modifier = Modifier,
    fill: Color = Brutal.surface,
    shadow: Dp = BrutalDims.Shadow,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val fg = when (fill) {
        Brutal.ink -> Brutal.onInk
        Brutal.accent -> Brutal.onAccent
        Brutal.error -> Brutal.errorText
        else -> Brutal.ink
    }
    val click = if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier
    BrutalSurface(modifier, fill = fill, shadow = shadow, contentColor = fg) {
        Column(Modifier.fillMaxWidth().then(click).padding(contentPadding)) { content() }
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
    val shape = RoundedCornerShape(6.dp)
    val fill = when (style) {
        BrutalButtonStyle.Primary -> Brutal.strong
        BrutalButtonStyle.Secondary -> Brutal.surface
        BrutalButtonStyle.Ink -> Brutal.panel
        BrutalButtonStyle.Danger -> Brutal.error
    }
    val fg = when (style) {
        BrutalButtonStyle.Primary -> Brutal.onStrong
        BrutalButtonStyle.Danger -> Brutal.errorText
        else -> Brutal.ink
    }
    Row(
        modifier.defaultMinSize(minHeight = height).clip(shape)
            .background(if (enabled) fill else Brutal.panel)
            .border(1.dp, if (style == BrutalButtonStyle.Secondary) Brutal.outline else Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) fg else Brutal.muted) { content() }
    }
}

@Composable
fun ButtonLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelLarge, color = LocalContentColor.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun BrutalIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    fill: Color = Brutal.bg,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val bg = when (fill) {
        Brutal.bg, Brutal.surface -> Color.Transparent
        Brutal.accent -> Brutal.strong
        else -> fill
    }
    val fg = when {
        !enabled -> Brutal.muted
        fill == Brutal.accent -> Brutal.onStrong
        fill == Brutal.ink -> Brutal.onInk
        else -> Brutal.muted
    }
    Box(
        modifier.size(BrutalDims.MinTouch).clip(CircleShape).background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides fg) { content() } }
}

@Composable
fun BrutalChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.defaultMinSize(minHeight = BrutalDims.MinTouch, minWidth = 48.dp).clip(shape)
            .background(if (selected) Brutal.accent else Brutal.panel.copy(alpha = 0.55f))
            .border(1.dp, if (selected) Brutal.strong.copy(alpha = 0.22f) else Color.Transparent, shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; stateDescription = if (selected) "выбрано" else "не выбрано" }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (!enabled) Brutal.muted else if (selected) Brutal.onAccent else Brutal.muted)
    }
}

@Composable
fun SpeakerChip(name: String, speakerId: String, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(5.dp)).background(speakerFill(speakerId, Brutal.dark))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(speakerInk(speakerId, Brutal.dark), CircleShape))
        Text(name, style = MaterialTheme.typography.labelMedium, color = Brutal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SpeakerSquare(speakerId: String, size: Dp = 18.dp) {
    Box(Modifier.size(size).background(speakerInk(speakerId, Brutal.dark), CircleShape))
}

@Composable
fun BrutalTag(text: String, modifier: Modifier = Modifier, fill: Color = Brutal.bg, textStyle: TextStyle = MonoStyle) {
    val fg = when (fill) {
        Brutal.ink -> Brutal.onInk
        Brutal.accent -> Brutal.onAccent
        Brutal.error -> Brutal.errorText
        else -> Brutal.muted
    }
    val bg = if (fill == Brutal.bg) Color.Transparent else fill
    Box(modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Text(text, style = textStyle, color = fg, maxLines = 1)
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
        PlaqueKind.Accent -> Brutal.accent
        PlaqueKind.Error -> Brutal.error
        else -> Brutal.panel
    }
    val fg = when (kind) {
        PlaqueKind.Accent -> Brutal.onAccent
        PlaqueKind.Error -> Brutal.errorText
        else -> Brutal.muted
    }
    BrutalSurface(
        modifier.fillMaxWidth().semantics { contentDescription = listOfNotNull(title, text).joinToString(". ") },
        fill = fill, borderWidth = 0.dp, contentColor = fg,
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
    Switch(
        checked, onCheckedChange,
        modifier = modifier.semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Brutal.onStrong, checkedTrackColor = Brutal.strong, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Brutal.muted, uncheckedTrackColor = Brutal.panel, uncheckedBorderColor = Brutal.outline,
        ),
    )
}

@Composable
fun BrutalProgress(progress: Float?, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val mod = modifier.fillMaxWidth().height(height.coerceAtMost(8.dp)).clip(CircleShape)
    if (progress != null) LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) }, modifier = mod, color = Brutal.strong,
        trackColor = Brutal.outline, strokeCap = StrokeCap.Round, drawStopIndicator = {},
    ) else LinearProgressIndicator(modifier = mod, color = Brutal.strong, trackColor = Brutal.outline, strokeCap = StrokeCap.Round)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrutalSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value, onValueChange, modifier, onValueChangeFinished = onValueChangeFinished,
        thumb = { Box(Modifier.size(18.dp).background(Brutal.strong, CircleShape)) },
        track = { state: SliderState ->
            val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Brutal.outline)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Brutal.strong))
            }
        },
    )
}

@Composable
fun BrutalDivider(modifier: Modifier = Modifier, thickness: Dp = BrutalDims.Border) {
    Box(modifier.fillMaxWidth().height(thickness.coerceAtMost(1.dp)).background(Brutal.outline))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium, color = Brutal.ink)
}

val BrutalBorderStroke: BorderStroke @Composable get() = BorderStroke(1.dp, Brutal.outline)

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
    BasicTextField(
        value, onValueChange, enabled = enabled, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Brutal.ink),
        cursorBrush = SolidColor(Brutal.strong), singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        modifier = modifier.defaultMinSize(minHeight = BrutalDims.MinTouch),
        decorationBox = { inner ->
            Column(
                Modifier.fillMaxWidth().background(Brutal.surface, RoundedCornerShape(6.dp))
                    .border(1.dp, Brutal.outline, RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = Brutal.muted)
                inner()
            }
        },
    )
}
