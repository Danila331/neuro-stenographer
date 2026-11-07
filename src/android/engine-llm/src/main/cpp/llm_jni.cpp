// JNI-обёртка над llama.cpp для ru.neurosteno.llm.LlamaNative.
// Один загруженный GGUF = один handle; generate() потокобезопасностью не обладает (Kotlin-слой синхронизирует).
// Собирается под Android (NDK, arm64-v8a) и под хост (macOS/Linux) -- см. CMakeLists.txt и scripts/build_host_jni.sh.
#include <jni.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define NS_LOG(...) __android_log_print(ANDROID_LOG_INFO, "NeuroLlm", __VA_ARGS__)
#else
#define NS_LOG(...) (std::fprintf(stderr, "[NeuroLlm] " __VA_ARGS__), std::fprintf(stderr, "\n"))
#endif

namespace {

struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    std::string options; // фактически применённые параметры (для диагностики и Kotlin-слоя)
    std::vector<llama_token> kv_tokens; // что сейчас лежит в KV-кэше (последовательность 0): промпт + сгенерированное
    std::atomic<bool> abort{false};
};

std::string g_last_error;
std::once_flag g_backend_once;

void set_error(const std::string &msg) {
    g_last_error = msg;
    NS_LOG("error: %s", msg.c_str());
}

void log_cb(enum ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) NS_LOG("%s", text);
}

// "f16" | "q8_0" | "q4_0" -> тип ggml для K/V кэша; неизвестное значение = f16 (с предупреждением в лог).
ggml_type parse_kv_type(const std::string &name) {
    if (name == "q8_0") return GGML_TYPE_Q8_0;
    if (name == "q4_0") return GGML_TYPE_Q4_0;
    if (name != "f16" && !name.empty()) NS_LOG("неизвестный kv_cache_type '%s' -> f16", name.c_str());
    return GGML_TYPE_F16;
}

const char *kv_name(ggml_type t) {
    return t == GGML_TYPE_Q8_0 ? "q8_0" : t == GGML_TYPE_Q4_0 ? "q4_0" : "f16";
}

bool abort_cb(void *data) { return static_cast<Handle *>(data)->abort.load(); }

// Ссылки на java.lang.Thread: ОДИН раз и потокобезопасно (std::call_once). Раньше ленивая проверка `if (!thread_cls)` у двух экземпляров LLM
// в разных потоках могла увидеть уже заполненный thread_cls, но ещё не заполненные current/is_interrupted -> вызов JNI с нулевым methodID.
struct ThreadRefs {
    jclass cls = nullptr;
    jmethodID current = nullptr;
    jmethodID is_interrupted = nullptr;
    bool ok = false;
};
ThreadRefs g_thread;
std::once_flag g_thread_once;

void init_thread_refs(JNIEnv *env) {
    jclass local = env->FindClass("java/lang/Thread");
    if (!local) { env->ExceptionClear(); NS_LOG("нет java/lang/Thread: прерывание из Java отключено"); return; }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) { env->ExceptionClear(); return; }
    jmethodID current = env->GetStaticMethodID(global, "currentThread", "()Ljava/lang/Thread;");
    jmethodID interrupted = env->GetMethodID(global, "isInterrupted", "()Z");
    if (!current || !interrupted) { env->ExceptionClear(); env->DeleteGlobalRef(global); NS_LOG("нет методов Thread: прерывание из Java отключено"); return; }
    g_thread.cls = global;
    g_thread.current = current;
    g_thread.is_interrupted = interrupted;
    g_thread.ok = true; // последним: после call_once все поля видны всем потокам
}

// Thread.currentThread().isInterrupted() -- отмена из корутин (runInterruptible) доходит до нативного цикла.
bool java_interrupted(JNIEnv *env) {
    std::call_once(g_thread_once, init_thread_refs, env);
    if (!g_thread.ok) return false;
    jobject t = env->CallStaticObjectMethod(g_thread.cls, g_thread.current);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    if (!t) return false;
    jboolean r = env->CallBooleanMethod(t, g_thread.is_interrupted);
    if (env->ExceptionCheck()) { env->ExceptionClear(); r = JNI_FALSE; }
    env->DeleteLocalRef(t);
    return r == JNI_TRUE;
}

