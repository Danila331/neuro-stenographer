package ru.neurosteno.app.licenses

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Компонент реестра сторонних материалов (`assets/third_party_notices.json`, генератор -- `src/android/tools/gen_notices.py`). */
@Serializable
data class Notice(
    val id: String,
    /** `model` | `library` | `font`. */
    val kind: String,
    val name: String,
    val version: String = "",
    val purpose: String = "",
    val license: String,
    /** Файл текста лицензии в `assets/licenses/`. */
    @SerialName("license_file") val licenseFile: String,
    /** `false` -- вложена краткая справка со ссылкой, а не полный юридический текст. */
    @SerialName("text_complete") val textComplete: Boolean = true,
    val source: String = "",
    val note: String = "",
    /** Наборы моделей (lite/medium/hard), в которые входит модель. */
    val tiers: List<String> = emptyList(),
) {
    companion object {
        const val MODEL = "model"
        const val LIBRARY = "library"
        const val FONT = "font"
    }
}

object Notices {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<Notice> = json.decodeFromString(ListSerializer(Notice.serializer()), text)

    fun load(context: Context): List<Notice> =
        runCatching { parse(context.assets.open("third_party_notices.json").bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrDefault(emptyList())

    /** Полный текст лицензии из `assets/licenses/` (офлайн); `null`, если файла нет. */
    fun licenseText(context: Context, notice: Notice): String? =
        runCatching { context.assets.open("licenses/${notice.licenseFile}").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
}
