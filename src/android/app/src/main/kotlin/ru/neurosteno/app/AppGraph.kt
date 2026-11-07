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

    /** AI-поиск (B51): индекс записей, эмбеддер, хранилище векторов. */
    val search: ru.neurosteno.app.search.SearchService by lazy { ru.neurosteno.app.search.SearchService(context, this) }

    /** Выжимка (B50); эмбеддер реплик для долин -- общий с поиском (выгружается по простою). */
    val summary: ru.neurosteno.app.summary.SummaryRunner by lazy { ru.neurosteno.app.summary.SummaryRunner(context, this) { search.embedder.get() } }

    /** Порт выжимки для экрана (B50): настоящий движок через [summary]. */
    val summaryRepository: ru.neurosteno.app.insight.SummaryRepository by lazy { ru.neurosteno.app.insight.GraphSummaryRepository(context, this) }

    /**
     * Порт поиска для экрана «AI-поиск» (B51): настоящий гибридный поиск и ответ LLM ([ru.neurosteno.app.insight.RealSearchRepository]).
     * Фейк ([ru.neurosteno.app.insight.FakeSearchRepository]) остаётся для превью и UI-тестов, в приложении не используется.
     */
    val searchRepository: ru.neurosteno.app.insight.SearchRepository by lazy { ru.neurosteno.app.insight.RealSearchRepository(this) }

    val history: HistoryRepository by lazy {
        HistoryRepository(File(context.filesDir, "history")).also { repo ->
            // индекс AI-поиска следит за историей: новые записи индексируются в фоне, удалённые уходят из индекса сразу
            repo.listener = object : HistoryRepository.Listener {
                override fun onAdded(id: String) = search.onAdded(id)
                override fun onSaved(id: String) = search.onSaved(id)
                override fun onDeleted(id: String) = search.onDeleted(id)
                override fun onCleared() = search.onCleared()
            }
        }
    }

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
