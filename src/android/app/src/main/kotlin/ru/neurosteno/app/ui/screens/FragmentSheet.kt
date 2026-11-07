package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.FragmentParams
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.MessageCard

/** Язык пересчёта фрагмента. */
enum class FragmentLanguage(val label: String) {
    AS_RECORDED("Как в записи"), AUTO("Авто"), RU("Русский"), EN("English");

    /** Значение для `FragmentRequest.language`; [recording] -- язык расшифровки (`ru`/`en`). */
    fun request(recording: String): String = when (this) {
        AS_RECORDED -> recording.ifBlank { "ru" }
        AUTO -> "auto"
        RU -> "ru"
        EN -> "en"
    }
}

/** Выбранные в листе параметры (переводятся в [FragmentParams] вызывающим экраном). */
data class FragmentOptions(val language: FragmentLanguage, val recomputeSpeakers: Boolean, val numSpeakers: Int?) {
    fun toParams(entryId: String, startMs: Long, endMs: Long, recordingLanguage: String, utteranceCount: Int) = FragmentParams(
        entryId = entryId, startMs = startMs, endMs = endMs,
        language = language.request(recordingLanguage),
        // одна реплика -- один говорящий: без этого диаризация короткого отрезка легко «находит» второго
        numSpeakers = if (!recomputeSpeakers) null else if (utteranceCount <= 1) 1 else numSpeakers,
        recomputeSpeakers = recomputeSpeakers,
    )
}

/**
 * Лист «Обработать заново»: язык, «Заново определить говорящих» (выкл. по умолчанию) и, если выделено больше одной реплики и
 * пересчёт говорящих включён, — число говорящих (авто / 1–4).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun FragmentOptionsSheet(
    rangeLabel: String,
    utteranceCount: Int,
    recordingLanguage: String,
    /** Почему запуск сейчас невозможен (нет копии аудио, идёт другая обработка); null -- можно. */
    blockedReason: String?,
    onStart: (FragmentOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var language by rememberSaveable { mutableStateOf(FragmentLanguage.AS_RECORDED) }
    var recompute by rememberSaveable { mutableStateOf(false) }
    var speakers by rememberSaveable { mutableStateOf(0) } // 0 -- авто
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.semantics { testTagsAsResourceId = true } // окно листа отдельное: resource-id тегов нужны скриптам автоматизации
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Обработать заново", style = MaterialTheme.typography.titleLarge)
            Text(
                "$rangeLabel · ${Format.plural(utteranceCount, "реплика", "реплики", "реплик")}. Остальная расшифровка не изменится.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Чтобы получить другой результат, смените язык или включите “Заново определить говорящих”",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            blockedReason?.let { MessageCard(it, isError = true) }

            Text("Язык фрагмента", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FragmentLanguage.entries.forEach { l ->
                    val label = if (l == FragmentLanguage.AS_RECORDED) "${l.label} (${recordingLanguage.ifBlank { "ru" }})" else l.label
                    FilterChip(selected = language == l, onClick = { language = l }, label = { Text(label) }, modifier = Modifier.testTag("fragment_lang_${l.name.lowercase()}"))
                }
            }
            Text(
                "«Авто» определяет язык по фрагменту (если в нём не меньше 3 секунд речи), иначе берёт язык записи.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Заново определить говорящих, ${if (recompute) "включено" else "выключено"}" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Заново определить говорящих", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (recompute) "Диаризация фрагмента и сопоставление с говорящими записи: дольше и расходует больше памяти."
                        else "Выключено: говорящие берутся из прежних реплик по времени — быстро, только распознавание речи.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = recompute, onCheckedChange = { recompute = it }, modifier = Modifier.testTag("fragment_recompute"))
            }

            if (recompute && utteranceCount > 1) {
                Text("Число говорящих во фрагменте", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..4).forEach { n ->
                        FilterChip(selected = speakers == n, onClick = { speakers = n }, label = { Text(if (n == 0) "Авто" else n.toString()) },
                            modifier = Modifier.testTag("fragment_speakers_$n"))
                    }
                }
                Text(
                    "Если число известно, укажите его: на коротком отрезке «Авто» может слить двух говорящих в одного.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (recompute) {
                Text(
                    "Выделена одна реплика — она считается репликой одного говорящего (иначе голоса на коротком отрезке сливаются).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).testTag("btn_fragment_sheet_cancel")) { Text("Отмена") }
                Button(
                    onClick = { onStart(FragmentOptions(language, recompute, speakers.takeIf { it > 0 })) },
                    enabled = blockedReason == null,
                    modifier = Modifier.weight(1f).testTag("btn_fragment_start"),
                ) { Text("Обработать") }
            }
        }
    }
}

/** Индикатор пересчёта поверх расшифровки: прогресс, стадия, «Отменить». */
@Composable
fun FragmentProgressCard(label: String, stageTitle: String, overall: Float, cancelling: Boolean, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Card(modifier.padding(24.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Обработка: $label", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { overall.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                if (cancelling) "Отмена…" else "$stageTitle · ${Format.percent(overall)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel, enabled = !cancelling, modifier = Modifier.fillMaxWidth().testTag("btn_fragment_cancel")) { Text("Отменить") }
        }
    }
}
