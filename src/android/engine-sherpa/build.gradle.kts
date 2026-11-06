import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.neurosteno.sherpa"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // JNI sherpa-onnx вызывает callback по сигнатуре invoke(IIJ)I -- её генерирует только класс-лямбда (не invokedynamic)
        freeCompilerArgs.add("-Xlambdas=class")
    }
}

dependencies {
    api(project(":core"))
    // Prebuilt AAR sherpa-onnx: скачивается scripts/fetch_sherpa_onnx.sh в локальный Maven (third_party/maven), в git не хранится.
    api(libs.sherpa.onnx)

    testImplementation(libs.junit4)
}
