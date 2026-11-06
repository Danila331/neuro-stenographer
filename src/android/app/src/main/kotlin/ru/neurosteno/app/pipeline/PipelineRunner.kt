package ru.neurosteno.app.pipeline

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import ru.neurosteno.app.AppGraph
import ru.neurosteno.app.BuildConfig
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

    suspend fun run(uri: Uri, displayName: String, listener: PipelineProgressListener): PipelineResult {
        val cfg = graph.tierConfig ?: throw ModelsNotReadyException("Не удалось прочитать конфигурацию варианта: ${graph.configError}")
        val dir = graph.modelStore.activeDir()
            ?: throw ModelsNotReadyException("Модели не найдены. Вариант «${BuildConfig.TIER}» без встроенных моделей: скачайте набор на экране «Модели».")
        val missing = graph.modelStore.missing(cfg.requiredModelPaths())
        if (missing.isNotEmpty()) {
            throw ModelsNotReadyException("В наборе моделей нет файлов: ${missing.joinToString()}")
        }
        val root = ModelRoot(dir)

        val plan = ResourcePlanner.plan(memorySnapshot(), cfg, Runtime.getRuntime().availableProcessors())
        val threads = plan.numThreads
        val llmFile = cfg.llm.model?.let { root.optionalFile(it) }

        val providers = EngineProviders(
            vad = { SherpaVad(cfg.vad, root, numThreads = 1) },
            asr = { lang ->
                val asrCfg = if (lang == LanguageConfig.EN) cfg.asrEn ?: cfg.asr else cfg.asr
                SherpaAsr(asrCfg, root, threads, lang, keepPunct = cfg.punctuation.source == "asr_native")
            },
            diarizer = { SherpaDiarizer(cfg.diarization, root, threads) },
            // Пунктуатор создаётся только после выгрузки ASR/диаризации (оркестратор). Если LLM недоступна (нет файла,
            // нет dotprod, нет нативной библиотеки) -- фабрика бросает, оркестратор переходит на fallback без падения.
            punctuator = if (cfg.punctuation.source == "llm") { durationMs ->
                val file = llmFile ?: error("файл LLM не найден (${cfg.llm.model})")
                LlamaAssistant.availability()?.let { error(it) }
                // GPU-слои не передаём (0): ускорение только опционально, по умолчанию -- CPU (BACKLOG B11)
                val llm = LlamaAssistant(file, cfg.llm.nCtx, minOf(cfg.llm.nThreads, threads), gpuLayers = 0)
                LlmPunctuator(llm, durationMs, cfg.llm.timeBudgetRtf)
            } else null,
            denoiser = if (cfg.audio.denoise.enabled && cfg.audio.denoise.model != null) {
                { SherpaDenoiser(cfg.audio.denoise.model!!, root, threads) }
            } else null,
            languageId = if (cfg.language.effectiveMode == LanguageConfig.AUTO && cfg.language.lidModel != null) {
                { SherpaLanguageId(cfg.language.lidModel!!, root, 1, cfg.language.lidSeconds) }
            } else null,
        )
        val orchestrator = PipelineOrchestrator(
            decoder = MediaCodecAudioDecoder(context),
            engines = providers,
            settings = PipelineSettings.from(cfg, BuildConfig.VERSION_NAME, plan),
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
