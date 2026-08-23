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
    compileSdk = 36

    // ── Release signing ────────────────────────────────────────────────────
    // To sign release builds with your release keystore, set these in
    // ~/.gradle/gradle.properties (DO NOT commit passwords to git):
    //   RELEASE_STORE_FILE=/path/to/Mainkeystore
    //   RELEASE_STORE_PASSWORD=your-password
    //   RELEASE_KEY_ALIAS=key0
    //   RELEASE_KEY_PASSWORD=your-password
    //
    // IMPORTANT: The SHA-256 fingerprint of the keystore used here MUST be
    // registered in Firebase Console → Project Settings → Your Android app
    // → SHA certificate fingerprints, otherwise Google/GitHub sign-in will
    // fail with "Internal Firebase error". Also register the Play App
    // Signing SHA-256 from Google Play Console → Setup → App Integrity.
    val releaseKeystorePath = System.getenv("RELEASE_STORE_FILE")
        ?: project.findProperty("RELEASE_STORE_FILE")?.toString()
    if (releaseKeystorePath != null && file(releaseKeystorePath).exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    ?: project.findProperty("RELEASE_STORE_PASSWORD")?.toString()
                    ?: ""
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    ?: project.findProperty("RELEASE_KEY_ALIAS")?.toString()
                    ?: "key0"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                    ?: project.findProperty("RELEASE_KEY_PASSWORD")?.toString()
                    ?: ""
            }
        }
    }

    defaultConfig {
        applicationId = "io.androllm.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
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
        jniLibs {
            // NPU (Qualcomm QNN) deployment requirement: LiteRT's dispatch
            // delegate locates its vendor libraries via a filesystem readdir
            // of applicationInfo.nativeLibraryDir. Without legacy packaging
            // that dir is empty (libs stay compressed inside the APK) and the
            // NPU backend can never initialize. See documentation/ai/
            // acceleration.md.
            useLegacyPackaging = true

            // Oryon-SoC workaround (Snapdragon 8 Elite / 8s Gen 4, SME): ONNX
            // Runtime 1.27.0 (bundled in the sherpa-onnx AAR) miscomputes
            // streaming-zipformer encoders there -> silent KWS/ASR failure
            // (k2-fsa/sherpa-onnx#3845). We ship ORT 1.28.0 plus patched
            // sherpa libs from app/src/main/jniLibs/: the sherpa JNI's only
            // versioned ORT reference (OrtGetApiBase@VERS_1.27.0) is patched
            // to be unversioned so it binds to 1.28's default-versioned
            // symbol. See tools/patch_ver.py. Remove once sherpa bumps ORT.
            pickFirsts += "**/libonnxruntime.so"
            pickFirsts += "**/libsherpa-onnx-jni.so"
            pickFirsts += "**/libsherpa-onnx-c-api.so"
            pickFirsts += "**/libsherpa-onnx-cxx-api.so"
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
    implementation(project(":core:tools"))
    implementation(project(":core:accessibility"))
    implementation(project(":core:mcp"))
    // Direct dependency (not just via feature:setup) so the app's Hilt
    // aggregation deterministically sees PermissionModule; a missed module
    // silently injects an empty handler set (blank Permissions & Access).
    implementation(project(":core:permissions"))
    implementation(project(":core:utils"))
    
    // Feature modules
    implementation(project(":feature:home"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:models"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:splash"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:prompts"))
    implementation(project(":feature:developer"))
    implementation(project(":feature:cloud"))
    implementation(project(":feature:voice"))

    // Engine & Docs
    api(project(":engine"))
    implementation(project(":documentation"))
    
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
val voiceTtsDir = file("$voiceAssetsDir/tts")

tasks.register("downloadVoiceModels") {
    group = "voice"
    description = "Downloads + extracts the bundled sherpa-onnx voice models (wake word, TTS). Speech-to-text uses whisper.cpp, whose ggml models are downloaded in-app."
    onlyIf { !voiceAssetsDir.exists() }

    doLast {
        val kwsTarball = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20.tar.bz2"
        val ttsTarball = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-ljs.tar.bz2"
        val tmp = layout.buildDirectory.get().asFile.resolve("voice-models")
        tmp.mkdirs()
        voiceKwsDir.mkdirs()
        voiceTtsDir.mkdirs()

        fun download(url: String, target: File) {
            if (target.exists() && target.length() > 1000) return
            logger.lifecycle("downloadVoiceModels: fetching ${url.substringAfterLast('/')}")
            providers.exec { commandLine("curl", "-sL", "--retry", "3", "-o", target.absolutePath, url) }
        }

        // KWS + TTS — release tarballs, extract the files we need.
        val kwsTar = File(tmp, "kws.tar.bz2")
        download(kwsTarball, kwsTar)
        providers.exec { commandLine("tar", "-xjf", kwsTar.absolutePath, "-C", tmp.absolutePath) }
        val kwsDir = File(tmp, "sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20")
        fun copyFrom(src: File, target: File, vararg names: String) {
            names.forEach { n ->
                val f = File(src, n)
                if (f.exists()) f.copyTo(File(target, f.name), overwrite = true)
            }
        }
        // Int8 encoder/joiner + fp32 decoder (official pairing). NOTE: this
        // model's int8 encoder produced zero detections on Oryon SoCs
        // (Snapdragon 8 Elite/8s Gen 4) with the ONNX Runtime 1.27.0 bundled in
        // the sherpa-onnx AAR — ORT 1.27.0 miscomputes zipformer2 encoders
        // there (k2-fsa/sherpa-onnx#3845). The app overrides libonnxruntime.so
        // with 1.28.0 via jniLibs, which fixes it; keep that override in place.
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
        // already tokenized ("HEY ANDRO" / "OKAY ANDRO" / "ANDRO" /
        // "HEY ANDROID" / "OKAY ANDROID" -> phones + @name). Several
        // pronunciation variants per phrase so the model has a fair chance of
        // matching live-mic speech (the decoder emits whichever phone sequence
        // the user actually utters).
        //
        // IMPORTANT: sherpa-onnx keyword-file format is
        //   <tokens> @<name> [:boost] [#threshold]
        // where ':' is the boost score and '#' is the trigger threshold. Do
        // NOT repeat ':' (e.g. ":1.0 :0.0001" silently overwrites the boost
        // with the last value and the threshold is dropped). Omitting both is
        // fine: KeywordSpotterConfig.keywordsScore / keywordsThreshold apply.
        File(voiceKwsDir, "keywords.txt").writeText(
            "HH EY1 AE1 N D R OW0 @HEY_ANDRO\n" +
                "HH EY1 AE1 N D R OW1 @HEY_ANDRO\n" +
                "HH EY1 AE1 N D R OW2 @HEY_ANDRO\n" +
                "HH EY1 AE1 N D R OY2 @HEY_ANDRO\n" +
                "HH EY1 AE1 N D R OY1 @HEY_ANDRO\n" +
                "HH EY1 AH0 N D R OW0 @HEY_ANDRO\n" +
                "HH EY1 AH0 N D R OW1 @HEY_ANDRO\n" +
                "HH EY1 AE0 N D R OW0 @HEY_ANDRO\n" +
                "OW2 K EY1 AE1 N D R OW0 @OKAY_ANDRO\n" +
                "OW2 K EY1 AH0 N D R OW0 @OKAY_ANDRO\n" +
                "OW2 K EY1 AE1 N D R OW1 @OKAY_ANDRO\n" +
                "OW0 K EY1 AE1 N D R OW0 @OKAY_ANDRO\n" +
                "OW1 K EY1 AE1 N D R OW0 @OKAY_ANDRO\n" +
                "AE1 N D R OW0 @ANDRO\n" +
                "AH0 N D R OW0 @ANDRO\n" +
                "AE1 N D R OW1 @ANDRO\n" +
                "AE0 N D R OW0 @ANDRO\n" +
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
        providers.exec { commandLine("tar", "-xjf", ttsTar.absolutePath, "-C", tmp.absolutePath) }
        copyFrom(File(tmp, "vits-ljs"), voiceTtsDir, "vits-ljs.onnx", "tokens.txt", "lexicon.txt")
        File(voiceTtsDir, "vits-ljs.onnx").renameTo(File(voiceTtsDir, "model.onnx"))

        logger.lifecycle("downloadVoiceModels: done (${voiceAssetsDir.walkTopDown().filter { it.isFile }.count()} files)")
    }
}

tasks.named("preBuild") {
    dependsOn("downloadVoiceModels")
}
