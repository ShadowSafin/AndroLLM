cmake_minimum_required(VERSION 3.22.1)
project(androllm_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------
# AndroLLM native engine
#
# Builds the vendored upstream llama.cpp (engine/src/main/cpp/llama.cpp) plus
# the AndroLLM JNI bridge into a single libandrollm_llama.so.
#
# The vendored tree is stock upstream: it is never patched, so it can be
# replaced wholesale with a newer snapshot. All AndroLLM-specific build
# configuration lives here.
# ---------------------------------------------------------------------------

set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "Vendored llama.cpp is missing at ${LLAMA_SRC}. "
        "Restore it before building the engine.")
endif()

# Everything links into one shared object, so there are no ggml backend .so
# files to locate at runtime.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD     OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS     OFF CACHE BOOL "" FORCE)

# OpenMP is not usable from the NDK cross-build (see upstream docs/android.md),
# and llamafile has no Android support.
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # KleidiAI supplies the Arm i8mm/dotprod microkernels used on Snapdragon.
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

# ---------------------------------------------------------------------------
# Vulkan
#
# Vulkan is required at build time. The device may still fall back to CPU at
# runtime if no usable Vulkan driver is present - that is decided in
# native_api.cpp, not here. Silently producing a CPU-only APK is not a
# supported outcome, so a missing host SDK is a hard error.
# ---------------------------------------------------------------------------
option(ANDROLLM_VULKAN "Build the ggml Vulkan backend" ON)

if(ANDROLLM_VULKAN)
    # glslc compiles the ggml compute shaders and must run on the build host,
    # so it is taken from the host Vulkan SDK rather than the NDK sysroot.
    find_program(ANDROLLM_GLSLC_EXECUTABLE
        NAMES glslc glslc.exe
        HINTS "$ENV{VULKAN_SDK}/Bin" "$ENV{VULKAN_SDK}/bin"
        NO_CMAKE_FIND_ROOT_PATH)
    if(NOT ANDROLLM_GLSLC_EXECUTABLE)
        message(FATAL_ERROR
            "Vulkan is enabled but the host shader compiler (glslc) was not found. "
            "Install the Vulkan SDK, set VULKAN_SDK, or configure with -DANDROLLM_VULKAN=OFF.")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${ANDROLLM_GLSLC_EXECUTABLE}" CACHE FILEPATH
        "Host glslc used to compile ggml Vulkan shaders" FORCE)

    get_filename_component(ANDROLLM_VULKAN_BIN_DIR "${ANDROLLM_GLSLC_EXECUTABLE}" DIRECTORY)
    get_filename_component(ANDROLLM_VULKAN_SDK_DIR "${ANDROLLM_VULKAN_BIN_DIR}" DIRECTORY)

    # ggml-vulkan needs the SPIRV-Headers CMake package for its host-side shader
    # generator. Point at it directly instead of putting the host SDK on
    # CMAKE_PREFIX_PATH, which would expose a Windows Vulkan import library to
    # the Android cross-linker.
    foreach(candidate
            "${ANDROLLM_VULKAN_SDK_DIR}/Lib/cmake/SPIRV-Headers"
            "${ANDROLLM_VULKAN_SDK_DIR}/lib/cmake/SPIRV-Headers"
            "${ANDROLLM_VULKAN_SDK_DIR}/share/cmake/SPIRV-Headers")
        if(EXISTS "${candidate}/SPIRV-HeadersConfig.cmake")
            set(SPIRV-Headers_DIR "${candidate}" CACHE PATH "" FORCE)
            break()
        endif()
    endforeach()
    if(NOT SPIRV-Headers_DIR)
        message(FATAL_ERROR
            "The Vulkan SDK at ${ANDROLLM_VULKAN_SDK_DIR} has no SPIRV-Headers CMake package.")
    endif()

    # A cross build makes FindVulkan pick the NDK sysroot headers, which lack
    # the C++ bindings (vulkan.hpp) that ggml-vulkan requires. Point it at the
    # host SDK explicitly before the vendored tree is configured.
    set(Vulkan_INCLUDE_DIR "${ANDROLLM_VULKAN_SDK_DIR}/Include" CACHE FILEPATH
        "Vulkan SDK include directory (host)" FORCE)

    # vulkan-shaders-gen is an ExternalProject that must run on the host.
    # AGP puts the NDK toolchain first on PATH, so a plain search for "clang"
    # finds a cross compiler that cannot produce host executables. gcc/g++ are
    # tried first because the NDK ships no binaries under those names, and the
    # cache entries are dropped each configure so a stale hit cannot win.
    if(CMAKE_CROSSCOMPILING)
        unset(ANDROLLM_HOST_C_COMPILER CACHE)
        unset(ANDROLLM_HOST_CXX_COMPILER CACHE)
        find_program(ANDROLLM_HOST_C_COMPILER   NAMES gcc cc clang cl  NO_CMAKE_FIND_ROOT_PATH)
        find_program(ANDROLLM_HOST_CXX_COMPILER NAMES g++ c++ clang++ cl NO_CMAKE_FIND_ROOT_PATH)
        if(NOT ANDROLLM_HOST_C_COMPILER OR NOT ANDROLLM_HOST_CXX_COMPILER)
            message(FATAL_ERROR
                "No host C/C++ compiler found for the Vulkan shader generator. "
                "Install a host toolchain (MSVC, GCC or LLVM-MinGW) and put it on PATH.")
        endif()

        # The generated toolchain also pins CMAKE_MAKE_PROGRAM: the shader
        # generator does not inherit AGP's bundled Ninja, and CMake cannot find
        # one on its own in a bare Android Studio install.
        set(ANDROLLM_HOST_NINJA "${CMAKE_MAKE_PROGRAM}")
        configure_file(
            "${CMAKE_CURRENT_SOURCE_DIR}/cmake/host-vulkan-shaders-toolchain.cmake.in"
            "${CMAKE_BINARY_DIR}/androllm-host-toolchain.cmake"
            @ONLY)
        set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
            "${CMAKE_BINARY_DIR}/androllm-host-toolchain.cmake" CACHE FILEPATH "" FORCE)
        message(STATUS "androllm: host shader toolchain -> ${ANDROLLM_HOST_CXX_COMPILER}")
    endif()

    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    message(STATUS "androllm: Vulkan enabled, host glslc=${Vulkan_GLSLC_EXECUTABLE}")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    message(WARNING "androllm: building without the Vulkan backend (CPU only)")
endif()

add_subdirectory("${LLAMA_SRC}" llama-build EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
add_library(androllm_llama SHARED native_api.cpp)

target_compile_options(androllm_llama PRIVATE -O3 -fno-finite-math-only)

if(ANDROLLM_VULKAN)
    target_compile_definitions(androllm_llama PRIVATE ANDROLLM_VULKAN=1)
endif()

target_include_directories(androllm_llama PRIVATE
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/vendor")

target_link_libraries(androllm_llama PRIVATE
    llama
    llama-common
    android
    log)
