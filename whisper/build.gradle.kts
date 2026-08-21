plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.androllm.core.whisper"
    compileSdk = 36
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
                    "-DGGML_OPENMP=OFF"
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
