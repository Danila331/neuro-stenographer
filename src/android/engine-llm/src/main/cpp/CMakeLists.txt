cmake_minimum_required(VERSION 3.22.1)
project(neurosteno_llm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Исходники llama.cpp фиксированного тега скачивает scripts/fetch_llama_cpp.sh (в git их нет)
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp" CACHE PATH "Каталог исходников llama.cpp")
if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Нет исходников llama.cpp в ${LLAMA_DIR}. Выполните src/android/scripts/fetch_llama_cpp.sh")
endif()

# --- настройки llama.cpp/ggml: статическая сборка, только CPU, без лишнего ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # собственный пул потоков ggml; без зависимости от libomp
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)      # GPU-бэкенды выключены: по умолчанию только CPU (BACKLOG B11)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)

# arm64: базовая архитектура задаётся снаружи (Gradle: -Pneurosteno.llm.arm_arch=...), по умолчанию armv8.2-a+dotprod
if(ANDROID AND NOT GGML_CPU_ARM_ARCH)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp-build" EXCLUDE_FROM_ALL)

add_library(neurosteno_llm SHARED llm_jni.cpp)
target_link_libraries(neurosteno_llm PRIVATE llama ggml)

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(neurosteno_llm PRIVATE ${log-lib})
    # 16 КБ-выравнивание сегментов (Android 15+, targetSdk 35)
    target_link_options(neurosteno_llm PRIVATE -Wl,-z,max-page-size=16384)
else()
    # хостовая сборка для проверки (scripts/build_host_jni.sh): заголовки JNI из JDK
    if(NOT DEFINED ENV{JAVA_HOME})
        message(FATAL_ERROR "Для хостовой сборки задайте JAVA_HOME (source scripts/env.sh)")
    endif()
    target_include_directories(neurosteno_llm PRIVATE "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/darwin" "$ENV{JAVA_HOME}/include/linux")
endif()
