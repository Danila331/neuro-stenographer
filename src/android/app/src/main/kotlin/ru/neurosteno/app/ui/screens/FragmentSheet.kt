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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import ru.neurosteno.app.ui.components.BrutalSheet
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalChip
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalSwitch
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.SectionTitle

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
    BrutalSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.semantics { testTagsAsResourceId = true } // окно листа отдельное: resource-id тегов нужны скриптам автоматизации
                .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionTitle("Обработать заново")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                BrutalTag(rangeLabel, fill = Brutal.accent)
                BrutalTag(Format.plural(utteranceCount, "реплика", "реплики", "реплик"))
            }
            Text("Остальная расшифровка не изменится.", style = MaterialTheme.typography.bodyMedium, color = Brutal.muted)
            blockedReason?.let { MessageCard(it, isError = true) }

            Text("Язык фрагмента", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FragmentLanguage.entries.forEach { l ->
                    val label = if (l == FragmentLanguage.AS_RECORDED) "${l.label} (${recordingLanguage.ifBlank { "ru" }})" else l.label
                    BrutalChip(selected = language == l, onClick = { language = l }, label = label, modifier = Modifier.testTag("fragment_lang_${l.name.lowercase()}"))
                }
            }
            Text(
                "«Авто» определяет язык по фрагменту (если в нём не меньше 3 секунд речи), иначе берёт язык записи.",
                style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
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
                        style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                    )
                }
                BrutalSwitch(checked = recompute, onCheckedChange = { recompute = it }, modifier = Modifier.testTag("fragment_recompute"))
            }

            if (recompute && utteranceCount > 1) {
                Text("Число говорящих во фрагменте", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..4).forEach { n ->
                        BrutalChip(selected = speakers == n, onClick = { speakers = n }, label = if (n == 0) "Авто" else n.toString(), modifier = Modifier.testTag("fragment_speakers_$n"))
                    }
                }
                Text(
                    "Если число известно, укажите его: на коротком отрезке «Авто» может слить двух говорящих в одного.",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                )
            } else if (recompute) {
                Text(
                    "Выделена одна реплика — она считается репликой одного говорящего (иначе голоса на коротком отрезке сливаются).",
                    style = MaterialTheme.typography.bodySmall, color = Brutal.muted,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                BrutalButton(onClick = onDismiss, style = BrutalButtonStyle.Secondary, modifier = Modifier.weight(1f).testTag("btn_fragment_sheet_cancel")) { ButtonLabel("Отмена") }
                BrutalButton(
                    onClick = { onStart(FragmentOptions(language, recompute, speakers.takeIf { it > 0 })) },
                    enabled = blockedReason == null,
                    modifier = Modifier.weight(1f).testTag("btn_fragment_start"),
                ) { ButtonLabel("Обработать") }
            }
        }
    }
}

/** Индикатор пересчёта поверх расшифровки: прогресс, стадия, «Отменить». */
@Composable
fun FragmentProgressCard(label: String, stageTitle: String, overall: Float, cancelling: Boolean, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    BrutalCard(modifier.padding(24.dp).fillMaxWidth(), shadow = 6.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Обработка: $label", style = MaterialTheme.typography.titleMedium)
            BrutalProgress(overall.coerceIn(0f, 1f))
            Text(
                if (cancelling) "Отмена…" else "$stageTitle · ${Format.percent(overall)}",
                style = MaterialTheme.typography.bodyMedium, color = Brutal.muted,
            )
            BrutalButton(onClick = onCancel, enabled = !cancelling, style = BrutalButtonStyle.Secondary, modifier = Modifier.fillMaxWidth().testTag("btn_fragment_cancel")) { ButtonLabel("Отменить") }
        }
    }
}
