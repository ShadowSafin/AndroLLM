plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.androllm.core.whisper"
    compileSdk = 36
    // Pinned native toolchain — do not bump without validating arm64-v8a
    // configure+build on a fresh Windows install. CMake 3.22.1 ships in the
    // Android SDK and requires the MSVC runtime (see CMakeLists.txt header):
    //   winget install --id Microsoft.VCRedist.2015+.x64 --silent
    // Gradle itself requires JDK 17 to run (PATH `java` 1.8 is NOT enough).
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 28

        // Only arm64-v8a by default (Snapdragon/Adreno target). Add x86_64 via
        // -PandrollmAbis=arm64-v8a,x86_64 to also run on emulators.
        val abis = (project.findProperty("androllmAbis") as String? ?: "arm64-v8a")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        ndk {
            abiFilters += abis
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    // ggml's OpenMP backend needs the NDK's libomp.so which AGP
                    // does not package for AARs; keep it off (ggml has its own
                    // CPU threading).
                    "-DGGML_OPENMP=OFF",
                    // Hermetic Android build: never build ggml tests/examples,
                    // never use host ccache, never build all CPU variants
                    // (requires BACKEND_DL). Mirrored as FORCE defaults in
                    // src/main/jni/whisper/CMakeLists.txt.
                    "-DGGML_BUILD_TESTS=OFF",
                    "-DGGML_BUILD_EXAMPLES=OFF",
                    "-DGGML_CCACHE=OFF",
                    "-DGGML_CPU_ALL_VARIANTS=OFF"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = false
        buildConfig = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            // Must match the SDK-bundled CMake under
            // <sdk>/cmake/<version>/bin/cmake.exe (see CMakeLists.txt header
            // for the VCRedist prerequisite).
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        targetSdk = 36
    }
    testOptions {
        targetSdk = 36
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
}
