package ru.neurosteno.smoke

import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.system.exitProcess
import ru.neurosteno.llm.LlamaAssistant
import ru.neurosteno.llm.LlamaOptions

/**
 * Хостовая проверка JNI-флагов памяти (BACKLOG B48) на реальной GGUF: «Протокол заседания» по одной расшифровке тем же запросом,
 * что строит приложение для фрагмента (`protocol_system.txt` + «Расшифровка (часть 1 из 1)» в ChatML без размышлений).
 *
 *   --llm-bench <model.gguf> <dialog.txt> <protocol_system.txt> <n_ctx> <default|flags> [max_tokens=450] [threads=4]
 *
 * `default` -- прежняя загрузка (repack, KV f16, без flash attention); `flags` -- `no_repack` + flash attention + KV q8_0 + mmap, как в
 * `config/tiers/medium.json`. Пик RSS процесса измеряется опросом `ps` раз в 50 мс; прогон -- отдельный процесс на конфигурацию.
 */
fun runLlmBench(args: List<String>) {
    if (args.size < 5) {
        System.err.println("usage: --llm-bench <model.gguf> <dialog.txt> <protocol_system.txt> <n_ctx> <default|flags> [max_tokens] [threads]")
        exitProcess(2)
    }
    val model = File(args[0])
    val dialog = File(args[1])
    val system = File(args[2]).readText(Charsets.UTF_8).trim()
    val nCtx = args[3].toInt()
    val mode = args[4]
    val maxTokens = args.getOrNull(5)?.toInt() ?: 450
    val threads = args.getOrNull(6)?.toInt() ?: 4
    val options = when (mode) {
        "default" -> LlamaOptions()
        "flags" -> LlamaOptions(noRepack = true, flashAttn = true, kvCacheType = "q8_0", useMmap = true)
        "nr" -> LlamaOptions(noRepack = true)
        else -> error("режим: default | flags | nr")
    }

    // строки расшифровки «[мм:сс] Говорящий N: текст» (130 слов/мин и пауза 0,6 с -- как в синтетической шкале llm-bench)
    var t = 1.2
    val lines = ArrayList<String>()
    for (raw in dialog.readLines(Charsets.UTF_8)) {
        val m = Regex("^(\\d+):\\s*(.*)$").matchEntire(raw) ?: continue
        val words = m.groupValues[2].split(' ').size
        val s = t.toInt()
        lines += String.format(Locale.ROOT, "[%02d:%02d] Говорящий %s: %s", s / 60, s % 60, m.groupValues[1], m.groupValues[2])
        t += words * 60.0 / 130 + 0.6
    }
    val prompt = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\nРасшифровка (часть 1 из 1):\n${lines.joinToString("\n")}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    val pid = ProcessHandle.current().pid()
    val peakBox = java.util.concurrent.atomic.AtomicLong(0)
    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    fun rssMb(): Long = runCatching {
        val p = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString()).redirectErrorStream(true).start()
        val v = p.inputStream.bufferedReader().readText().trim().toLong()
        p.waitFor()
        v / 1024
    }.getOrDefault(0)
    val sampler = Thread({
        while (running.get()) { peakBox.accumulateAndGet(rssMb(), ::maxOf); Thread.sleep(50) }
    }, "rss-sampler").apply { isDaemon = true; start() }

    val t0 = System.nanoTime()
    val llm = LlamaAssistant(model, nCtx, threads, gpuLayers = 0, options = options)
    val loadMs = (System.nanoTime() - t0) / 1_000_000
    val rssAfterLoad = rssMb()
    var firstTokenMs = -1L
    var tokens = 0
    val g0 = System.nanoTime()
    val text = llm.generate(prompt, maxTokens) {
        if (firstTokenMs < 0) firstTokenMs = (System.nanoTime() - g0) / 1_000_000
        tokens++
    }
    val genMs = (System.nanoTime() - g0) / 1_000_000
    val rssEnd = rssMb()
    running.set(false)
    sampler.join(500)
    peakBox.accumulateAndGet(rssEnd, ::maxOf)
    val peak = peakBox.get()
    val promptTokensEst = (prompt.toByteArray(Charsets.UTF_8).size + 3) / 4
    println("== llm-bench mode=$mode model=${model.name} n_ctx=$nCtx threads=$threads")
    println("applied: ${llm.appliedOptions}")
    println(String.format(Locale.ROOT, "load_ms=%d rss_after_load_mb=%d | prompt~%d tok, first_token_ms=%d (prefill), total_gen_ms=%d, gen_tokens=%d, decode=%.1f tok/s | peak_rss_mb=%d rss_end_mb=%d",
        loadMs, rssAfterLoad, promptTokensEst, firstTokenMs, genMs, tokens,
        if (tokens > 1 && genMs > firstTokenMs) (tokens - 1) * 1000.0 / (genMs - firstTokenMs) else 0.0, peak, rssEnd))
    println("--- protocol (first 600 chars) ---")
    println(text.take(600))
    llm.close()
}
