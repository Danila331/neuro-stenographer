package ru.neurosteno.app.insight

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Область поиска: открытая запись или вся история. */
enum class SearchScope { RECORD, HISTORY }

/** Строка найденного фрагмента: реплика с говорящим (имя уже подставлено: имена -- метаданные записи, разбираются в момент запроса). */
data class HitLine(val speakerId: String, val speakerName: String, val startMs: Long, val text: String)

/**
 * Найденный фрагмент (окно из нескольких ходов, `win3s1`): [lines] по времени, [terms] -- основы слов запроса для подсветки совпадений.
 */
data class SearchHit(
    val recordId: String,
    val recordTitle: String,
    val startMs: Long,
    val endMs: Long,
    val lines: List<HitLine>,
    val score: Float = 0f,
    val terms: List<String> = emptyList(),
)

/** Ссылка из ответа ИИ: `[мм:сс, Говорящий N]`; [speakerId] -- `speaker_N` или `null`, если подпись не в стандартном виде. */
data class SearchCitation(val startMs: Long, val speakerId: String?)

/** Состояние индекса поиска (B51): строится в фоне сразу после сохранения записи. */
sealed interface IndexState {
    data object Ready : IndexState
    data class Building(val done: Int, val total: Int) : IndexState {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Unavailable(val reason: String) : IndexState
}

/** Можно ли получить ответ ИИ: [enabled] = false и [reason] == null -- выключено конфигом (lite: только список реплик); с [reason] -- недоступно сейчас. */
data class AnswerCaps(val enabled: Boolean, val reason: String? = null) {
    val canAnswer: Boolean get() = enabled && reason == null
}

sealed interface AnswerEvent {
    /** Ответ растёт по мере генерации. */
    data class Partial(val text: String) : AnswerEvent
    data class Done(val text: String) : AnswerEvent
    data class Failed(val message: String) : AnswerEvent
}

/**
 * Порт поиска (B51). Настоящая реализация -- гибридный BM25 + эмбеддинги + RRF на `ru.neurosteno.core.search`, ответ -- LLM со ссылками;
 * пока движок не слит, UI работает на [FakeSearchRepository].
 */
interface SearchRepository {
    /** Состояние индекса области (запись или вся история). */
    fun indexState(scope: SearchScope, recordId: String?): StateFlow<IndexState>

    fun answerCaps(): AnswerCaps

    /** Быстрый поиск фрагментов (BM25 + вектор + RRF), без LLM. Пустой список -- ничего не найдено. */
    suspend fun search(query: String, scope: SearchScope, recordId: String?): List<SearchHit>

    /** Ответ ИИ по найденным фрагментам со ссылками `[мм:сс, Говорящий N]`; отказ -- «В найденных фрагментах этого нет.». */
    fun answer(query: String, hits: List<SearchHit>): Flow<AnswerEvent>
}
