Заголовки C API ONNX Runtime 1.28.0 (`onnxruntime-android-1.28.0.aar/headers`), MIT License, (c) Microsoft Corporation.
Используются только для компиляции `embed_jni.cpp`; библиотека (`libonnxruntime.so`) берётся во время работы та, что идёт в составе
sherpa-onnx (одна копия ORT на приложение), через `dlopen` + `dlsym("OrtGetApiBase")`.
