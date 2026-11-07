package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.licenses.Notice
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar
import ru.neurosteno.app.ui.theme.Brutal
import ru.neurosteno.app.ui.theme.BrutalCard
import ru.neurosteno.app.ui.theme.BrutalPlaque
import ru.neurosteno.app.ui.theme.BrutalTag
import ru.neurosteno.app.ui.theme.MonoFamily
import ru.neurosteno.app.ui.theme.PlaqueKind
import ru.neurosteno.app.ui.theme.SectionTitle

/**
 * «Лицензии и модели» (Настройки): модели (назначение, лицензия, источник), библиотеки, шрифты; тап -- полный текст лицензии из
 * `assets/licenses/` (работает офлайн).
 */
@Composable
fun LicensesScreen(notices: List<Notice>, onOpen: (Notice) -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar("Лицензии и модели", onBack = onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Приложение работает офлайн и использует открытые модели, библиотеки и шрифты. Нажмите на пункт, чтобы прочитать полный текст лицензии.",
                    style = MaterialTheme.typography.bodyMedium, color = Brutal.muted, modifier = Modifier.testTag("licenses_intro"),
                )
            }
            if (notices.isEmpty()) item { MessageCard("Реестр лицензий не найден в приложении.", isError = true) }
            for ((kind, title) in listOf(Notice.MODEL to "Модели", Notice.LIBRARY to "Библиотеки", Notice.FONT to "Шрифты")) {
                val group = notices.filter { it.kind == kind }
                if (group.isEmpty()) continue
                item(key = "h_$kind") { SectionTitle(title, Modifier.padding(top = 6.dp).testTag("licenses_section_$kind")) }
                group.forEach { n -> item(key = n.id) { NoticeCard(n, onOpen) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoticeCard(n: Notice, onOpen: (Notice) -> Unit) {
    BrutalCard(
        Modifier.fillMaxWidth().testTag("notice_${n.id}").semantics(mergeDescendants = true) {
            contentDescription = "${n.name}. Лицензия ${n.license}. ${n.purpose}. Открыть текст лицензии"
        },
        onClick = { onOpen(n) }, onClickLabel = "Открыть текст лицензии",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(n.name, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BrutalTag(n.license, fill = Brutal.accent)
                if (n.version.isNotBlank()) BrutalTag(n.version)
                n.tiers.forEach { BrutalTag(it.uppercase()) }
            }
            if (n.purpose.isNotBlank()) Text(n.purpose, style = MaterialTheme.typography.bodyMedium)
            if (n.source.isNotBlank()) Text(n.source.removePrefix("https://"), style = MaterialTheme.typography.labelMedium, color = Brutal.muted)
        }
    }
}

/** Полный текст лицензии компонента [notice]; [text] == null -- файл не найден. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LicenseTextScreen(notice: Notice, text: String?, onBack: () -> Unit) {
    Scaffold(containerColor = Brutal.bg, topBar = { NeuroTopBar(notice.name, onBack = onBack, titleMaxLines = 2) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BrutalTag(notice.license, fill = Brutal.accent)
                if (notice.version.isNotBlank()) BrutalTag(notice.version)
            }
            if (notice.source.isNotBlank()) Text("Источник: ${notice.source}", style = MaterialTheme.typography.bodySmall, color = Brutal.muted)
            if (notice.note.isNotBlank()) BrutalPlaque(notice.note, kind = if (notice.textComplete) PlaqueKind.Neutral else PlaqueKind.Accent, modifier = Modifier.testTag("license_note"))
            if (text == null) MessageCard("Файл с текстом лицензии не найден.", isError = true)
            else BrutalCard(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(0.dp)) {
                SelectionContainer {
                    Text(
                        text.trim(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily),
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(14.dp).fillMaxWidth().testTag("license_text"),
                    )
                }
            }
        }
    }
}