// ---- строки: Java (UTF-16) <-> стандартный UTF-8 ----
// GetStringUTFChars отдаёт «модифицированный UTF-8» (символы вне BMP -- как две суррогатные трёхбайтовые последовательности, U+0000 -- как C0 80),
// а токенизатор llama ждёт обычный UTF-8: emoji в расшифровке превращались в мусор. Конвертируем сами.
void append_utf8(std::string &out, uint32_t cp) {
    if (cp < 0x80) out.push_back(static_cast<char>(cp));
    else if (cp < 0x800) { out.push_back(static_cast<char>(0xC0 | (cp >> 6))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F))); }
    else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18))); out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F))); out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// UTF-16 -> UTF-8: пары суррогатов -> 4 байта; одиночный суррогат -> U+FFFD. Чистая функция (тестируется без JVM-вызовов).
std::string utf16_to_utf8(const jchar *u, size_t n) {
    std::string out;
    out.reserve(n * 3);
    for (size_t i = 0; i < n; i++) {
        uint32_t c = u[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        append_utf8(out, c);
    }
    return out;
}

// UTF-8 -> UTF-16; неверные последовательности -> U+FFFD.
std::u16string utf8_to_utf16(const std::string &s) {
    std::u16string out;
    out.reserve(s.size());
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (c < 0x80) { cp = c; }
        else if (c >= 0xC2 && c <= 0xDF && i + 1 < n && (s[i + 1] & 0xC0) == 0x80) { cp = ((c & 0x1F) << 6) | (s[i + 1] & 0x3F); len = 2; }
        else if (c >= 0xE0 && c <= 0xEF && i + 2 < n && (s[i + 1] & 0xC0) == 0x80 && (s[i + 2] & 0xC0) == 0x80) {
            uint32_t v = ((c & 0x0F) << 12) | ((s[i + 1] & 0x3F) << 6) | (s[i + 2] & 0x3F);
            if (v >= 0x800 && !(v >= 0xD800 && v <= 0xDFFF)) { cp = v; len = 3; }
        } else if (c >= 0xF0 && c <= 0xF4 && i + 3 < n && (s[i + 1] & 0xC0) == 0x80 && (s[i + 2] & 0xC0) == 0x80 && (s[i + 3] & 0xC0) == 0x80) {
            uint32_t v = ((c & 0x07) << 18) | ((s[i + 1] & 0x3F) << 12) | ((s[i + 2] & 0x3F) << 6) | (s[i + 3] & 0x3F);
            if (v >= 0x10000 && v <= 0x10FFFF) { cp = v; len = 4; }
        }
        if (cp >= 0x10000) { cp -= 0x10000; out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10))); out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF))); }
        else out.push_back(static_cast<char16_t>(cp));
        i += len;
    }
    return out;
}

// jstring -> стандартный UTF-8. false -- не хватило памяти (исключение OutOfMemoryError уже ожидает в JVM): вызывающий сразу возвращается.
// null-строка даёт пустую.
bool to_utf8(JNIEnv *env, jstring s, std::string &out) {
    out.clear();
    if (!s) return true;
    jsize n = env->GetStringLength(s);
    const jchar *u = env->GetStringChars(s, nullptr);
    if (!u) return false;
    out = utf16_to_utf8(u, static_cast<size_t>(n));
    env->ReleaseStringChars(s, u);
    return true;
}

// UTF-8 -> jstring (null и ожидающее исключение при нехватке памяти).
jstring from_utf8(JNIEnv *env, const std::string &s) {
    std::u16string u = utf8_to_utf16(s);
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size()));
}

// nullptr при нехватке памяти (исключение OutOfMemoryError ожидает в JVM).
jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr && !s.empty()) env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// Длина префикса s, заканчивающегося на границе символа UTF-8 (хвост с недописанным символом откладывается).
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { i--; back++; continue; } // продолжение
        int need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        return (n - (i - 1) >= static_cast<size_t>(need)) ? n : i - 1;
    }
    return n;
}

// Сэмплер вызова: цепочка [грамматика GBNF] -> greedy. Если грамматики нет -- общий greedy-сэмплер хэндла.
struct CallSampler {
    llama_sampler *chain = nullptr;
    ~CallSampler() { if (chain) llama_sampler_free(chain); }
};

