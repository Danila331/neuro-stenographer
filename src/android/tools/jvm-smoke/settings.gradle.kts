// Отдельная сборка (НЕ часть основного проекта): прогон реального пайплайна с адаптерами sherpa-onnx на JVM хоста (macOS arm64)
// и моделями из $NEUROSTENO_ROOT/.cache/models. Запуск: scripts/jvm_smoke.sh <wav> [tier]
rootProject.name = "jvm-smoke"

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
