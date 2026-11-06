import java.util.Properties

rootProject.name = "neurosteno"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Prebuilt AAR sherpa-onnx: scripts/fetch_sherpa_onnx.sh кладёт его в локальный Maven (в git не хранится)
        maven {
            url = uri(File(rootDir, "third_party/maven"))
            content { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Модули:
//   :core            -- чистый Kotlin/JVM, собирается и тестируется только с JDK 17 (без Android SDK);
//   :audio, :engine-sherpa, :engine-llm, :app -- Android; подключаются, только если найден готовый SDK
//   (platforms/android-36 и build-tools) либо принудительно: -Pneurosteno.android=true.
//   -Pneurosteno.android=false отключает Android-модули даже при наличии SDK.
// ---------------------------------------------------------------------------------------------
val androidMode: String = providers.gradleProperty("neurosteno.android").orNull?.lowercase() ?: "auto"

fun findAndroidSdk(): File? {
    val fromLocalProps = File(rootDir, "local.properties").takeIf { it.isFile }?.let { f ->
        Properties().also { p -> f.inputStream().use(p::load) }.getProperty("sdk.dir")
    }
    return listOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), fromLocalProps)
        .asSequence()
        .filterNotNull()
        .filter { it.isNotBlank() }
        .map(::File)
        .firstOrNull { it.isDirectory }
}

val sdk = findAndroidSdk()
val sdkReady = sdk != null &&
    File(sdk, "platforms/android-36").isDirectory &&
    (File(sdk, "build-tools").list()?.isNotEmpty() == true)

val includeAndroid = when (androidMode) {
    "true" -> true
    "false" -> false
    else -> sdkReady
}

include(":core")
if (includeAndroid) {
    include(":audio", ":engine-sherpa", ":engine-llm", ":app")
    if (!File(rootDir, "third_party/maven/com/k2fsa/sherpa/onnx/sherpa-onnx").isDirectory) {
        throw GradleException("Нет sherpa-onnx AAR: выполните src/android/scripts/fetch_sherpa_onnx.sh (или -Pneurosteno.android=false)")
    }
    logger.lifecycle("neurosteno: Android-модули включены (SDK: $sdk)")
} else {
    logger.lifecycle(
        "neurosteno: Android SDK не готов (нужен platforms/android-36 + build-tools) -- " +
            "собирается только :core. Принудительно: -Pneurosteno.android=true",
    )
}
