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

void throw_java(JNIEnv* env, const char* cls, const std::string& msg) {
  jclass c = env->FindClass(cls);
  if (c != nullptr) env->ThrowNew(c, msg.c_str());
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
  const char* p = env->GetStringUTFChars(path, nullptr);
  std::lock_guard<std::mutex> lock(g_mu);
  g_lib_path = p;
  env->ReleaseStringUTFChars(path, p);
}

JNIEXPORT jstring JNICALL Java_ru_neurosteno_embed_OrtNative_version(JNIEnv* env, jclass) {
  if (!init_api(env)) return nullptr;
  return env->NewStringUTF(g_version.c_str());
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
  const char* path = env->GetStringUTFChars(model, nullptr);
  OrtSession* session = nullptr;
  OrtStatus* st = g_api->CreateSession(g_env, path, opts, &session);
  env->ReleaseStringUTFChars(model, path);
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
  jobjectArray arr = env->NewObjectArray(static_cast<jsize>(h->input_names.size()), sc, nullptr);
  for (size_t i = 0; i < h->input_names.size(); i++) env->SetObjectArrayElement(arr, static_cast<jsize>(i), env->NewStringUTF(h->input_names[i].c_str()));
  return arr;
}

// Вход: input_ids / attention_mask [/ token_type_ids] int64 формы [batch, seq]; выход: первый выход модели (float32, [batch, seq, hidden])
// копируется в out. Возвращает число скопированных float (batch * seq * hidden).
JNIEXPORT jint JNICALL Java_ru_neurosteno_embed_OrtNative_run(JNIEnv* env, jclass, jlong handle, jlongArray ids, jlongArray mask, jlongArray types,
                                                              jint batch, jint seq, jfloatArray out) {
  auto* h = reinterpret_cast<Handle*>(handle);
  const size_t count = static_cast<size_t>(batch) * static_cast<size_t>(seq);
  std::vector<int64_t> v_ids(count), v_mask(count), v_types;
  env->GetLongArrayRegion(ids, 0, static_cast<jsize>(count), reinterpret_cast<jlong*>(v_ids.data()));
  env->GetLongArrayRegion(mask, 0, static_cast<jsize>(count), reinterpret_cast<jlong*>(v_mask.data()));
  if (types != nullptr) {
    v_types.resize(count);
    env->GetLongArrayRegion(types, 0, static_cast<jsize>(count), reinterpret_cast<jlong*>(v_types.data()));
  }
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
