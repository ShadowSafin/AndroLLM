plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.androllm.feature.coding"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
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
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // android.util.Log and friends return default values in JVM unit tests
        // instead of throwing "not mocked", so ViewModels can log freely.
        unitTests.isReturnDefaultValues = true
        targetSdk = 36
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,NOTICE}"
        }
    }
    lint {
        targetSdk = 36
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
    implementation(project(":core:models"))
    implementation(project(":core:utils"))
    implementation(project(":core:datastore"))
    implementation(project(":core:cloud"))
    implementation(project(":core:tools"))

    // SAF folder import (workspace selection from device storage).
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Real Linux base environment: the coding CLI runs inside a proot'd Debian
    // rootfs (see environment/proot). Commons Compress + tukaani XZ extract the
    // rootfs tarball on-device during first-use provisioning; OkHttp downloads
    // the tarball on first use (kept out of the APK to avoid a +90 MB asset).
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.9")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // DataStore for coding workspace + session persistence.
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.datastore:datastore-core:1.1.1")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("com.google.dagger:hilt-android:2.57.1")
    ksp("com.google.dagger:hilt-compiler:2.57.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.16")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("org.apache.commons:commons-compress:1.27.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// ── Coding-agent Linux base (Debian rootfs) ─────────────────────────────────
// The coding CLI runs inside a proot'd Debian userland (real npm/python/git/
// build tools). Unlike the old Alpine minirootfs asset, the Debian rootfs is
// ~90 MB compressed, so it is downloaded on first use by the app itself
// (DebianRootfsDownloader) and cached in app storage instead of bloating the
// APK. Nothing to restore at build time.
