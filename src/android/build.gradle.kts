// Корневой скрипт для сборки ТОЛЬКО :core (достаточно JDK 17, без Android SDK и без загрузки AGP).
// При подключении Android-модулей вместо него используется build.android.gradle.kts (см. settings.gradle.kts).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