// Общая реализация generate. grammar != nullptr -- вывод строго по GBNF (корень `root`); reuse -- не пересчитывать префикс промпта,
// совпадающий с содержимым KV-кэша (общий системный промпт между фрагментами выжимки: ×1.7 к скорости без него).
jbyteArray generate_impl(JNIEnv *env, Handle *h, const std::string &prompt, int max_tokens, const std::string *grammar, bool reuse,
                         jobject callback) {
    h->abort.store(false);
    jmethodID on_token = nullptr;
    if (callback) {
        jclass cb_cls = env->GetObjectClass(callback);
        if (!cb_cls) { set_error("нет класса колбэка"); return nullptr; }
        on_token = env->GetMethodID(cb_cls, "onToken", "([B)Z");
        env->DeleteLocalRef(cb_cls);
        if (!on_token) { env->ExceptionClear(); set_error("у колбэка нет метода onToken([B)Z"); return nullptr; }
    }
    auto invalidate = [&] { // любой сбой: кэш и его описание сбрасываем вместе, иначе следующий вызов переиспользует мусор
        llama_memory_clear(llama_get_memory(h->ctx), true);
        h->kv_tokens.clear();
    };

    // --- токенизация промпта (спец-токены ChatML парсим как токены) ---
    int n_max = static_cast<int>(prompt.size()) + 16;
    std::vector<llama_token> tokens(n_max);
    int n = llama_tokenize(h->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n_max, true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(h->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), -n, true, true);
    }
    if (n <= 0) { set_error("токенизация вернула " + std::to_string(n)); return nullptr; }
    tokens.resize(n);
    if (n + 1 >= h->n_ctx) {
        set_error("промпт (" + std::to_string(n) + " токенов) не помещается в контекст " + std::to_string(h->n_ctx));
        return nullptr;
    }
    int budget = std::min<int>(max_tokens, h->n_ctx - n - 1);

    // --- сэмплер ---
    CallSampler local;
    llama_sampler *smpl = h->smpl;
    if (grammar && !grammar->empty()) {
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        local.chain = llama_sampler_chain_init(sp);
        llama_sampler *g = llama_sampler_init_grammar(h->vocab, grammar->c_str(), "root");
        if (!g) { set_error("грамматика GBNF не разобрана"); return nullptr; }
        llama_sampler_chain_add(local.chain, g);
        llama_sampler_chain_add(local.chain, llama_sampler_init_greedy());
        smpl = local.chain;
    } else {
        llama_sampler_reset(h->smpl);
    }

    // --- префикс из KV-кэша ---
    int keep = 0;
    if (reuse) {
        size_t lim = std::min(h->kv_tokens.size(), static_cast<size_t>(n - 1)); // последний токен промпта пересчитываем всегда (нужны логиты)
        while (static_cast<size_t>(keep) < lim && h->kv_tokens[keep] == tokens[keep]) keep++;
    }
    if (keep > 0) {
        if (!llama_memory_seq_rm(llama_get_memory(h->ctx), 0, keep, -1)) { keep = 0; }
    }
    if (keep == 0) llama_memory_clear(llama_get_memory(h->ctx), true);
    h->kv_tokens.resize(keep);
    if (reuse) NS_LOG("префикс из кэша: %d из %d токенов промпта", keep, n);

    // --- prefill кусками по n_batch ---
    for (int i = keep; i < n; i += h->n_batch) {
        if (java_interrupted(env)) { set_error("прервано"); invalidate(); return nullptr; }
        int cnt = std::min(h->n_batch, n - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, cnt);
        int rc = llama_decode(h->ctx, b);
        if (rc != 0) { set_error("llama_decode(prefill) = " + std::to_string(rc)); invalidate(); return nullptr; }
        h->kv_tokens.insert(h->kv_tokens.end(), tokens.begin() + i, tokens.begin() + i + cnt);
    }

    // --- генерация ---
    std::string out;       // весь результат (байты UTF-8)
    std::string pending;   // накопленное, ещё не отданное колбэку
    char piece[256];
    for (int i = 0; i < budget; i++) {
        if (java_interrupted(env)) { set_error("прервано"); invalidate(); return nullptr; }
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int len = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (len < 0) {
            std::vector<char> big(-len);
            len = llama_token_to_piece(h->vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, false);
            if (len > 0) { out.append(big.data(), len); pending.append(big.data(), len); }
        } else if (len > 0) {
            out.append(piece, len);
            pending.append(piece, len);
        }
        if (on_token && !pending.empty()) {
            size_t ok = utf8_complete_prefix(pending);
            if (ok > 0) {
                jbyteArray arr = to_bytes(env, pending.substr(0, ok));
                if (!arr) { invalidate(); return nullptr; } // OutOfMemoryError уже ожидает в JVM
                jboolean keep_going = env->CallBooleanMethod(callback, on_token, arr);
                env->DeleteLocalRef(arr);
                pending.erase(0, ok);
                if (env->ExceptionCheck()) { env->ExceptionClear(); set_error("исключение в колбэке"); invalidate(); return nullptr; }
                if (keep_going == JNI_FALSE) break;
            }
        }
        llama_batch b = llama_batch_get_one(&tok, 1);
        int rc = llama_decode(h->ctx, b);
        if (rc != 0) { set_error("llama_decode(gen) = " + std::to_string(rc)); invalidate(); return nullptr; }
        h->kv_tokens.push_back(tok);
    }
    return to_bytes(env, out);
}

} // namespace

