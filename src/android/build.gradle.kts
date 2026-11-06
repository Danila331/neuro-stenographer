// Плагины Kotlin объявлены в корне (apply false), чтобы подгрузиться один раз в общий classloader.
// Плагины AGP подключаются в самих Android-модулях: так `:core:test` не требует ни AGP, ни SDK.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
