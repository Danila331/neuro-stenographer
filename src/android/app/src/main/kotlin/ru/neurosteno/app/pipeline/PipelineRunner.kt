package ru.neurosteno.app.pipeline

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.BuildConfig
import ru.neurosteno.app.data.AppSettings
import ru.neurosteno.app.data.LanguageChoice
import ru.neurosteno.audio.MediaCodecAudioDecoder
import ru.neurosteno.audio.UriAudioInput
import ru.neurosteno.core.EngineProviders
import ru.neurosteno.core.LanguageConfig
import ru.neurosteno.core.MemorySnapshot
import ru.neurosteno.core.PipelineOrchestrator
import ru.neurosteno.core.PipelineProgressListener
import ru.neurosteno.core.PipelineResult
import ru.neurosteno.core.PipelineSettings
import ru.neurosteno.core.ResourcePlanner
import ru.neurosteno.core.llm.LlmPunctuator
import ru.neurosteno.llm.LlamaAssistant
import ru.neurosteno.sherpa.ModelRoot
import ru.neurosteno.sherpa.SherpaAsr
import ru.neurosteno.sherpa.SherpaDenoiser
import ru.neurosteno.sherpa.SherpaDiarizer
import ru.neurosteno.sherpa.SherpaLanguageId
import ru.neurosteno.sherpa.SherpaVad

/** Модели/конфиг не готовы -- сообщение показывается пользователю как есть. */
class ModelsNotReadyException(message: String) : Exception(message)

/** Собирает [PipelineOrchestrator] из конфига варианта и активного набора моделей. */
class PipelineRunner(private val context: Context, private val graph: AppGraph) {

    /** [userSettings] -- язык и число говорящих из экрана «Настройки» (переопределяют значения конфига уровня). */
    suspend fun run(uri: Uri, displayName: String, userSettings: AppSettings, listener: PipelineProgressListener): PipelineResult {
        val cfg = graph.activeConfig()?.first ?: throw ModelsNotReadyException("Не удалось прочитать конфигурацию варианта: ${graph.configError}")
        val dir = graph.modelStore.activeDir()
            ?: throw ModelsNotReadyException("Модели не найдены. Вариант «${BuildConfig.TIER}» без встроенных моделей: скачайте набор на экране «Модели».")
        val missing = graph.modelStore.missing(cfg.essentialModelPaths())
        if (missing.isNotEmpty()) {
            throw ModelsNotReadyException("В наборе моделей нет файлов: ${missing.joinToString()}")
        }
        val root = ModelRoot(dir)
        // Необязательные модели: нет файлов -- возможность отключается (не падаем)
        val optionalMissing = graph.modelStore.missing(cfg.requiredModelPaths() - cfg.essentialModelPaths().toSet()).toSet()
        val hasEn = cfg.asrEn != null && cfg.asrEn!!.modelDir !in optionalMissing
        val hasLid = cfg.language.lidModel != null && cfg.language.lidModel!! !in optionalMissing
        val hasDenoise = cfg.audio.denoise.model != null && cfg.audio.denoise.model!! !in optionalMissing

        val languageMode = when (userSettings.language) {
            LanguageChoice.RU -> LanguageConfig.RU
            LanguageChoice.EN -> LanguageConfig.EN
            // «Авто» -- определять по речи, если для этого есть модели (LID и английская ASR); иначе -- как в конфиге
            LanguageChoice.AUTO -> if (hasEn && hasLid) LanguageConfig.AUTO else cfg.language.effectiveMode
        }
        val plan = ResourcePlanner.plan(memorySnapshot(), cfg, Runtime.getRuntime().availableProcessors())
        val threads = plan.numThreads
        val llmFile = cfg.llm.model?.let { root.optionalFile(it) }

        val providers = EngineProviders(
            vad = { SherpaVad(cfg.vad, root, numThreads = 1) },
            asr = { lang ->
                val asrCfg = if (lang == LanguageConfig.EN && hasEn) cfg.asrEn!! else cfg.asr
                SherpaAsr(asrCfg, root, threads, lang, keepPunct = cfg.punctuation.source == "asr_native",
                    dropTinyMaxChars = cfg.postprocess.dropTinyChunks.takeIf { it.enabled }?.maxChars)
            },
            diarizer = { SherpaDiarizer(cfg.diarization, root, threads) },
            // Пунктуатор создаётся только после выгрузки ASR/диаризации (оркестратор). Если LLM недоступна (нет файла,
            // нет dotprod, нет нативной библиотеки) -- фабрика бросает, оркестратор переходит на fallback без падения.
            punctuator = if (cfg.punctuation.source == "llm") {
                { durationMs: Long ->
                    val file = llmFile ?: error("файл LLM не найден (${cfg.llm.model})")
                    LlamaAssistant.availability()?.let { error(it) }
                    // GPU-слои не передаём (0): ускорение только опционально, по умолчанию -- CPU (BACKLOG B11)
                    val llm = LlamaAssistant(file, cfg.llm.nCtx, minOf(cfg.llm.nThreads, threads), gpuLayers = 0)
                    LlmPunctuator(llm, durationMs, cfg.llm.timeBudgetRtf)
                }
            } else null,
            denoiser = if (cfg.audio.denoise.enabled && hasDenoise) {
                { SherpaDenoiser(cfg.audio.denoise.model!!, root, threads) }
            } else null,
            languageId = if (languageMode == LanguageConfig.AUTO && hasLid && hasEn) {
                { SherpaLanguageId(cfg.language.lidModel!!, root, 1, cfg.language.lidSeconds) }
            } else null,
        )
        val orchestrator = PipelineOrchestrator(
            decoder = MediaCodecAudioDecoder(context),
            engines = providers,
            settings = PipelineSettings.from(cfg, BuildConfig.VERSION_NAME, plan).let { s ->
                val langs = if (hasEn) s.supportedLanguages else setOf(LanguageConfig.RU)
                s.copy(
                    supportedLanguages = langs,
                    denoiseEnabled = s.denoiseEnabled && hasDenoise,
                    languageMode = languageMode,
                    // число говорящих из настроек; ровно один говорящий -- диаризация не нужна вовсе (быстрее)
                    numSpeakers = if (userSettings.numSpeakers > 0) userSettings.numSpeakers else s.numSpeakers,
                    diarizationEnabled = s.diarizationEnabled && userSettings.numSpeakers != 1,
                )
            },
        )
        return orchestrator.run(UriAudioInput(uri, displayName), listener)
    }

    private fun memorySnapshot(): MemorySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemorySnapshot(
            totalMb = info.totalMem / (1024 * 1024),
            availMb = info.availMem / (1024 * 1024),
            lowMemory = info.lowMemory,
        )
    }
}
