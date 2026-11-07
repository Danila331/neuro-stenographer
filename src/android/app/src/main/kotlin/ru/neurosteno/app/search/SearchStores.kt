package ru.neurosteno.app.search

import android.content.Context
import android.util.Log
import java.io.File
import ru.neurosteno.core.search.BruteForceVectorStore
import ru.neurosteno.core.search.SearchConfig
import ru.neurosteno.core.search.VectorStore
import ru.neurosteno.store.ObjectBoxVectorStore

/** Открытое хранилище векторов и то, что реально используется (`objectbox` | `bruteforce`; [note] -- причина запасного пути). */
class OpenedStore(val store: VectorStore, val kind: String, val note: String? = null)

/**
 * Хранилище векторов по `search.store`: ObjectBox (HNSW; размерность индекса 384) либо полный перебор. При любой проблеме с ObjectBox
 * (нативная библиотека не загрузилась, не та размерность, повреждённая база) -- `search.store.fallback` (по умолчанию bruteforce).
 */
object SearchStores {
    private const val TAG = "Neurosteno"

    fun open(context: Context, cfg: SearchConfig, dir: File): OpenedStore {
        val dim = cfg.embedder.dim
        if (cfg.store.kind == "objectbox") {
            if (dim != ObjectBoxVectorStore.DIM) {
                return fallback(cfg, dir, "ObjectBox рассчитан на ${ObjectBoxVectorStore.DIM}-мерные векторы, у эмбеддера $dim")
            }
            try {
                return OpenedStore(ObjectBoxVectorStore.open(File(dir, "objectbox").also { it.mkdirs() }, context), "objectbox")
            } catch (e: Throwable) {
                Log.e(TAG, "ObjectBox не открылся, запасной путь: ${cfg.store.fallback}", e)
                return fallback(cfg, dir, "ObjectBox: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return OpenedStore(BruteForceVectorStore(dim, File(dir, "vec")), "bruteforce")
    }

    private fun fallback(cfg: SearchConfig, dir: File, why: String): OpenedStore {
        require(cfg.store.fallback == "bruteforce") { "неизвестный запасной путь ${cfg.store.fallback}" }
        return OpenedStore(BruteForceVectorStore(cfg.embedder.dim, File(dir, "vec")), "bruteforce", why)
    }
}
