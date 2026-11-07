package ru.neurosteno.app.util

/**
 * «Единственный слот обработки + очередь» (B58): сервис обрабатывает по одной записи, остальные запросы ждут в очереди либо получают
 * явный отказ. Все операции под одним замком: между «слот свободен» и «слот занят» нет окна, в которое мог бы проскочить запрос
 * (гонка «задание заканчивается / приходит новый intent» раньше теряла запрос молча).
 */
class ProcessingQueue<T>(private val capacity: Int = DEFAULT_CAPACITY) {
    sealed interface Submit {
        /** Слот был свободен и теперь занят этим запросом: запускать немедленно. */
        data object Start : Submit

        /** Слот занят: запрос в очереди на месте [position] (с 1). */
        data class Queued(val position: Int) : Submit

        /** Очередь переполнена: запрос НЕ принят (вызывающий обязан сообщить об отказе). */
        data object Full : Submit
    }

    private val items = ArrayDeque<T>()
    private var busy = false

    @Synchronized
    fun submit(item: T): Submit {
        if (!busy) {
            busy = true
            return Submit.Start
        }
        if (items.size >= capacity) return Submit.Full
        items.addLast(item)
        return Submit.Queued(items.size)
    }

    /** Занять слот без очереди (пересчёт фрагмента, выжимка): false -- занято. */
    @Synchronized
    fun tryAcquire(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    /** Задание закончилось: следующий запрос из очереди (слот остаётся занятым) либо null (слот освобождён). */
    @Synchronized
    fun release(): T? {
        val next = items.removeFirstOrNull()
        if (next == null) busy = false
        return next
    }

    @Synchronized
    fun snapshot(): List<T> = items.toList()

    @get:Synchronized
    val isBusy: Boolean get() = busy

    /** Убрать все ожидающие запросы (служба останавливается): возвращает их, чтобы сообщить об отмене. */
    @Synchronized
    fun drain(): List<T> = items.toList().also { items.clear() }

    companion object {
        const val DEFAULT_CAPACITY = 32
    }
}
