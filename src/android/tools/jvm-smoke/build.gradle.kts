import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val root = System.getenv("NEUROSTENO_ROOT") ?: error("Выполните: source scripts/env.sh")
val deps = file("$root/.cache/android-deps/sherpa-onnx-${libs.versions.sherpa.onnx.get()}")
val aar = File(deps, "sherpa-onnx-${libs.versions.sherpa.onnx.get()}.aar")
val nativeJar = File(deps, "sherpa-onnx-native-lib-osx-aarch64-${libs.versions.sherpa.onnx.get()}.jar")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xlambdas=class") // как в :engine-sherpa (JNI sherpa-onnx ищет invoke(IIJ)I)
    }
}

sourceSets {
    main {
        java.srcDirs("src/stubs")
        kotlin.srcDirs("../../core/src/main/kotlin", "../../engine-sherpa/src/main/kotlin")
    }
}

// classes.jar из AAR (Kotlin API) и dylib'ы для macOS из официального native-lib jar
val extractSherpa by tasks.registering(Sync::class) {
    from(zipTree(aar)) { include("classes.jar"); rename { "sherpa-classes.jar" } }
    into(layout.buildDirectory.dir("sherpa"))
}
val extractNative by tasks.registering(Sync::class) {
    from(zipTree(nativeJar)) { include("sherpa-onnx/native/osx-aarch64/*"); eachFile { path = name } }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("native"))
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(files(layout.buildDirectory.file("sherpa/sherpa-classes.jar")).builtBy(extractSherpa))
}

application {
    mainClass.set("ru.neurosteno.smoke.MainKt")
}

tasks.named<JavaExec>("run") {
    dependsOn(extractNative)
    val nativeDir = layout.buildDirectory.dir("native").get().asFile.absolutePath
    jvmArgs("-Djava.library.path=$nativeDir")
    environment("DYLD_LIBRARY_PATH", nativeDir)
    workingDir = projectDir
}
