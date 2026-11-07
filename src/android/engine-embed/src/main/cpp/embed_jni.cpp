// Тонкая JNI-обёртка над C API ONNX Runtime для эмбеддера поиска (B51).
//
// Зачем не официальный Java-API onnxruntime-android: sherpa-onnx уже несёт свой libonnxruntime.so (1.28.2) с версионированными
// символами `OrtGetApiBase@@VERS_1.28.2`, а JNI официальной сборки требует `OrtGetApiBase@VERS_1.28.0` -- вместе в одном процессе они
// не работают (dlopen failed: cannot locate symbol). Поэтому используем ТУ ЖЕ библиотеку, что и sherpa-onnx, через dlopen/dlsym
// (без версий символов), а заголовки берём официальные (MIT; third_party/onnxruntime). Одна копия ORT на приложение.
#include <jni.h>

#include <dlfcn.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NeurostenoEmbed", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

namespace {

std::mutex g_mu;
std::string g_lib_path;  // пусто -> имя по умолчанию платформы
std::string g_version;   // версия загруженной библиотеки ORT
const OrtApi* g_api = nullptr;
OrtEnv* g_env = nullptr;

struct Handle {
  OrtSession* session = nullptr;
  std::vector<std::string> input_names;
  std::string output_name;
};

void free_str(OrtAllocator* alloc, void* p) {
  if (OrtStatus* s = g_api->AllocatorFree(alloc, p)) g_api->ReleaseStatus(s);
}

// ---- строки: Java (UTF-16) <-> стандартный UTF-8 (то же, что в llm_jni.cpp; GetStringUTFChars отдаёт modified UTF-8) ----
void append_utf8(std::string& out, uint32_t cp) {
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

std::string utf16_to_utf8(const jchar* u, size_t n) {
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

std::u16string utf8_to_utf16(const std::string& s) {
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

// false -- не хватило памяти (OutOfMemoryError уже ожидает в JVM); null-строка даёт пустую.
bool to_utf8(JNIEnv* env, jstring s, std::string& out) {
  out.clear();
  if (!s) return true;
  jsize n = env->GetStringLength(s);
  const jchar* u = env->GetStringChars(s, nullptr);
  if (!u) return false;
  out = utf16_to_utf8(u, static_cast<size_t>(n));
  env->ReleaseStringChars(s, u);
  return true;
}

// Исключение с текстом в UTF-16 (в сообщении может быть путь с emoji: ThrowNew ждёт modified UTF-8).
void throw_java(JNIEnv* env, const char* cls, const std::string& msg) {
  if (env->ExceptionCheck()) return; // уже есть исключение (например, OutOfMemoryError) -- не затираем
  jclass c = env->FindClass(cls);
  if (c == nullptr) return;
  jmethodID ctor = env->GetMethodID(c, "<init>", "(Ljava/lang/String;)V");
  std::u16string u = utf8_to_utf16(msg);
  jstring js = ctor ? env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size())) : nullptr;
  if (ctor && js) {
    jobject ex = env->NewObject(c, ctor, js);
    if (ex) env->Throw(static_cast<jthrowable>(ex));
  }
  env->DeleteLocalRef(c);
}

// true -> ошибка (исключение Java уже брошено); статус освобождается
bool check(JNIEnv* env, OrtStatus* st) {
  if (st == nullptr) return false;
  std::string msg = g_api->GetErrorMessage(st);
  g_api->ReleaseStatus(st);
  throw_java(env, "java/lang/IllegalStateException", "onnxruntime: " + msg);
  return true;
}

bool init_api(JNIEnv* env) {
  std::lock_guard<std::mutex> lock(g_mu);
  if (g_api != nullptr) return true;
#ifdef __APPLE__
  const char* def = "libonnxruntime.dylib";
#else
  const char* def = "libonnxruntime.so";
#endif
  const char* path = g_lib_path.empty() ? def : g_lib_path.c_str();
  void* lib = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
  if (lib == nullptr) {
    throw_java(env, "java/lang/UnsatisfiedLinkError", std::string("не загрузилась ") + path + ": " + dlerror());
    return false;
  }
  using GetApiBase = const OrtApiBase* (*)();
  auto base = reinterpret_cast<GetApiBase>(dlsym(lib, "OrtGetApiBase"));
  if (base == nullptr) {
    throw_java(env, "java/lang/UnsatisfiedLinkError", std::string("в ") + path + " нет OrtGetApiBase");
    return false;
  }
  const OrtApi* api = base()->GetApi(ORT_API_VERSION);
  if (api == nullptr) {
    throw_java(env, "java/lang/UnsatisfiedLinkError", std::string("библиотека ") + path + " не поддерживает ORT API " + std::to_string(ORT_API_VERSION) +
                                                          " (версия " + base()->GetVersionString() + ")");
    return false;
  }
  g_version = base()->GetVersionString();
  g_api = api;
  OrtEnv* e = nullptr;
  if (check(env, g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "neurosteno", &e))) { g_api = nullptr; return false; }
  g_env = e;
  return true;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_ru_neurosteno_embed_OrtNative_setLibraryPath(JNIEnv* env, jclass, jstring path) {
  std::string p;
  if (!to_utf8(env, path, p)) return;
  std::lock_guard<std::mutex> lock(g_mu);
  g_lib_path = p;
}

JNIEXPORT jstring JNICALL Java_ru_neurosteno_embed_OrtNative_version(JNIEnv* env, jclass) {
  if (!init_api(env)) return nullptr;
  std::u16string u = utf8_to_utf16(g_version);
  return env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size()));
}

