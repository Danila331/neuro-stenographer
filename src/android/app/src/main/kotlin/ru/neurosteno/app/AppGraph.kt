package ru.neurosteno.app

import android.content.Context
import java.io.File
import ru.neurosteno.app.data.HistoryRepository
import ru.neurosteno.app.data.SettingsRepository
import ru.neurosteno.app.models.ApkModelSource
import ru.neurosteno.app.models.AssetModelSource
import ru.neurosteno.core.models.ModelSource
import ru.neurosteno.app.models.Catalog
import ru.neurosteno.app.models.ModelsStatus
import ru.neurosteno.core.TierConfig
import ru.neurosteno.core.models.ModelStore
import ru.neurosteno.core.models.SetState

/**
 * Простейший «граф зависимостей» приложения. Ничего не бросает наружу: битый/отсутствующий конфиг превращается
 * в [configError], который UI показывает пользователю (приложение не падает при старте).
 */
class AppGraph(private val context: Context) {

    /** Каталог наборов моделей: `filesDir/models/<set-id>/` (+ `manifest.json` в каждом). */
    val modelStore: ModelStore by lazy { ModelStore(File(context.filesDir, "models")) }

    val settings: SettingsRepository by lazy { SettingsRepository(context) }

    val history: HistoryRepository by lazy { HistoryRepository(File(context.filesDir, "history")) }

    /** Каталог наборов для загрузки (assets/model_sets.json; в debug-сборке его можно подменить файлом в files/). */
    val catalog: Catalog by lazy { Catalog.load(context, allowOverride = BuildConfig.DEBUG) }

    /** Идентификатор встроенного набора моделей; null в варианте online (моделей в APK нет). */
    val bundledSetId: String? by lazy {
        if (!BuildConfig.BUNDLED_MODELS) null
        else context.assets.list(AssetModelSource.ROOT)?.firstOrNull { id ->
            context.assets.list("${AssetModelSource.ROOT}/$id")?.contains("manifest.json") == true
        }
    }

    /** Источник встроенного набора: чтение APK через ZipFile (без mmap, B44); если не получилось -- AssetManager. */
    fun bundledSource(): ModelSource? = bundledSetId?.let { id ->
        val apk = java.io.File(context.applicationInfo.sourceDir)
        if (ApkModelSource.available(apk, id)) ApkModelSource(apk, id) else AssetModelSource(context.assets, id)
    }

    /** Конфиг уровня [tier] (`assets/tiers/<tier>.json`); для встроенного уровня запасной путь -- `assets/tier.json`. */
    fun configFor(tier: String): Pair<TierConfig, String>? {
        val text = runCatching { context.assets.open("tiers/$tier.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
            ?: if (tier == BuildConfig.TIER || BuildConfig.TIER == "online") {
                runCatching { context.assets.open("tier.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
            } else null
        return text?.let { runCatching { TierConfig.parse(it) to it }.getOrNull() }
    }

    /** Уровень активного набора (из его manifest.json); если набора нет -- уровень сборки. */
    fun activeTier(): String {
        val dir = modelStore.activeDir()
        return dir?.let { modelStore.readManifest(it)?.tier } ?: BuildConfig.TIER.takeIf { it != "online" } ?: "medium"
    }

    /** Конфиг для активного набора моделей: пути моделей из конфига разрешаются внутри каталога именно этого набора. */
    fun activeConfig(): Pair<TierConfig, String>? = configFor(activeTier())

    val configError: String? get() = if (activeConfig() == null) "Не удалось прочитать конфигурацию уровня «${activeTier()}»" else null

    val appContext: Context get() = context.applicationContext

    fun externalFilesDir(): File? = context.getExternalFilesDir(null)

    fun filesDir(): File = context.filesDir

    /** Менеджер моделей для экрана «Модели» и первого запуска варианта online. */
    val modelsController: ru.neurosteno.app.models.ModelsController by lazy { ru.neurosteno.app.models.ModelsController(context, this) }

    /** Воркер загрузки установил набор: обновить состояние моделей для UI. */
    fun onModelSetInstalled(setId: String) {
        val active = modelStore.activeSetId
        if (active != null && modelStore.state(active) == SetState.COMPLETE) ProcessingState.setModels(ModelsStatus.Ready(active))
    }
}
