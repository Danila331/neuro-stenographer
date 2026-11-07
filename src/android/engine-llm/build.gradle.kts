import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// Архитектура ARM для ggml: baseline с dotprod (быстрые int8-ядра). Устройства без dotprod обрабатываются
// проверкой LlamaAssistant.availability() -- LLM отключается, приложение не падает.
val armArch = providers.gradleProperty("neurosteno.llm.arm_arch").orElse("armv8.2-a+dotprod")

android {
    namespace = "ru.neurosteno.llm"
    compileSdk = 36
    // Фиксируем NDK: должен совпадать с тем, что ставит scripts/sdk_setup.sh
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_CPU_ARM_ARCH=${armArch.get()}",
                    "-DGGML_NATIVE=OFF",
                )
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    testOptions {
        // тесты нативной части на хосте: каталог с libneurosteno_llm (scripts/build_host_jni.sh), LLM_HOST_BUILD; нет -- тесты пропускаются
        unitTests.all { test -> System.getenv("LLM_HOST_BUILD")?.let { test.systemProperty("java.library.path", it) } }
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

    testImplementation(libs.junit4)
}
