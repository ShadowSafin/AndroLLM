cmake_minimum_required(VERSION 3.22.1)
project(androllm_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# AndroLLM build options
# ---------------------------------------------------------------------------
#
# Vulkan GPU backend: FORCE-ENABLED for Snapdragon 8 Gen 5 / Adreno devices.
# The engine will automatically fall back to CPU if Vulkan initialization fails
# at runtime (see native_api.cpp vulkan_available() check).
#
# To disable Vulkan, pass: -DANDROLLM_VULKAN=OFF
# To specify a Vulkan SDK: -DVULKAN_SDK=<path-to-SDK>
#
set(ANDROLLM_VULKAN ON CACHE BOOL "Enable the ggml Vulkan GPU backend" FORCE)

# llama.cpp build options
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

# Vulkan is a required build dependency, never an optional CPU-only variant.
# Android supplies the target Vulkan loader via the NDK; the host Vulkan SDK is
# only used for glslc while compiling ggml's compute shaders.  Failing the
# build here is intentional: silently producing a CPU-only APK is incorrect.
find_program(ANDROLLM_GLSLC_EXECUTABLE NAMES glslc glslc.exe
    HINTS "$ENV{VULKAN_SDK}/Bin" "$ENV{VULKAN_SDK}/bin"
    NO_CMAKE_FIND_ROOT_PATH)
if(NOT ANDROLLM_GLSLC_EXECUTABLE)
    message(FATAL_ERROR
        "Vulkan is required but the host shader compiler (glslc) was not found. "
        "Install the Vulkan SDK and set VULKAN_SDK before building AndroLLM.")
endif()

# Do not set VULKAN_SDK as a CMake prefix: during an Android cross-build that
# can make FindVulkan pick a Windows host library.  The NDK remains the source
# of headers and libvulkan.so; only glslc comes from the host SDK.
set(Vulkan_GLSLC_EXECUTABLE "${ANDROLLM_GLSLC_EXECUTABLE}" CACHE FILEPATH
    "Host glslc used to compile ggml Vulkan shaders" FORCE)

# ggml-vulkan uses SPIRV-Headers for the host-side shader generator.  Point
# directly at its package configuration without exposing the host SDK's
# Windows Vulkan library to the Android cross-linker.
get_filename_component(ANDROLLM_VULKAN_BIN_DIR "${ANDROLLM_GLSLC_EXECUTABLE}" DIRECTORY)
get_filename_component(ANDROLLM_VULKAN_SDK_DIR "${ANDROLLM_VULKAN_BIN_DIR}" DIRECTORY)
if(EXISTS "${ANDROLLM_VULKAN_SDK_DIR}/Lib/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
    set(SPIRV-Headers_DIR "${ANDROLLM_VULKAN_SDK_DIR}/Lib/cmake/SPIRV-Headers" CACHE PATH
        "Host SPIRV-Headers package used by ggml shader generation" FORCE)
elseif(EXISTS "${ANDROLLM_VULKAN_SDK_DIR}/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
    set(SPIRV-Headers_DIR "${ANDROLLM_VULKAN_SDK_DIR}/share/cmake/SPIRV-Headers" CACHE PATH
        "Host SPIRV-Headers package used by ggml shader generation" FORCE)
else()
    message(FATAL_ERROR "Vulkan SDK is missing the required SPIRV-Headers CMake package")
endif()
set(GGML_VULKAN ON CACHE BOOL "ggml: use Vulkan" FORCE)
message(STATUS "androllm: Vulkan backend required; host glslc=${Vulkan_GLSLC_EXECUTABLE}")

# llama.cpp builds its shader generator as a host executable. Its generated
# fallback toolchain does not inherit Android Gradle Plugin's bundled Ninja,
# so provide a complete host toolchain explicitly for cross compilation.
if(CMAKE_CROSSCOMPILING)
    # NDK clang lands in CMAKE_SYSTEM_PROGRAM_PATH and is not a usable host
    # compiler, so search only the process PATH for a native toolchain.
    # The cached result must be dropped, otherwise a stale NDK clang found on
    # an earlier configure keeps winning over the current environment.
    unset(ANDROLLM_HOST_C_COMPILER CACHE)
    unset(ANDROLLM_HOST_CXX_COMPILER CACHE)
    # gcc/g++ are tried first because AGP prepends the NDK toolchain bin dir
    # to PATH, which would otherwise win with plain "clang" (NDK clang cannot
    # link host binaries).
    find_program(ANDROLLM_HOST_C_COMPILER NAMES gcc clang
        HINTS "$ENV{PATH}" NO_CMAKE_SYSTEM_PATH)
    find_program(ANDROLLM_HOST_CXX_COMPILER NAMES g++ clang++
        HINTS "$ENV{PATH}" NO_CMAKE_SYSTEM_PATH)
    if(NOT ANDROLLM_HOST_C_COMPILER OR NOT ANDROLLM_HOST_CXX_COMPILER)
        message(FATAL_ERROR "Vulkan shader generator requires a host C/C++ compiler")
    endif()
    set(ANDROLLM_HOST_NINJA "${CMAKE_MAKE_PROGRAM}")
    if(NOT EXISTS "${ANDROLLM_HOST_NINJA}")
        find_program(ANDROLLM_HOST_NINJA NAMES ninja ninja.exe NO_CMAKE_FIND_ROOT_PATH)
    endif()
    if(NOT ANDROLLM_HOST_NINJA)
        message(FATAL_ERROR "Vulkan shader generator requires Ninja on the host")
    endif()
    configure_file(
        "${CMAKE_CURRENT_SOURCE_DIR}/cmake/host-vulkan-shaders-toolchain.cmake.in"
        "${CMAKE_BINARY_DIR}/androllm-host-vulkan-shaders-toolchain.cmake"
        @ONLY
    )
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
        "${CMAKE_BINARY_DIR}/androllm-host-vulkan-shaders-toolchain.cmake"
        CACHE FILEPATH "Host toolchain for the ggml Vulkan shader generator" FORCE)
endif()

add_subdirectory(llama.cpp)

# The Android NDK provides the C Vulkan headers and target loader, while
# llama.cpp's Vulkan backend also includes the header-only C++ bindings.
# These come from the host SDK and are safe to use when cross-compiling.
target_include_directories(ggml-vulkan PRIVATE
    "${ANDROLLM_VULKAN_SDK_DIR}/Include"
)

# llama.cpp's jinja chat template engine is now built as part of llama-common
# (LLAMA_BUILD_COMMON=ON above). We link against llama-common instead of
# compiling jinja sources manually.

add_library(androllm_llama SHARED native_api.cpp)

target_compile_options(androllm_llama PRIVATE
    -O3
    -fno-finite-math-only
    -funroll-loops
)

target_include_directories(androllm_llama PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    llama.cpp/common
    llama.cpp/vendor
)

# Make the backend selection visible to the JNI bridge (native_api.cpp gates
# the ggml-vulkan.h include behind GGML_USE_VULKAN).
target_compile_definitions(androllm_llama PRIVATE GGML_USE_VULKAN=1)

target_link_libraries(androllm_llama PRIVATE
    llama
    llama-common
    ggml
    ggml-base
    ggml-cpu
    log
    android
    vulkan
)

target_link_libraries(androllm_llama PRIVATE ggml-vulkan)
