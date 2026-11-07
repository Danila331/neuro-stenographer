package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.insight.SUMMARY_MODE_LLM
import ru.neurosteno.app.insight.SummaryCaps
import ru.neurosteno.app.insight.SummaryState
import ru.neurosteno.app.insight.firstRef
import ru.neurosteno.app.insight.isLlm
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalButton
import ru.neurosteno.app.ui.theme.BrutalButtonStyle
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalDims
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.BrutalProgress
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.ButtonLabel
import ru.neurosteno.app.ui.theme.PlaqueKind
import ru.neurosteno.app.ui.theme.SectionTitle
import ru.neurosteno.app.ui.theme.SpeakerChip
import ru.neurosteno.core.summary.SummaryDoc
import ru.neurosteno.core.summary.SummaryRef
import ru.neurosteno.core.summary.SummaryTopic

/** Действия экрана «Выжимка». [onJump] -- перейти к реплике: время `t` и номер реплики `n` (с 1; у заголовка темы -- `null`). */
class SummaryActions(
    val onBack: () -> Unit,
    val onBuild: () -> Unit,
    val onCancel: () -> Unit,
    val onJump: (tMs: Long, n: Int?) -> Unit,
    val onExportTxt: () -> Unit,
    val onExportPdf: () -> Unit,
)

/**
 * «Выжимка» записи (B50): «о чём», темы-карточки с диапазоном времени (пункты по говорящим с цветом и именем, решения, задачи),
 * пометки «быстрая выжимка» / «ИИ-выжимка готовится…» с прогрессом, плашка «черновик», «Пересоздать» и экспорт TXT/PDF.
 * [blockedReason] -- ИИ занят другой задачей (например, идёт составление протокола): запуск выжимки отключён с пояснением.
 */
@Composable
fun SummaryScreen(
    state: SummaryState,
    caps: SummaryCaps,
    names: (String) -> String,
    actions: SummaryActions,
    blockedReason: String? = null,
) {
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Выжимка", onBack = actions.onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state) {
                SummaryState.Loading -> item { Text("Загрузка…", style = MaterialTheme.typography.bodyLarge) }
                SummaryState.None -> item { EmptyCard(caps, blockedReason, actions.onBuild) }
                is SummaryState.Preparing -> item { WorkingPlaque(state.stage, null, actions.onCancel, preparing = true) }
                is SummaryState.Failed -> {
                    item { MessageCard(state.message, isError = true) }
                    state.doc?.let { content(it, names, caps, blockedReason, null, null, actions, working = false) }
                    item { Controls(working = false, hasDoc = state.doc != null, blocked = blockedReason, actions = actions, retryLabel = "Повторить") }
                }
                is SummaryState.Shown -> content(state.doc, names, caps, blockedReason, state, state.notice, actions, working = state.working)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.content(
    doc: SummaryDoc,
    names: (String) -> String,
    caps: SummaryCaps,
    blockedReason: String?,
    shown: SummaryState.Shown?,
    notice: String?,
    actions: SummaryActions,
    working: Boolean,
) {
    item { StatusTags(doc, working) }
    if (shown?.progress != null) item { WorkingPlaque(shown.progress!!.stage.trimEnd('.', '…'), shown.progress!!.fraction, actions.onCancel) }
    item {
        BrutalPlaque(
            "Нажмите на пункт — откроется реплика. Пересказ может быть неточным.",
            kind = PlaqueKind.Accent, title = "Черновик — проверьте по репликам",
            modifier = Modifier.testTag("summary_draft_notice"),
        )
    }
    if (!working) {
        val extra = when {
            !caps.llmMode -> "В этом варианте приложения выжимка строится без ИИ: темы и цитаты реплик по говорящим."
            !doc.isLlm && caps.llmUnavailableReason != null && notice == null -> "ИИ-выжимка недоступна: ${caps.llmUnavailableReason.trimEnd('.')}. Показана быстрая версия."
            else -> null
        }
        if (extra != null) item { BrutalPlaque(extra, kind = PlaqueKind.Neutral, modifier = Modifier.testTag("summary_mode_note")) }
    }
    if (notice != null) item { BrutalPlaque(notice, kind = PlaqueKind.Neutral, modifier = Modifier.testTag("summary_notice")) }
    if (doc.overview.isNotBlank()) {
        item {
            BrutalCard(Modifier.fillMaxWidth().testTag("summary_overview")) {
                SectionTitle("О чём")
                Text(doc.overview.trim().replace(Regex("\\s+"), " "), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    itemsIndexed(doc.topics) { i, t -> TopicCard(i, t, names, actions.onJump) }
    item { Controls(working = working, hasDoc = !doc.topics.isEmpty(), blocked = blockedReason, actions = actions, retryLabel = "Пересоздать") }
}

@Composable
private fun StatusTags(doc: SummaryDoc, working: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (doc.isLlm && !working) BrutalTag("ИИ-ВЫЖИМКА", fill = Brutal.ink, modifier = Modifier.testTag("summary_tag_llm"))
        else BrutalTag("БЫСТРАЯ ВЫЖИМКА · БЕЗ ИИ", fill = Brutal.accent, modifier = Modifier.testTag("summary_tag_quick"))
        BrutalTag(Format.plural(doc.topics.size, "тема", "темы", "тем"))
    }
}

@Composable
private fun WorkingPlaque(stage: String, fraction: Float?, onCancel: () -> Unit, preparing: Boolean = false) {
    BrutalPlaque(
        if (preparing) stage else "$stage. Пока показана быстрая версия — ИИ-версия заменит её сама.",
        title = "ИИ-выжимка готовится…", kind = PlaqueKind.Neutral, modifier = Modifier.testTag("summary_working"),
    ) {
        BrutalProgress(fraction, Modifier.semantics { contentDescription = "Выжимка: ${((fraction ?: 0f) * 100).toInt()} процентов" })
        BrutalButton(onClick = onCancel, style = BrutalButtonStyle.Secondary, height = 44.dp, modifier = Modifier.fillMaxWidth().testTag("btn_summary_cancel")) { ButtonLabel("Остановить") }
    }
}

@Composable
private fun EmptyCard(caps: SummaryCaps, blocked: String?, onBuild: () -> Unit) {
    BrutalCard(Modifier.fillMaxWidth().testTag("summary_empty")) {
        SectionTitle("Выжимки ещё нет")
        Text(
            if (caps.llmMode) "Быстрая версия (темы и цитаты реплик) появится сразу. ИИ-версия с пересказом, решениями и задачами готовится дольше и заменит её."
            else "Темы и цитаты реплик по говорящим — без ИИ, мгновенно.",
            style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, modifier = Modifier.padding(vertical = 10.dp),
        )
        blocked?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 10.dp).testTag("summary_blocked")) }
        BrutalButton(onClick = onBuild, enabled = blocked == null, modifier = Modifier.fillMaxWidth().testTag("btn_make_summary")) { ButtonLabel("Составить выжимку") }
    }
}

@Composable
private fun Controls(working: Boolean, hasDoc: Boolean, blocked: String?, actions: SummaryActions, retryLabel: String) {
    BrutalCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocked?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("summary_blocked")) }
            if (!working) BrutalButton(onClick = actions.onBuild, enabled = blocked == null, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = Modifier.fillMaxWidth().testTag("btn_summary_rebuild")) { ButtonLabel(retryLabel) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrutalButton(onClick = actions.onExportTxt, enabled = hasDoc, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = Modifier.weight(1f).testTag("btn_summary_txt")) { ButtonLabel("Сохранить TXT") }
                BrutalButton(onClick = actions.onExportPdf, enabled = hasDoc, style = BrutalButtonStyle.Secondary, height = 52.dp, modifier = Modifier.weight(1f).testTag("btn_summary_pdf")) { ButtonLabel("Сохранить PDF") }
            }
        }
    }
}