JNIEXPORT jlong JNICALL Java_ru_neurosteno_embed_OrtNative_create(JNIEnv* env, jclass, jstring model, jint threads) {
  if (!init_api(env)) return 0;
  OrtSessionOptions* opts = nullptr;
  if (check(env, g_api->CreateSessionOptions(&opts))) return 0;
  bool bad = check(env, g_api->SetIntraOpNumThreads(opts, threads > 0 ? threads : 1)) ||
             check(env, g_api->SetInterOpNumThreads(opts, 1)) ||
             check(env, g_api->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL)) ||
             // динамические длины батчей: без арены и шаблона памяти пик памяти не растёт от разнородных форм
             check(env, g_api->DisableCpuMemArena(opts)) || check(env, g_api->DisableMemPattern(opts));
  if (bad) { g_api->ReleaseSessionOptions(opts); return 0; }
  std::string path;
  if (model == nullptr) { g_api->ReleaseSessionOptions(opts); throw_java(env, "java/lang/IllegalArgumentException", "путь к модели не задан"); return 0; }
  if (!to_utf8(env, model, path)) { g_api->ReleaseSessionOptions(opts); return 0; }
  OrtSession* session = nullptr;
  OrtStatus* st = g_api->CreateSession(g_env, path.c_str(), opts, &session);
  g_api->ReleaseSessionOptions(opts);
  if (check(env, st)) return 0;

  auto* h = new Handle();
  h->session = session;
  OrtAllocator* alloc = nullptr;
  if (check(env, g_api->GetAllocatorWithDefaultOptions(&alloc))) { g_api->ReleaseSession(session); delete h; return 0; }
  size_t n_in = 0, n_out = 0;
  if (check(env, g_api->SessionGetInputCount(session, &n_in)) || check(env, g_api->SessionGetOutputCount(session, &n_out))) {
    g_api->ReleaseSession(session); delete h; return 0;
  }
  for (size_t i = 0; i < n_in; i++) {
    char* name = nullptr;
    if (check(env, g_api->SessionGetInputName(session, i, alloc, &name))) { g_api->ReleaseSession(session); delete h; return 0; }
    h->input_names.emplace_back(name);
    free_str(alloc, name);
  }
  char* oname = nullptr;
  if (n_out == 0 || check(env, g_api->SessionGetOutputName(session, 0, alloc, &oname))) {
    if (n_out == 0) throw_java(env, "java/lang/IllegalStateException", "у модели нет выходов");
    g_api->ReleaseSession(session); delete h; return 0;
  }
  h->output_name = oname;
  free_str(alloc, oname);
  return reinterpret_cast<jlong>(h);
}

JNIEXPORT jobjectArray JNICALL Java_ru_neurosteno_embed_OrtNative_inputNames(JNIEnv* env, jclass, jlong handle) {
  auto* h = reinterpret_cast<Handle*>(handle);
  jclass sc = env->FindClass("java/lang/String");
  if (!sc) return nullptr;
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(h->input_names.size()), sc, nullptr);
  env->DeleteLocalRef(sc);
  if (!arr) return nullptr;
  for (size_t i = 0; i < h->input_names.size(); i++) {
    std::u16string u = utf8_to_utf16(h->input_names[i]);
    jstring js = env->NewString(reinterpret_cast<const jchar *>(u.data()), static_cast<jsize>(u.size()));
    if (!js) return nullptr; // OutOfMemoryError ожидает в JVM
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
    env->DeleteLocalRef(js);
  }
  return arr;
}

