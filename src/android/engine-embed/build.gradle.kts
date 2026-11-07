import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.neurosteno.embed"
    compileSdk = 36
    // Фиксируем NDK: должен совпадать с тем, что ставит scripts/sdk_setup.sh
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release") } }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    // ONNX Runtime -- та же libonnxruntime.so, что в sherpa-onnx (см. embed_jni.cpp: почему не официальный onnxruntime-android);
    // для компиляции Kotlin не нужна, поэтому зависимости от ORT нет.

    testImplementation(libs.junit4)
    testImplementation(project(":store-objectbox")) // SearchEvalTest сравнивает ObjectBox HNSW с перебором
    testImplementation(libs.objectbox.macos)
}