@Composable
private fun TopicCard(index: Int, t: SummaryTopic, names: (String) -> String, onJump: (Long, Int?) -> Unit) {
    BrutalCard(Modifier.fillMaxWidth().testTag("summary_topic_$index"), contentPadding = PaddingValues(0.dp)) {
        // заголовок темы: диапазон времени и название; тап -- к началу темы
        Column(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Перейти к началу темы") { onJump(t.startMs, null) }
                .padding(14.dp).semantics(mergeDescendants = true) { contentDescription = "Тема: ${t.title}, ${Format.clock(t.startMs)}–${Format.clock(t.endMs)}" },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrutalTag("${Format.clock(t.startMs)}–${Format.clock(t.endMs)}", fill = Brutal.accent)
            Text(t.title, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            t.points.forEachIndexed { j, p ->
                val r = p.refs.firstRef()
                RefRow(
                    modifier = Modifier.testTag("summary_point_${index}_$j"),
                    ref = r, onJump = onJump, description = "${names(p.speaker)}: ${p.text}",
                ) {
                    SpeakerChip(names(p.speaker), p.speaker)
                    Text(p.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            t.decisions.forEachIndexed { j, d ->
                RefRow(modifier = Modifier.testTag("summary_decision_${index}_$j"), ref = d.refs.firstRef(), onJump = onJump, description = "Решение: ${d.text}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrutalTag("РЕШЕНИЕ", fill = Brutal.ink)
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(d.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            t.tasks.forEachIndexed { j, task ->
                RefRow(modifier = Modifier.testTag("summary_task_${index}_$j"), ref = task.refs.firstRef(), onJump = onJump,
                    description = "Задача" + (if (task.who.isNotBlank()) " для ${task.who}" else "") + ": ${task.text}") {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BrutalTag("ЗАДАЧА", fill = Brutal.ink)
                        if (task.who.isNotBlank()) BrutalTag(task.who)
                    }
                    Text(task.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Пункт выжимки со ссылкой на реплику: весь ряд нажимаем, справа таймкод. */
@Composable
private fun RefRow(
    modifier: Modifier,
    ref: SummaryRef?,
    onJump: (Long, Int?) -> Unit,
    description: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val click = if (ref != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Перейти к реплике ${Format.clock(ref.t)}") { onJump(ref.t, ref.n) } else Modifier
    Row(
        modifier.fillMaxWidth().then(click)
            .semantics(mergeDescendants = true) { contentDescription = description + (ref?.let { ". Перейти к ${Format.clock(it.t)}" } ?: "") },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        if (ref != null) BrutalTag(Format.clock(ref.t))
    }
}
