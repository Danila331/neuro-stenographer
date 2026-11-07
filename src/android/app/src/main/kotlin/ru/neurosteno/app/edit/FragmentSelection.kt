package ru.neurosteno.app.edit

/**
 * Выделение соседних реплик для «Обработать заново»: непрерывный диапазон индексов [first]..[last].
 * Логика нажатий (режим выделения): нажатие вне диапазона расширяет его до этой реплики (реплики между ними включаются, диапазон
 * остаётся непрерывным); нажатие на крайнюю реплику убирает её; нажатие на внутреннюю обрезает хвост до неё; последнюю оставшуюся
 * реплику можно снять -- выделение закончится ([toggle] вернёт null).
 */
data class FragmentSelection(val first: Int, val last: Int) {
    init {
        require(first <= last) { "first <= last" }
    }

    val size: Int get() = last - first + 1
    operator fun contains(index: Int): Boolean = index in first..last

    fun toggle(index: Int): FragmentSelection? = when {
        index !in this -> FragmentSelection(minOf(first, index), maxOf(last, index))
        size == 1 -> null
        index == first -> FragmentSelection(first + 1, last)
        index == last -> FragmentSelection(first, last - 1)
        else -> FragmentSelection(first, index)
    }

    /** Сохраняет выделение в `rememberSaveable` как одно число-пару. */
    fun encode(): Long = (first.toLong() shl 32) or (last.toLong() and 0xFFFFFFFFL)

    companion object {
        fun decode(v: Long): FragmentSelection = FragmentSelection((v shr 32).toInt(), (v and 0xFFFFFFFFL).toInt())

        /** Выделение после изменения списка реплик (удаление/объединение): зажимаем в `[0, size)`; null, если списка нет. */
        fun clamp(sel: FragmentSelection?, size: Int): FragmentSelection? {
            if (sel == null || size <= 0) return null
            val f = sel.first.coerceIn(0, size - 1)
            val l = sel.last.coerceIn(f, size - 1)
            return FragmentSelection(f, l)
        }
    }
}
