package ru.neurosteno.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Фиолетовый hero референса с мягкой статичной диагональной световой дугой. */
@Composable
fun RevolutHeroBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(Brush.verticalGradient(listOf(Brutal.hero, Brutal.heroEnd)))) {
        Canvas(Modifier.matchParentSize()) {
            val path = Path().apply {
                moveTo(-size.width * 0.16f, size.height * 1.04f)
                cubicTo(
                    size.width * 0.34f, size.height * 0.94f,
                    size.width * 0.88f, size.height * 0.52f,
                    size.width * 1.08f, size.height * 0.06f,
                )
            }
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.025f),
                style = Stroke(width = 92.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.045f),
                style = Stroke(width = 54.dp.toPx(), cap = StrokeCap.Round),
            )
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.055f),
                style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        content()
    }
}

@Composable
fun HeroCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.20f))
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
    }
}

@Composable
fun HeroQuickAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 80.dp)
            .clip(ControlShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) { icon() }
        }
        Text(
            label,
            modifier = Modifier.padding(top = 7.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
