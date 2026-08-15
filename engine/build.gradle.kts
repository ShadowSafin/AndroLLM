plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.androllm.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // The LiteRT-LM / LiteRT AARs (0.16.0 / 2.2.0) ship Kotlin 2.3.0
            // metadata while this project's toolchain is Kotlin 2.1.20. The
            // AAR's own POM pins kotlin-reflect 2.2.21, so its runtime needs
            // are satisfied by 2.2.x — the version check is the only blocker.
            // Bumping the whole project to Kotlin 2.3 is tracked separately.
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    buildFeatures {
        compose = false
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,NOTICE}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:models"))
    implementation(project(":core:runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.google.dagger:hilt-android:2.57.1")
    ksp("com.google.dagger:hilt-compiler:2.57.1")
    implementation("com.jakewharton.timber:timber:5.0.1")

    // LiteRT-LM: the official Kotlin runtime for on-device LLM inference
    // (chat/generation). Version 0.16.0 (2026-08-11) — see
    // documentation/LOCAL_LLM_ARCHITECTURE.md for the pin rationale.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")

    // Raw LiteRT runtime + CompiledModel API, used by the embedding pipeline
    // (memory search). The LiteRT-LM EmbeddingEngine is unreleased as of
    // 0.16.0, so embeddings run through the LiteRT CompiledModel API
    // directly (Accelerator.CPU/GPU/NPU).
    //
    // NOTE: litert-api 2.2.0 transitively requires androidx.lifecycle:
    // lifecycle-runtime:2.10.0 (only used by its remote ModelProvider/
    // ModelSelector helpers, which this project never calls). That version
    // drags lifecycle-runtime-compose to 2.10.0, which requires Compose
    // 1.9.0 — silently overriding the project BOM (2024.10.00 → Compose
    // 1.7.4) and breaking the app at runtime (NoSuchMethodError on FlowRow
    // in ModelWalletCard, compiled against 1.7.4). Excluding the unused
    // lifecycle dep keeps Compose on the project BOM.
    implementation("com.google.ai.edge.litert:litert:2.2.0") {
        exclude(group = "androidx.lifecycle", module = "lifecycle-runtime")
    }
    implementation("com.google.ai.edge.litert:litert-api:2.2.0") {
        exclude(group = "androidx.lifecycle", module = "lifecycle-runtime")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.16")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