extern "C" {

// Параметры памяти и скорости (BACKLOG B48), все -- через llama_model_params / llama_context_params этой версии llama.cpp:
//   no_repack  -> model.use_extra_bufts = false   (веса не перепаковываются в отдельный буфер: -1 ГБ RSS у 1.7B, prefill медленнее);
//   use_mmap   -> model.load_mode = MMAP | NONE   (в этой версии булево use_mmap заменено перечислением llama_load_mode);
//   flash_attn -> ctx.flash_attn_type = ENABLED | DISABLED;
//   kv_cache   -> ctx.type_k = ctx.type_v          (квантованный V требует flash attention: без него контекст не создаётся).
// Обход: если kv_cache квантован, а flash_attn выключен -- flash включается принудительно; если контекст с такими параметрами
// не создался (например, бэкенд не умеет flash attention для этой модели) -- повтор с f16 и flash AUTO.
JNIEXPORT jlong JNICALL Java_ru_neurosteno_llm_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads,
                                                                jint n_gpu_layers, jboolean no_repack, jboolean flash_attn,
                                                                jstring jkv, jboolean use_mmap) {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
    });
    if (!jpath) { set_error("путь к модели не задан"); return 0; }
    std::string p;
    if (!to_utf8(env, jpath, p)) return 0; // OutOfMemoryError ожидает в JVM
    std::string kv_str = "f16";
    if (jkv && !to_utf8(env, jkv, kv_str)) return 0;

    auto *h = new Handle();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.use_extra_bufts = no_repack == JNI_FALSE;
    mp.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    h->model = llama_model_load_from_file(p.c_str(), mp);
    if (!h->model) {
        set_error("не удалось загрузить модель: " + p);
        delete h;
        return 0;
    }
    h->vocab = llama_model_get_vocab(h->model);

    ggml_type kv_type = parse_kv_type(kv_str);
    bool fa = flash_attn == JNI_TRUE;
    if (kv_type != GGML_TYPE_F16 && !fa) {
        NS_LOG("квантованный KV-кэш (%s) требует flash attention -- включаю его", kv_name(kv_type));
        fa = true;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = h;
    cp.flash_attn_type = fa ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.type_k = kv_type;
    cp.type_v = kv_type;
    h->ctx = llama_init_from_model(h->model, cp);
    if (!h->ctx && (fa || kv_type != GGML_TYPE_F16)) {
        NS_LOG("контекст не создан с flash_attn=%d kv=%s -- повтор: f16, flash AUTO", fa ? 1 : 0, kv_name(kv_type));
        fa = false;
        kv_type = GGML_TYPE_F16;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cp.type_k = kv_type;
        cp.type_v = kv_type;
        h->ctx = llama_init_from_model(h->model, cp);
    }
    if (!h->ctx) {
        set_error("не удалось создать контекст (мало памяти? n_ctx=" + std::to_string(n_ctx) + ")");
        llama_model_free(h->model);
        delete h;
        return 0;
    }
    h->n_ctx = static_cast<int>(llama_n_ctx(h->ctx));
    h->n_batch = static_cast<int>(cp.n_batch);
    h->options = std::string("no_repack=") + (mp.use_extra_bufts ? "0" : "1") + " mmap=" + (use_mmap == JNI_TRUE ? "1" : "0") +
                 " flash_attn=" + llama_flash_attn_type_name(cp.flash_attn_type) + " kv=" + kv_name(kv_type) +
                 " n_ctx=" + std::to_string(h->n_ctx) + " threads=" + std::to_string(n_threads);
    NS_LOG("модель загружена: %s", h->options.c_str());

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    h->smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(h->smpl, llama_sampler_init_greedy()); // детерминированно: пунктуация не должна «творить»
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_ru_neurosteno_llm_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) return;
    if (h->smpl) llama_sampler_free(h->smpl);
    if (h->ctx) llama_free(h->ctx);
    if (h->model) llama_model_free(h->model);
    delete h;
}

