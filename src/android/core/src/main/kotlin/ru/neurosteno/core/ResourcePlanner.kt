package ru.neurosteno.core

/** Снимок памяти устройства (Android заполняет из `ActivityManager.MemoryInfo`). */
data class MemorySnapshot(val totalMb: Long, val availMb: Long, val lowMemory: Boolean = false)

/** Что именно урезаем и почему. */
data class ResourcePlan(
    val numThreads: Int,
    val llmPunctuation: Boolean,
    val secondPass: Boolean,
    val denoise: Boolean,
    /** Человекочитаемые причины деградации (в лог/статистику); пусто, если ограничений нет. */
    val reasons: List<String>,
) {
    val degraded: Boolean get() = reasons.isNotEmpty()
}

/**
 * Чистая функция «память + конфиг -> план ресурсов» (ARCHITECTURE: «Бюджет памяти»). Никаких исключений:
 * при нехватке памяти снижаем потоки и отключаем самые тяжёлые необязательные части, но обработка идёт.
 *
 * Модели грузятся по очереди (ASR и диаризация выгружаются до LLM), поэтому пики сравниваются раздельно:
 * основной проход (ASR || диаризация) ~ [peakMb], LLM-стадия ~ [llmMb] (после выгрузки остального).
 */
object ResourcePlanner {
    /** Оценка пика RAM основного прохода по tier (ARCHITECTURE: lite <= 1.0, medium <= 1.5, hard <= 4 ГБ). */
    fun defaultPeakMb(tier: String): Long = when (tier) {
        "lite" -> 1000
        "hard" -> 4000
        else -> 1500 // medium и неизвестные
    }

    /** Оценка RAM под LLM-стадию (GGUF q4 + KV-кэш). */
    fun defaultLlmMb(tier: String): Long = when (tier) {
        "lite" -> 700
        "hard" -> 3500
        else -> 1800
    }

    fun plan(
        memory: MemorySnapshot,
        cfg: TierConfig,
        cores: Int,
        peakMb: Long = defaultPeakMb(cfg.tier),
        llmMb: Long = defaultLlmMb(cfg.tier),
        /** На устройствах с меньшим объёмом RAM LLM-пунктуация отключается (замер: AVD 2 ГБ -- стадия 105 с вместо 9 с). */
        minTotalMbForLlm: Long = 3_000,
    ): ResourcePlan {
        val reasons = ArrayList<String>()
        val maxThreads = maxOf(1, minOf(cfg.numThreads, cores))
        var threads = maxThreads
        var llm = cfg.llm.punctuation && cfg.punctuation.source == "llm" && cfg.llm.model != null
        var second = cfg.asr.secondPass != null
        var denoise = cfg.audio.denoise.enabled

        if (cores < cfg.numThreads) reasons += "ядер ($cores) меньше, чем потоков в конфиге (${cfg.numThreads})"

        val avail = memory.availMb
        when {
            memory.lowMemory || avail < peakMb * 0.6 -> {
                threads = 1
                second = false
                denoise = false
                llm = false
                reasons += "очень мало памяти (доступно $avail МБ, нужно ~$peakMb МБ): 1 поток, без второго прохода, денойза и LLM"
            }
            avail < peakMb -> {
                threads = maxOf(1, maxThreads / 2)
                second = false
                denoise = false
                reasons += "мало памяти (доступно $avail МБ, нужно ~$peakMb МБ): потоков $threads, без второго прохода и денойза"
            }
        }
        if (llm && memory.totalMb in 1 until minTotalMbForLlm) {
            llm = false
            reasons += "RAM устройства ${memory.totalMb} МБ < $minTotalMbForLlm МБ: LLM-пунктуация отключена (слишком медленно)"
        }
        if (llm && avail < llmMb * 1.25) {
            llm = false
            reasons += "для LLM-пунктуации не хватает памяти (доступно $avail МБ, нужно ~${(llmMb * 1.25).toLong()} МБ)"
        }
        return ResourcePlan(threads, llm, second, denoise, reasons)
    }
}
