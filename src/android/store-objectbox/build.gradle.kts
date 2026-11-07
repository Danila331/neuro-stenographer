import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.legacy.kapt)
    alias(libs.plugins.objectbox)
}

android {
    namespace = "ru.neurosteno.store"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    implementation(libs.objectbox.kotlin)
    kapt(libs.objectbox.processor)

    testImplementation(libs.junit4)
    testImplementation(libs.objectbox.macos) // нативная библиотека для тестов на хосте (macOS)
}
