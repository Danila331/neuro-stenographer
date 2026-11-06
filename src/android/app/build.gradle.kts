import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Репозиторий: .../src/android -> корень на два уровня выше (в рабочей копии и в основном дереве одинаково). */
val repoRoot: File = rootProject.projectDir.parentFile.parentFile

// Подпись release-APK локальным ключом: scripts/build_all.sh генерирует его в $ROOT/.local/keystore и передаёт -P-свойства
val keystorePath = providers.gradleProperty("neurosteno.keystore")

android {
    namespace = "ru.neurosteno.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.neurosteno.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Один код, три набора моделей + online (без встроенных моделей). applicationId: ru.neurosteno.app.<tier>
    flavorDimensions += "tier"
    productFlavors {
        create("lite") {
            dimension = "tier"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            resValue("string", "app_name", "Нейро-стенографист Lite")
            buildConfigField("String", "TIER", "\"lite\"")
            buildConfigField("boolean", "BUNDLED_MODELS", "true")
        }
        create("medium") {
            dimension = "tier"
            applicationIdSuffix = ".medium"
            versionNameSuffix = "-medium"
            resValue("string", "app_name", "Нейро-стенографист Medium")
            buildConfigField("String", "TIER", "\"medium\"")
            buildConfigField("boolean", "BUNDLED_MODELS", "true")
        }
        create("hard") {
            dimension = "tier"
            applicationIdSuffix = ".hard"
            versionNameSuffix = "-hard"
            resValue("string", "app_name", "Нейро-стенографист Hard")
            buildConfigField("String", "TIER", "\"hard\"")
            buildConfigField("boolean", "BUNDLED_MODELS", "true")
        }
        create("online") {
            dimension = "tier"
            applicationIdSuffix = ".online"
            versionNameSuffix = "-online"
            resValue("string", "app_name", "Нейро-стенографист Online")
            buildConfigField("String", "TIER", "\"online\"")
            buildConfigField("boolean", "BUNDLED_MODELS", "false")
        }
    }

    signingConfigs {
        if (keystorePath.isPresent) {
            create("neurosteno") {
                storeFile = file(keystorePath.get())
                storePassword = providers.gradleProperty("neurosteno.keystorePassword").get()
                keyAlias = providers.gradleProperty("neurosteno.keyAlias").getOrElse("neurosteno")
                keyPassword = providers.gradleProperty("neurosteno.keyPassword")
                    .orElse(providers.gradleProperty("neurosteno.keystorePassword")).get()
            }
        }
    }

    buildTypes {
        release {
            // Без R8: JNI (sherpa-onnx, llama) и kotlinx.serialization дружат с минификацией плохо, а размер APK задают модели
            isMinifyEnabled = false
            signingConfigs.findByName("neurosteno")?.let { signingConfig = it }
        }
        debug {
            // отладочная сборка подписывается стандартным debug-ключом AGP
        }
    }

    // Модели лежат в assets без сжатия: быстрый доступ без распаковки в память и меньше нагрузка при копировании
    androidResources {
        noCompress += listOf("onnx", "gguf", "bin", "txt")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += "**/libc++_shared.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// --- config/tiers/<tier>.json -> assets/tier.json каждого варианта (online использует medium как конфиг по умолчанию) ---
abstract class CopyTierConfig : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        source.get().asFile.copyTo(File(dir, "tier.json"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val flavor = variant.flavorName ?: return@onVariants
        val configName = if (flavor == "online") "medium" else flavor
        val taskName = "copy" + variant.name.replaceFirstChar { it.uppercase() } + "TierConfig"
        val copyTask = tasks.register<CopyTierConfig>(taskName) {
            source.set(File(repoRoot, "config/tiers/$configName.json"))
            outputDir.set(layout.buildDirectory.dir("generated/tierConfig/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, CopyTierConfig::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":audio"))
    implementation(project(":engine-sherpa"))
    implementation(project(":engine-llm"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
}