JNIEXPORT void JNICALL Java_ru_neurosteno_llm_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h) h->abort.store(true);
}

JNIEXPORT jstring JNICALL Java_ru_neurosteno_llm_LlamaNative_lastError(JNIEnv *env, jclass) {
    return from_utf8(env, g_last_error); // в сообщении может быть путь модели с любыми символами
}

// Фактически применённые параметры загрузки (после обходов), строкой `key=value ...`.
JNIEXPORT jstring JNICALL Java_ru_neurosteno_llm_LlamaNative_options(JNIEnv *env, jclass, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    return from_utf8(env, h ? h->options : std::string());
}

JNIEXPORT jstring JNICALL Java_ru_neurosteno_llm_LlamaNative_systemInfo(JNIEnv *env, jclass) {
    return from_utf8(env, llama_print_system_info());
}

// Возвращает UTF-8 байты сгенерированного текста или null при ошибке (см. lastError). Колбэк: fun onToken(bytes: ByteArray): Boolean
// Прежнее поведение: каждый вызов с чистого KV-кэша, без грамматики.
JNIEXPORT jbyteArray JNICALL Java_ru_neurosteno_llm_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jstring jprompt,
                                                                        jint max_tokens, jobject callback) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) { set_error("handle == 0"); return nullptr; }
    std::string prompt;
    if (!to_utf8(env, jprompt, prompt)) return nullptr;
    return generate_impl(env, h, prompt, max_tokens, nullptr, false, callback);
}

// Расширенная генерация (B50): jgrammar -- GBNF (корень `root`; null -- без ограничения), reuse -- переиспользовать общий префикс KV-кэша.
JNIEXPORT jbyteArray JNICALL Java_ru_neurosteno_llm_LlamaNative_generateEx(JNIEnv *env, jclass, jlong handle, jstring jprompt, jint max_tokens,
                                                                          jstring jgrammar, jboolean reuse, jobject callback) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) { set_error("handle == 0"); return nullptr; }
    std::string prompt;
    if (!to_utf8(env, jprompt, prompt)) return nullptr;
    if (jgrammar) {
        std::string g;
        if (!to_utf8(env, jgrammar, g)) return nullptr;
        return generate_impl(env, h, prompt, max_tokens, &g, reuse == JNI_TRUE, callback);
    }
    return generate_impl(env, h, prompt, max_tokens, nullptr, reuse == JNI_TRUE, callback);
}

// ---- Только для тестов (B58): преобразование строк, без модели ----

// Стандартный UTF-8 байты строки (как уходят в токенизатор). null -- нехватка памяти.
JNIEXPORT jbyteArray JNICALL Java_ru_neurosteno_llm_LlamaNative_utf8Bytes(JNIEnv *env, jclass, jstring s) {
    std::string out;
    if (!to_utf8(env, s, out)) return nullptr;
    return to_bytes(env, out);
}

// Прежнее поведение (GetStringUTFChars = «модифицированный UTF-8», emoji как суррогатные пары по 3 байта) -- для воспроизведения ошибки в тесте.
JNIEXPORT jbyteArray JNICALL Java_ru_neurosteno_llm_LlamaNative_legacyUtf8Bytes(JNIEnv *env, jclass, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    if (!c) return nullptr;
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return to_bytes(env, out);
}

// Круговой обход UTF-16 -> UTF-8 -> UTF-16 (то, что получает Java в lastError/options).
JNIEXPORT jstring JNICALL Java_ru_neurosteno_llm_LlamaNative_utf8RoundTrip(JNIEnv *env, jclass, jstring s) {
    std::string out;
    if (!to_utf8(env, s, out)) return nullptr;
    return from_utf8(env, out);
}

} // extern "C"