// Вход: input_ids / attention_mask [/ token_type_ids] int64 формы [batch, seq]; выход: первый выход модели (float32, [batch, seq, hidden])
// копируется в out. Возвращает число скопированных float (batch * seq * hidden).
JNIEXPORT jint JNICALL Java_ru_neurosteno_embed_OrtNative_run(JNIEnv* env, jclass, jlong handle, jlongArray ids, jlongArray mask, jlongArray types,
                                                              jint batch, jint seq, jfloatArray out) {
  auto* h = reinterpret_cast<Handle*>(handle);
  const size_t count = static_cast<size_t>(batch) * static_cast<size_t>(seq);
  std::vector<int64_t> v_ids(count), v_mask(count), v_types;
  if (h == nullptr || ids == nullptr || mask == nullptr || out == nullptr) { throw_java(env, "java/lang/NullPointerException", "нулевой аргумент"); return -1; }
  env->GetLongArrayRegion(ids, 0, static_cast<jsize>(count), reinterpret_cast<jlong*>(v_ids.data()));
  env->GetLongArrayRegion(mask, 0, static_cast<jsize>(count), reinterpret_cast<jlong*>(v_mask.data()));
  if (types != nullptr) {
    v_types.resize(count);
    env->GetLongArrayRegion(types, 0, static_cast<jsize>(count), reinterpret_cast<jlong*>(v_types.data()));
  }
  if (env->ExceptionCheck()) return -1; // выход за границы массива -- исключение уже ожидает
  OrtMemoryInfo* mem = nullptr;
  if (check(env, g_api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem))) return -1;
  int64_t shape[2] = {batch, seq};
  std::vector<OrtValue*> inputs;
  std::vector<const char*> names;
  auto add = [&](const std::string& nm, std::vector<int64_t>& data) -> bool {
    OrtValue* v = nullptr;
    if (check(env, g_api->CreateTensorWithDataAsOrtValue(mem, data.data(), data.size() * sizeof(int64_t), shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &v))) return false;
    inputs.push_back(v);
    names.push_back(nm.c_str());
    return true;
  };
  bool ok = true;
  for (const auto& nm : h->input_names) {
    if (nm == "input_ids") ok = ok && add(nm, v_ids);
    else if (nm == "attention_mask") ok = ok && add(nm, v_mask);
    else if (nm == "token_type_ids") {
      if (v_types.empty()) v_types.assign(count, 0);
      ok = ok && add(nm, v_types);
    } else {
      throw_java(env, "java/lang/IllegalStateException", "неизвестный вход модели: " + nm);
      ok = false;
    }
    if (!ok) break;
  }
  jint result = -1;
  OrtValue* output = nullptr;
  if (ok) {
    const char* out_names[1] = {h->output_name.c_str()};
    OrtStatus* st = g_api->Run(h->session, nullptr, names.data(), inputs.data(), inputs.size(), out_names, 1, &output);
    if (!check(env, st)) {
      float* data = nullptr;
      OrtTensorTypeAndShapeInfo* info = nullptr;
      if (!check(env, g_api->GetTensorMutableData(output, reinterpret_cast<void**>(&data))) && !check(env, g_api->GetTensorTypeAndShape(output, &info))) {
        size_t total = 0;
        if (!check(env, g_api->GetTensorShapeElementCount(info, &total))) {
          if (total > static_cast<size_t>(env->GetArrayLength(out))) {
            throw_java(env, "java/lang/IllegalStateException", "выходной буфер мал: нужно " + std::to_string(total));
          } else {
            env->SetFloatArrayRegion(out, 0, static_cast<jsize>(total), data);
            result = static_cast<jint>(total);
          }
        }
        g_api->ReleaseTensorTypeAndShapeInfo(info);
      }
    }
  }
  if (output != nullptr) g_api->ReleaseValue(output);
  for (OrtValue* v : inputs) g_api->ReleaseValue(v);
  g_api->ReleaseMemoryInfo(mem);
  return result;
}

JNIEXPORT void JNICALL Java_ru_neurosteno_embed_OrtNative_destroy(JNIEnv*, jclass, jlong handle) {
  auto* h = reinterpret_cast<Handle*>(handle);
  if (h == nullptr) return;
  if (h->session != nullptr) g_api->ReleaseSession(h->session);
  delete h;
}

}  // extern "C"
