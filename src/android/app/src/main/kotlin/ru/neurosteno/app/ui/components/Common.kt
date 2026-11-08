package ru.neurosteno.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon as M3Icon
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalIconButton
import ru.neurosteno.app.ui.theme.BrutalPalette
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.BrutalShape
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.ControlShape
import ru.neurosteno.app.ui.theme.PlaqueKind
import ru.neurosteno.app.ui.theme.SectionTitle
import ru.neurosteno.app.ui.theme.SheetShape
import ru.neurosteno.app.ui.theme.SpeakerSquare
import ru.neurosteno.app.ui.theme.WordmarkStyle

/** Спокойная верхняя панель: только содержание, действия и тонкий разделитель. */
@Composable
fun NeuroTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    titleMaxLines: Int = 1,
    wordmark: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Brutal.surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onBack != null) {
                BrutalIconButton(onClick = onBack, contentDescription = "Назад") {
                    M3Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            }
            val big = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
            val baseStyle = if (wordmark) WordmarkStyle else MaterialTheme.typography.titleLarge
            val titleStyle = if (big) baseStyle.copy(fontSize = baseStyle.fontSize * 0.82f, lineHeight = baseStyle.lineHeight * 0.82f) else baseStyle
            Text(
                title,
                style = titleStyle,
                color = Brutal.ink,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
            )
            actions()
        }
    }
}

@Composable
fun BrutalDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    text: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BrutalCard(
            modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            shadow = 2.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Brutal.ink, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f, fill = false).heightIn(max = 460.dp)) { text() }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

@Composable
fun DialogButton(label: String, onClick: () -> Unit, primary: Boolean = true, danger: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    BrutalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        style = when {
            danger -> BrutalButtonStyle.Danger
            primary -> BrutalButtonStyle.Primary
            else -> BrutalButtonStyle.Secondary
        },
        height = 48.dp,
    ) { ButtonLabel(label) }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Отмена",
    destructive: Boolean = false,
) {
    BrutalDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = { DialogButton(confirmLabel, onConfirm, primary = !destructive, danger = destructive) },
        dismissButton = { DialogButton(dismissLabel, onDismiss, primary = false) },
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Brutal.ink, modifier = Modifier.verticalScroll(rememberScrollState()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrutalSheet(onDismissRequest: () -> Unit, sheetState: SheetState, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = Brutal.surface,
        contentColor = Brutal.ink,
        scrimColor = BrutalPalette.Scrim.copy(alpha = 0.48f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).background(Brutal.outline, CircleShape))
            }
        },
    ) { content() }
}

@Composable
fun BrutalSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> BrutalSnackbar(data) }
}

@Composable
private fun BrutalSnackbar(data: SnackbarData) {
    Row(
        Modifier
            .padding(12.dp)
            .fillMaxWidth()
            .background(Brutal.ink, ControlShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, color = Brutal.onInk, modifier = Modifier.weight(1f))
        data.visuals.actionLabel?.let { label ->
            BrutalButton(onClick = { data.performAction() }, height = 40.dp, modifier = Modifier.defaultMinSize(minWidth = 88.dp)) {
                ButtonLabel(label)
            }
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    container: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
    BrutalCard(modifier.fillMaxWidth(), fill = if (container == Color.Unspecified) Brutal.surface else container) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) SectionTitle(title)
            content()
        }
    }
}

@Composable
fun SpeakerDot(speakerId: String, size: Int = 18) = SpeakerSquare(speakerId, size.dp)

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, color = Brutal.ink, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brutal.outlineSoft))
    }
}

@Composable
fun IconLabel(icon: ImageVector, text: String, tint: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val color = if (tint == Color.Unspecified) Brutal.ink else tint
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        M3Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))

@Composable
fun MessageCard(text: String, modifier: Modifier = Modifier, isError: Boolean = false, content: @Composable () -> Unit = {}) {
    BrutalPlaque(text, modifier, kind = if (isError) PlaqueKind.Error else PlaqueKind.Neutral) { content() }
}
