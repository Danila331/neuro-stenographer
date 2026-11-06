package ru.neurosteno.sherpa

import java.io.File
import java.io.FileNotFoundException

/** Корень активного набора моделей; пути из `config/tiers/<tier>.json` -- относительные. */
class ModelRoot(val dir: File) {
    fun file(relative: String): File = File(dir, relative).also {
        if (!it.isFile) throw FileNotFoundException("Нет файла модели: $relative (набор ${dir.name})")
    }

    fun directory(relative: String): File = File(dir, relative).also {
        if (!it.isDirectory) throw FileNotFoundException("Нет каталога модели: $relative (набор ${dir.name})")
    }

    fun optionalFile(relative: String?): File? = relative?.let { File(dir, it) }?.takeIf { it.isFile }
}
