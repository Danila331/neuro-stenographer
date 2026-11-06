package ru.neurosteno.app

import android.content.Context
import java.io.File
import ru.neurosteno.app.models.AssetModelSource
import ru.neurosteno.core.TierConfig
import ru.neurosteno.core.models.ModelStore

/**
 * Простейший «граф зависимостей» приложения. Ничего не бросает наружу: битый/отсутствующий конфиг превращается
 * в [configError], который UI показывает пользователю (приложение не падает при старте).
 */
class AppGraph(private val context: Context) {

    /** Каталог наборов моделей: `filesDir/models/<set-id>/` (+ `manifest.json` в каждом). */
    val modelStore: ModelStore by lazy { ModelStore(File(context.filesDir, "models")) }

    /** Идентификатор встроенного набора моделей; null в варианте online (моделей в APK нет). */
    val bundledSetId: String? by lazy {
        if (!BuildConfig.BUNDLED_MODELS) null
        else context.assets.list(AssetModelSource.ROOT)?.firstOrNull { id ->
            context.assets.list("${AssetModelSource.ROOT}/$id")?.contains("manifest.json") == true
        }
    }

    fun bundledSource(): AssetModelSource? = bundledSetId?.let { AssetModelSource(context.assets, it) }

    private val configResult: Result<Pair<TierConfig, String>> by lazy {
        runCatching {
            val text = context.assets.open("tier.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            TierConfig.parse(text) to text
        }
    }

    val tierConfig: TierConfig? get() = configResult.getOrNull()?.first
    val tierConfigText: String? get() = configResult.getOrNull()?.second
    val configError: String? get() = configResult.exceptionOrNull()?.message
}
