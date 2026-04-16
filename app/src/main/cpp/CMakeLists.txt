cmake_minimum_required(VERSION 3.22.1)
project(whisper_android)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp sources not found at ${WHISPER_DIR}. Run ./setup.sh first.")
endif()

# GGML's CMake auto-probe runs try_compile with host flags on NDK cross-builds and
# errors out ("Failed to get ARM features") leaving us with a scalar fallback —
# which is ~10x slower. Skip the probe and force the ARM extensions we know the
# S23 Ultra (Snapdragon 8 Gen 2, Cortex-X3) supports.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_compile_options(-march=armv8.2-a+dotprod+fp16)
add_compile_definitions(GGML_USE_CPU)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

target_link_libraries(whisper_jni
    whisper
    log
    android
)

target_compile_options(whisper_jni PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)
