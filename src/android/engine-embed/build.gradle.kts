import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.neurosteno.embed"
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
    // Официальный ONNX Runtime для Android (MIT, Maven Central). Нативная libonnxruntime.so общая с sherpa-onnx (app: pickFirsts).
    api(libs.onnxruntime.android)

    testImplementation(libs.junit4)
    testImplementation(project(":store-objectbox")) // SearchEvalTest сравнивает ObjectBox HNSW с перебором
    testImplementation(libs.objectbox.macos)
    testImplementation(libs.onnxruntime.desktop) // Java-API с нативной библиотекой для macOS: тесты эмбеддера на хосте
}
