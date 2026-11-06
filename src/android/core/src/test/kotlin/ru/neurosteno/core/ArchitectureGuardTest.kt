package ru.neurosteno.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Статические проверки контракта безопасности (ARCHITECTURE «Безопасность и приватность»):
 * - модули обработки (`:core`, `:audio`, `:engine-*`) не содержат сетевого кода и сетевых зависимостей;
 * - INTERNET в манифесте `:app` допустим только ради менеджера моделей, остальные разрешения -- из белого списка.
 * Работают на исходниках, поэтому проходят и без Android SDK.
 */
class ArchitectureGuardTest {
    private val processingModules = listOf("core", "audio", "engine-sherpa", "engine-llm")

    private val forbiddenSourceTokens = listOf(
        "java.net.URL", "java.net.HttpURLConnection", "java.net.Socket", "java.net.URI(\"http",
        "javax.net.", "okhttp3", "retrofit2", "io.ktor", "android.net.http", "android.net.ConnectivityManager",
        "WebView", "URLConnection", "HttpClient", "http://", "https://",
    )
    private val forbiddenDependencyTokens = listOf(
        "okhttp", "retrofit", "ktor", "volley", "cronet", "apollo", "fuel", "coil", "glide", "firebase", "play-services",
    )

    /** URL допустимы только в комментариях/документации; в проверке пропускаем строки комментариев. */
    private fun codeLines(f: File): List<Pair<Int, String>> = f.readLines().mapIndexedNotNull { i, l ->
        val t = l.trim()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else (i + 1) to l
    }

    private fun sources(module: String): List<File> {
        val dir = File(TestPaths.androidRoot, "$module/src")
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter {
            it.isFile && it.extension in setOf("kt", "java", "xml", "cpp", "h", "hpp", "c", "cc", "txt")
        }.toList()
    }

    @Test
    fun `processing modules contain no network code`() {
        val violations = ArrayList<String>()
        for (m in processingModules) {
            // каталог с исходниками llama.cpp скачивается скриптом и в проверку не входит
            sources(m).filter { !it.path.contains("/cpp/llama.cpp/") && it.name != "ArchitectureGuardTest.kt" }.forEach { f ->
                codeLines(f).forEach { (n, rawLine) ->
                    // пространства имён XML (xmlns) -- не сетевой код
                    val line = rawLine.replace(Regex("""xmlns(:\w+)?="[^"]*""""), "")
                    forbiddenSourceTokens.filter { line.contains(it) }.forEach {
                        violations += "$m: ${f.relativeTo(TestPaths.androidRoot)}:$n содержит '$it'"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `processing modules declare no network dependencies`() {
        val violations = ArrayList<String>()
        for (m in processingModules) {
            val f = File(TestPaths.androidRoot, "$m/build.gradle.kts")
            if (!f.isFile) continue
            codeLines(f).forEach { (n, line) ->
                forbiddenDependencyTokens.filter { line.lowercase().contains(it) }.forEach {
                    violations += "$m/build.gradle.kts:$n зависимость '$it'"
                }
            }
        }
        // версионный каталог не должен тянуть сетевые библиотеки в принципе
        File(TestPaths.androidRoot, "gradle/libs.versions.toml").readLines().forEachIndexed { i, l ->
            forbiddenDependencyTokens.filter { l.lowercase().contains(it) }.forEach { violations += "libs.versions.toml:${i + 1} '$it'" }
        }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `app manifest permissions are from the allow list`() {
        val manifest = File(TestPaths.androidRoot, "app/src/main/AndroidManifest.xml")
        if (!manifest.isFile) return // Android-часть ещё не создана
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest.readText()).map { it.groupValues[1] }.toSet()
        val allowed = setOf(
            "android.permission.INTERNET", // только менеджер моделей
            "android.permission.ACCESS_NETWORK_STATE", // менеджер моделей: проверка Wi-Fi/метрики
            "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
        )
        assertTrue(allowed.containsAll(declared), "Недопустимые разрешения: ${declared - allowed}")
    }

    @Test
    fun `network code is confined to the models manager package`() {
        // Сетевой код допустим только в app/src/**/ru/neurosteno/app/models/download/
        val app = File(TestPaths.androidRoot, "app/src")
        if (!app.isDirectory) return
        val bad = app.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains("/ru/neurosteno/app/models/download/") }
            .filter { f -> codeLines(f).any { (_, l) -> listOf("java.net.URL", "HttpURLConnection", "okhttp3").any { l.contains(it) } } }
            .map { it.relativeTo(TestPaths.androidRoot).path }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
