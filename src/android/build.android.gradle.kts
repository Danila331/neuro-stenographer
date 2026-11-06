// Корневой скрипт для сборки С Android-модулями (settings.gradle.kts выбирает его вместо build.gradle.kts, когда
// найден готовый SDK либо передан -Pneurosteno.android=true). Все плагины объявлены здесь с `apply false`, чтобы
// Kotlin Gradle Plugin и AGP жили в ОДНОМ classloader: иначе KGP не видит классы AGP (NoClassDefFoundError).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
