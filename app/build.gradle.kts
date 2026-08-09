plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
}

android {
    namespace = "io.androllm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.androllm.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "debug.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }
    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,NOTICE}"
        }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:navigation"))
    implementation(project(":core:models"))
    implementation(project(":core:network"))
    implementation(project(":core:cloud"))
    implementation(project(":core:memory"))
    implementation(project(":core:voice"))
    implementation(project(":core:utils"))
    
    // Feature modules
    implementation(project(":feature:home"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:models"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:splash"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:prompts"))
    implementation(project(":feature:developer"))
    implementation(project(":feature:cloud"))
    implementation(project(":feature:voice"))
    
    // Engine & Docs
    api(project(":engine"))
    implementation(project(":docs"))
    
    // Compose BOM & Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.compose.runtime)
    implementation(libs.compose.runtime.livedata)
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.core.ktx)
    
    // Navigation
    implementation(libs.navigation.compose)
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.4")
    implementation(libs.hilt.navigation.compose)
    
    // Hilt
    implementation(libs.hilt.android)
    ksp("com.google.dagger:hilt-android-compiler:2.57.1")
    implementation(libs.androidx.hilt)
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    
    // DataStore
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.core)
    
    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    
    // Serialization
    implementation(libs.serialization.core)
    
    // Networking
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // Firebase & Auth (BoM 34.x — the -ktx artifacts were removed, Kotlin
    // extensions now ship in the main artifacts)
    // NOTE: 34.12.0 (firebase-auth 24.0.1) is the newest BoM compatible with
    // this project's Kotlin 2.1.20 toolchain. BoM 34.13.0+ ships firebase-auth
    // 24.1.0+, compiled with Kotlin metadata 2.3.0, which KSP 2.1.20 cannot read
    // (build error). Upgrade Kotlin to 2.3 before bumping this.
    implementation(platform("com.google.firebase:firebase-bom:34.12.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Credential Manager — the officially required SDK for Google Sign-In
    // (Firebase docs: firebase.google.com/docs/auth/android/google-signin)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Android 12+ system splash with pre-12 backport
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Image Loading
    implementation(libs.coil.compose)
    
    // Logging
    implementation(libs.timber)
    
    // Permissions
    implementation(libs.accompanist.permissions)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.coroutines.test)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-tooling-data:1.7.2")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

// ── Voice models (sherpa-onnx) ───────────────────────────────────────────────
// The wake word / ASR / TTS ONNX models are bundled into the APK. They are
// large, so they live in app/src/main/assets/voice/ (gitignored) and are
// fetched by this task on machines where they are missing. The task is wired
// into preBuild so a fresh clone still produces a complete APK.
val voiceAssetsDir = file("src/main/assets/voice")
val voiceKwsDir = file("$voiceAssetsDir/kws")
val voiceAsrDir = file("$voiceAssetsDir/asr")
val voiceTtsDir = file("$voiceAssetsDir/tts")

tasks.register("downloadVoiceModels") {
    group = "voice"
    description = "Downloads + extracts the bundled sherpa-onnx voice models (wake word, ASR, TTS)"
    onlyIf { !voiceAssetsDir.exists() }

    doLast {
        val asrBase = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"
        val asrFiles = mapOf(
            "encoder.onnx" to "encoder-epoch-99-avg-1.int8.onnx",
            "decoder.onnx" to "decoder-epoch-99-avg-1.int8.onnx",
            "joiner.onnx" to "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt" to "tokens.txt"
        )
        val kwsTarball = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2"
        val ttsTarball = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-ljs.tar.bz2"
        val tmp = buildDir.resolve("voice-models")
        tmp.mkdirs()
        voiceKwsDir.mkdirs()
        voiceAsrDir.mkdirs()
        voiceTtsDir.mkdirs()

        fun download(url: String, target: File) {
            if (target.exists() && target.length() > 1000) return
            logger.lifecycle("downloadVoiceModels: fetching ${url.substringAfterLast('/')}")
            exec { commandLine("curl", "-sL", "--retry", "3", "-o", target.absolutePath, url) }
        }

        // Streaming ASR — int8 files only (smallest complete English model).
        asrFiles.forEach { (name, remote) ->
            val target = File(voiceAsrDir, name)
            download("$asrBase/$remote", target)
        }

        // KWS + TTS — release tarballs, extract the files we need.
        val kwsTar = File(tmp, "kws.tar.bz2")
        download(kwsTarball, kwsTar)
        exec { commandLine("tar", "-xjf", kwsTar.absolutePath, "-C", tmp.absolutePath) }
        val kwsDir = File(tmp, "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20")
        fun copyFrom(src: File, target: File, vararg names: String) {
            names.forEach { n ->
                val f = File(src, n)
                if (f.exists()) f.copyTo(File(target, f.name), overwrite = true)
            }
        }
        copyFrom(kwsDir, voiceKwsDir,
            "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
            "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            "tokens.txt")
        // Normalize to the names the app code references.
        File(voiceKwsDir, "encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx").renameTo(File(voiceKwsDir, "encoder.onnx"))
        File(voiceKwsDir, "decoder-epoch-13-avg-2-chunk-16-left-64.onnx").renameTo(File(voiceKwsDir, "decoder.onnx"))
        File(voiceKwsDir, "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx").renameTo(File(voiceKwsDir, "joiner.onnx"))
        // The zh-en KWS model uses ARPABET phoneme tokens: ship the keywords
        // already tokenized ("HEY ANDROID" / "OKAY ANDROID" -> phones + @name).
        // Several pronunciation variants per phrase so the model has a fair
        // chance of matching live-mic speech (the decoder emits whichever
        // phone sequence the user actually utters).
        File(voiceKwsDir, "keywords.txt").writeText(
            "HH EY1 AE1 N D R OY2 D @HEY_ANDROID\n" +
                "HH EY1 AH0 N D R OY2 D @HEY_ANDROID\n" +
                "HH EY1 AE1 N D R OY1 D @HEY_ANDROID\n" +
                "HH EY1 AH0 N D R OY1 D @HEY_ANDROID\n" +
                "HH EY1 AE1 N D R IY1 D @HEY_ANDROID\n" +
                "OW2 K EY1 AE1 N D R OY2 D @OKAY_ANDROID\n" +
                "OW2 K EY1 AH0 N D R OY2 D @OKAY_ANDROID\n" +
                "OW2 K EY1 AE1 N D R OY1 D @OKAY_ANDROID\n" +
                "OW0 K EY1 AE1 N D R OY2 D @OKAY_ANDROID\n" +
                "OW0 K EY1 AH0 N D R OY2 D @OKAY_ANDROID\n"
        )

        val ttsTar = File(tmp, "tts.tar.bz2")
        download(ttsTarball, ttsTar)
        exec { commandLine("tar", "-xjf", ttsTar.absolutePath, "-C", tmp.absolutePath) }
        copyFrom(File(tmp, "vits-ljs"), voiceTtsDir, "vits-ljs.onnx", "tokens.txt", "lexicon.txt")
        File(voiceTtsDir, "vits-ljs.onnx").renameTo(File(voiceTtsDir, "model.onnx"))

        logger.lifecycle("downloadVoiceModels: done (${voiceAssetsDir.walkTopDown().filter { it.isFile }.count()} files)")
    }
}

tasks.named("preBuild") {
    dependsOn("downloadVoiceModels")
}
