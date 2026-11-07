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

/**
 * Шрифты (все -- SIL OFL 1.1, вшиты в APK, кириллица проверена; тексты лицензий -- `assets/licenses/`):
 * заголовки -- Unbounded, основной текст -- Golos Text, время и числа -- JetBrains Mono. Все три -- вариативные: нужные
 * начертания задаются осью `wght` (minSdk 26).
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val HeadlineFamily = FontFamily(variable(R.font.unbounded, 600), variable(R.font.unbounded, 800), variable(R.font.unbounded, 900))
val BodyFamily = FontFamily(variable(R.font.golos_text, 400), variable(R.font.golos_text, 500), variable(R.font.golos_text, 700), variable(R.font.golos_text, 900))
val MonoFamily = FontFamily(variable(R.font.jetbrains_mono, 500), variable(R.font.jetbrains_mono, 700))

/** Моно-стиль для таймкодов и чисел. */
val MonoStyle = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 18.sp)

val BrutalTypography = Typography(
    displayLarge = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(900), fontSize = 56.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(900), fontSize = 44.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(800), fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(800), fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(800), fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(800), fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(800), fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = HeadlineFamily, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight(900), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight(900), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight(700), fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight(700), fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
)
