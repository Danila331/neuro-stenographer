package ru.neurosteno.core

import java.io.File

/** Поиск корня репозитория и корня Android-проекта из рабочей папки теста (cwd = модуль `core`). */
internal object TestPaths {
    val repoRoot: File by lazy {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "config/tiers").isDirectory) d = d.parentFile
        checkNotNull(d) { "Не найден корень репозитория (config/tiers) выше ${File("").absolutePath}" }
    }
    val androidRoot: File get() = File(repoRoot, "src/android")
}
