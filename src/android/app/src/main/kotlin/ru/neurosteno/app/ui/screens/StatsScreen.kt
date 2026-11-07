package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.neurosteno.app.data.HistoryEntry
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.InfoRow
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.components.SectionCard
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.MonoStyle

/** Подписи ключей `stage_ms` из мета-данных расшифровки. */
private val STAGE_LABELS = listOf(
    "decode" to "Декодирование", "denoise" to "Шумоподавление", "vad" to "Поиск речи", "lid" to "Определение языка",
    "asr" to "Распознавание", "diarization" to "Разделение говорящих", "align" to "Сборка реплик",
    "punctuation" to "Пунктуация", "export" to "Сохранение результата",
)

/** «Статистика обработки» (BACKLOG B14): время по стадиям, RTF, пиковая память — по последней и по всем запускам. */
@Composable
fun StatsScreen(entries: List<HistoryEntry>, deviceTotalMb: Int, tier: String, onBack: () -> Unit) {
    val runs = entries.filter { it.processingMs > 0 && it.durationMs > 0 }
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Статистика обработки", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (runs.isEmpty()) {
                Text("Пока нет данных: обработайте любую запись, и здесь появятся время по стадиям, RTF и пиковая память.", style = MaterialTheme.typography.bodyMedium)
                SectionCard(title = "Устройство") {
                    InfoRow("Вариант сборки", tier)
                    InfoRow("Память устройства", if (deviceTotalMb > 0) "$deviceTotalMb МБ" else "—")
                }
                return@Column
            }
            val last = runs.first()
            SectionCard(title = "Последняя обработка") {
                InfoRow("Файл", last.sourceName)
                InfoRow("Длительность записи", Format.clock(last.durationMs))
                InfoRow("Время обработки", Format.duration(last.processingMs))
                InfoRow("RTF (время / длительность)", Format.rtf(last.rtf))
                InfoRow("Пик памяти процесса (PSS)", if (last.peakPssMb > 0) "${last.peakPssMb} МБ" else "не измерялся")
                if (last.threads > 0) InfoRow("Потоков", last.threads.toString())
                if (last.device.isNotBlank()) InfoRow("Устройство", last.device + if (last.androidSdk > 0) " · Android API ${last.androidSdk}" else "")
                if (last.appVersion.isNotBlank()) InfoRow("Версия приложения", last.appVersion)
                InfoRow("Язык", last.language)
                InfoRow("Набор моделей", last.modelSetId ?: "—")
            }
            SectionCard(title = "Время по стадиям") {
                val total = (last.stageMs["total"] ?: last.processingMs).coerceAtLeast(1)
                STAGE_LABELS.forEach { (key, label) ->
                    val ms = last.stageMs[key] ?: return@forEach
                    StageBar(label, ms, total)
                }
                Text(
                    "Распознавание и разделение говорящих идут параллельно, поэтому сумма стадий может превышать общее время.",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                )
            }
            SectionCard(title = "Сводка по ${Format.plural(runs.size, "запуску", "запускам", "запускам")}") {
                val rtfs = runs.map { it.rtf }
                InfoRow("Средний RTF", Format.rtf(rtfs.average()))
                InfoRow("Лучший / худший RTF", "${Format.rtf(rtfs.min())} / ${Format.rtf(rtfs.max())}")
                val peaks = runs.map { it.peakPssMb }.filter { it > 0 }
                if (peaks.isNotEmpty()) InfoRow("Пик памяти (макс.)", "${peaks.max()} МБ")
                InfoRow("Суммарно обработано", Format.duration(runs.sumOf { it.durationMs }))
                InfoRow("Память устройства", if (deviceTotalMb > 0) "$deviceTotalMb МБ" else (runs.maxOf { it.deviceTotalMb }.takeIf { it > 0 }?.let { "$it МБ" } ?: "—"))
            }
            SectionCard(title = "Запуски") {
                val df = SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru"))
                runs.take(15).forEach { e ->
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription = "${e.title}: RTF ${Format.rtf(e.rtf)}, ${Format.duration(e.processingMs)}"
                    }) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${df.format(Date(e.createdAt))} · запись ${Format.clock(e.durationMs)} · обработка ${Format.duration(e.processingMs)} · RTF ${Format.rtf(e.rtf)}" +
                                (if (e.peakPssMb > 0) " · ${e.peakPssMb} МБ" else ""),
                            style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StageBar(label: String, ms: Long, totalMs: Long) {
    val frac = (ms.toFloat() / totalMs).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label: ${Format.duration(ms)}, ${(frac * 100).toInt()} процентов" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${Format.duration(ms)} · ${(frac * 100).toInt()}%", style = MonoStyle, color = Brutal.ink)
        }
        BrutalProgress(frac.coerceAtLeast(0.01f), height = 14.dp)
    }
}
