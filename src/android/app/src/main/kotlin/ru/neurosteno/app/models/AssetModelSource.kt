package ru.neurosteno.app.models

import android.content.res.AssetManager
import java.io.InputStream
import ru.neurosteno.core.models.ModelManifest
import ru.neurosteno.core.models.ModelSource

/** Встроенный набор: `assets/models/<set-id>/{manifest.json, ...файлы}`. */
class AssetModelSource(private val assets: AssetManager, private val setId: String) : ModelSource {
    override fun manifest(): ModelManifest =
        ModelManifest.parse(assets.open("$ROOT/$setId/${ModelManifest.FILE_NAME}").bufferedReader(Charsets.UTF_8).use { it.readText() })

    override fun open(relativePath: String): InputStream = assets.open("$ROOT/$setId/$relativePath", AssetManager.ACCESS_STREAMING)

    companion object {
        const val ROOT = "models"
    }
}
