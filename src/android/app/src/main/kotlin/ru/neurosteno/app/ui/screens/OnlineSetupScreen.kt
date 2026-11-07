package ru.neurosteno.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.neurosteno.app.models.ModelSetRow
import ru.neurosteno.app.ui.Format
import ru.neurosteno.app.ui.components.MessageCard
import ru.neurosteno.app.ui.components.NeuroTopBar

/**
 * Первый запуск варианта `online` (без встроенных моделей): единственный экран выбора и загрузки набора. Остальные
 * варианты первого экрана не имеют -- у них модели уже в APK.
 */
@Composable
fun OnlineSetupScreen(
    appName: String,
    rows: List<ModelSetRow>,
    freeBytes: Long,
    message: String?,
    actions: ModelActions,
    onDismissMessage: () -> Unit,
) {
    Scaffold(containerColor = ru.neurosteno.app.ui.theme.Brutal.bg, topBar = { NeuroTopBar(appName, titleMaxLines = 2) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ru.neurosteno.app.ui.theme.SectionTitle("Выберите набор моделей")
            }
            item {
                Text(
                    "В этой версии модели не входят в приложение — скачайте один набор (один раз, лучше по Wi-Fi). " +
                        "После этого распознавание работает полностью на устройстве и без интернета. " +
                        "Свободно на устройстве: ${Format.bytes(freeBytes)}.",
                    style = MaterialTheme.typography.bodyMedium, color = ru.neurosteno.app.ui.theme.Brutal.muted,
                )
            }
            if (message != null) item { MessageCard(message, isError = true) }
            items(rows.filter { it.inCatalog || it.installed }, key = { it.id }) { row ->
                ModelSetCard(row, bundledSetId = null, actions = actions, primaryDownload = row.recommended)
            }
        }
    }
}
