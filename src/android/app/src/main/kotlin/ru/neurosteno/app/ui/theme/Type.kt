package ru.neurosteno.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.neurosteno.app.R

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val BodyFamily = FontFamily(
    variable(R.font.golos_text, 400), variable(R.font.golos_text, 500),
    variable(R.font.golos_text, 600), variable(R.font.golos_text, 700),
)
val HeadlineFamily = BodyFamily
val MonoFamily = FontFamily(variable(R.font.jetbrains_mono, 400), variable(R.font.jetbrains_mono, 500))
val MonoStyle = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = BodyFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

val BrutalTypography = Typography(
    displayLarge = type(48, 58, FontWeight.Medium),
    displayMedium = type(38, 48, FontWeight.Medium),
    displaySmall = type(32, 42, FontWeight.Medium),
    headlineLarge = type(32, 40, FontWeight.SemiBold),
    headlineMedium = type(27, 35, FontWeight.SemiBold),
    headlineSmall = type(22, 30, FontWeight.Medium),
    titleLarge = type(20, 28, FontWeight.SemiBold),
    titleMedium = type(16, 24, FontWeight.Medium),
    titleSmall = type(14, 21, FontWeight.Medium),
    bodyLarge = type(16, 26),
    bodyMedium = type(14, 22),
    bodySmall = type(12, 19),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(11, 16, FontWeight.Medium),
)
