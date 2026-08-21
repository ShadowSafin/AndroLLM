plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.androllm.core.mcp"
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
        compose = false
    }
    testOptions {
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
    implementation(project(":core:tools"))
    implementation(project(":core:common"))
    implementation(project(":core:runtime"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.core)
    implementation(libs.timber)

    // Model Context Protocol client (Streamable HTTP transport)
    implementation(libs.mcp.kotlin.sdk.client)
    // The MCP SDK uses Ktor's SSE client plugin; ktor-client-plugins bundles it
    // in Ktor 3.x. OkHttp is the engine already used across the app.
    implementation(libs.ktor.client.plugins)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
}
