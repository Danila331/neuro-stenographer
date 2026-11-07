// JNI-обёртка над llama.cpp для ru.neurosteno.llm.LlamaNative.
// Один загруженный GGUF = один handle; generate() потокобезопасностью не обладает (Kotlin-слой синхронизирует).
// Собирается под Android (NDK, arm64-v8a) и под хост (macOS/Linux) -- см. CMakeLists.txt и scripts/build_host_jni.sh.
#include <jni.h>

#include <atomic>
#include <cstdarg>
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

// Thread.currentThread().isInterrupted() -- отмена из корутин (runInterruptible) доходит до нативного цикла.
bool java_interrupted(JNIEnv *env) {
    static jclass thread_cls = nullptr;
    static jmethodID current = nullptr;
    static jmethodID is_interrupted = nullptr;
    if (!thread_cls) {
        jclass local = env->FindClass("java/lang/Thread");
        if (!local) { env->ExceptionClear(); return false; }
        thread_cls = static_cast<jclass>(env->NewGlobalRef(local));
        current = env->GetStaticMethodID(thread_cls, "currentThread", "()Ljava/lang/Thread;");
        is_interrupted = env->GetMethodID(thread_cls, "isInterrupted", "()Z");
    }
    jobject t = env->CallStaticObjectMethod(thread_cls, current);
    if (!t) return false;
    jboolean r = env->CallBooleanMethod(t, is_interrupted);
    env->DeleteLocalRef(t);
    return r == JNI_TRUE;
}

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
        on_token = env->GetMethodID(cb_cls, "onToken", "([B)Z");
        env->DeleteLocalRef(cb_cls);
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

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
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
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    std::string p(path);
    env->ReleaseStringUTFChars(jpath, path);
    std::string kv_str = "f16";
    if (jkv) {
        const char *kv = env->GetStringUTFChars(jkv, nullptr);
        kv_str = kv;
        env->ReleaseStringUTFChars(jkv, kv);
    }

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
    return env->NewStringUTF(g_last_error.c_str()); // ASCII-безопасно: сообщения содержат только BMP-текст без суррогатов
}

// Фактически применённые параметры загрузки (после обходов), строкой `key=value ...`.
JNIEXPORT jstring JNICALL Java_ru_neurosteno_llm_LlamaNative_options(JNIEnv *env, jclass, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    return env->NewStringUTF(h ? h->options.c_str() : "");
}

JNIEXPORT jstring JNICALL Java_ru_neurosteno_llm_LlamaNative_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

// Возвращает UTF-8 байты сгенерированного текста или null при ошибке (см. lastError). Колбэк: fun onToken(bytes: ByteArray): Boolean
// Прежнее поведение: каждый вызов с чистого KV-кэша, без грамматики.
JNIEXPORT jbyteArray JNICALL Java_ru_neurosteno_llm_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jstring jprompt,
                                                                        jint max_tokens, jobject callback) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) { set_error("handle == 0"); return nullptr; }
    return generate_impl(env, h, jstr(env, jprompt), max_tokens, nullptr, false, callback);
}

// Расширенная генерация (B50): jgrammar -- GBNF (корень `root`; null -- без ограничения), reuse -- переиспользовать общий префикс KV-кэша.
JNIEXPORT jbyteArray JNICALL Java_ru_neurosteno_llm_LlamaNative_generateEx(JNIEnv *env, jclass, jlong handle, jstring jprompt, jint max_tokens,
                                                                          jstring jgrammar, jboolean reuse, jobject callback) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (!h) { set_error("handle == 0"); return nullptr; }
    std::string prompt = jstr(env, jprompt);
    if (jgrammar) {
        std::string g = jstr(env, jgrammar);
        return generate_impl(env, h, prompt, max_tokens, &g, reuse == JNI_TRUE, callback);
    }
    return generate_impl(env, h, prompt, max_tokens, nullptr, reuse == JNI_TRUE, callback);
}

} // extern "C"
