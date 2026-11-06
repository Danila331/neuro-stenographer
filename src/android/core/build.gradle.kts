import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.allWarningsAsErrors.set(true) // production-код без предупреждений
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.jupiter.get()}"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Путь к JSON-кейсам Aligner из Python-эталона (src/eval/refpipe/tests/align_cases.json)
    systemProperty("neurosteno.alignCases", providers.gradleProperty("neurosteno.alignCases").orNull ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
