package ru.neurosteno.app.insight

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Что показывает экран «AI-поиск» после запроса. */
enum class SearchPhase { Idle, Searching, Results, NoResults, Error }

/** Блок «Ответ ИИ». */
sealed interface AnswerUi {
    /** Фрагменты найдены, ответ ещё не просили (кнопка «Ответить»). */
    data object Ready : AnswerUi
    data class Generating(val partial: String) : AnswerUi
    data class Done(val text: String) : AnswerUi
    /** ИИ ответил, что в найденном ответа нет. */
    data object Refused : AnswerUi
    /** ИИ недоступен сейчас: показываем только фрагменты. */
    data class Unavailable(val reason: String) : AnswerUi
    /** В этом варианте ответ ИИ выключен конфигом (lite). */
    data object Disabled : AnswerUi
    data class Failed(val message: String) : AnswerUi
    /** Пока нечего отвечать (нет запроса/фрагментов). */
    data object Hidden : AnswerUi
}

data class SearchUi(
    val query: String = "",
    val scope: SearchScope = SearchScope.RECORD,
    /** Запись, из которой открыт поиск; `null` -- поиск открыт с главного экрана, доступна только вся история. */
    val recordId: String? = null,
    val phase: SearchPhase = SearchPhase.Idle,
    val hits: List<SearchHit> = emptyList(),
    val answer: AnswerUi = AnswerUi.Hidden,
    val index: IndexState = IndexState.Ready,
    val error: String? = null,
) {
    val canScopeRecord: Boolean get() = recordId != null
}

/**
 * Состояние экрана «AI-поиск»: вопрос, область, найденные фрагменты, затем (по кнопке) ответ ИИ. Не зависит от Android;
 * [scope] -- корутины экрана (в тестах -- тестовый диспетчер).
 */
class SearchController(private val repo: SearchRepository, private val scope: CoroutineScope) {
    private val _ui = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    private var searchJob: Job? = null
    private var answerJob: Job? = null
    private var indexJob: Job? = null

    /** Открытие экрана: [recordId] -- запись, из которой пришли (поиск по ней по умолчанию), либо `null` -- вся история. */
    fun open(recordId: String?) {
        searchJob?.cancel(); answerJob?.cancel()
        val sc = if (recordId != null) SearchScope.RECORD else SearchScope.HISTORY
        _ui.value = SearchUi(recordId = recordId, scope = sc)
        watchIndex()
    }

    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }

    fun setScope(s: SearchScope) {
        val cur = _ui.value
        if (s == SearchScope.RECORD && cur.recordId == null || s == cur.scope) return
        _ui.value = cur.copy(scope = s)
        watchIndex()
        if (cur.phase != SearchPhase.Idle && cur.query.isNotBlank()) submit() // результаты прежней области неактуальны
    }

    fun submit() {
        val cur = _ui.value
        val q = cur.query.trim()
        searchJob?.cancel(); answerJob?.cancel()
        if (q.isEmpty()) { _ui.value = cur.copy(phase = SearchPhase.Idle, hits = emptyList(), answer = AnswerUi.Hidden); return }
        _ui.value = cur.copy(phase = SearchPhase.Searching, hits = emptyList(), answer = AnswerUi.Hidden, error = null)
        searchJob = scope.launch {
            try {
                val hits = repo.search(q, cur.scope, cur.recordId)
                _ui.value = _ui.value.copy(
                    phase = if (hits.isEmpty()) SearchPhase.NoResults else SearchPhase.Results,
                    hits = hits, answer = if (hits.isEmpty()) AnswerUi.Hidden else initialAnswer(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(phase = SearchPhase.Error, error = e.message ?: "Не удалось выполнить поиск")
            }
        }
    }

    private fun initialAnswer(): AnswerUi {
        val caps = repo.answerCaps()
        return when {
            !caps.enabled -> AnswerUi.Disabled
            caps.reason != null -> AnswerUi.Unavailable(caps.reason)
            else -> AnswerUi.Ready
        }
    }

    /** «Ответить»: ответ ИИ по найденным фрагментам. */
    fun answer() {
        val cur = _ui.value
        if (cur.hits.isEmpty() || cur.answer !is AnswerUi.Ready && cur.answer !is AnswerUi.Failed && cur.answer !is AnswerUi.Done && cur.answer !is AnswerUi.Refused) return
        answerJob?.cancel()
        _ui.value = cur.copy(answer = AnswerUi.Generating(""))
        val q = cur.query.trim()
        answerJob = scope.launch {
            try {
                repo.answer(q, cur.hits).collectLatest { ev ->
                    _ui.value = _ui.value.copy(
                        answer = when (ev) {
                            is AnswerEvent.Partial -> AnswerUi.Generating(ev.text)
                            is AnswerEvent.Done -> if (CitationParser.isRefusal(ev.text)) AnswerUi.Refused else AnswerUi.Done(ev.text.trim())
                            is AnswerEvent.Failed -> AnswerUi.Failed(ev.message)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(answer = AnswerUi.Failed(e.message ?: "Не удалось получить ответ"))
            }
        }
    }

    /** «Остановить»: генерация прерывается, фрагменты остаются. */
    fun stopAnswer() {
        answerJob?.cancel()
        val a = _ui.value.answer
        if (a is AnswerUi.Generating) _ui.value = _ui.value.copy(answer = if (a.partial.isBlank()) initialAnswer() else AnswerUi.Done(a.partial))
    }

    private fun watchIndex() {
        indexJob?.cancel()
        val cur = _ui.value
        indexJob = scope.launch { repo.indexState(cur.scope, cur.recordId).collect { _ui.value = _ui.value.copy(index = it) } }
    }

    fun close() { searchJob?.cancel(); answerJob?.cancel(); indexJob?.cancel() }
}
