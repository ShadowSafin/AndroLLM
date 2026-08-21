plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.androllm.core.memory"
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
        // android.util.Log and friends return default values in JVM unit tests
        // instead of throwing "not mocked", so repositories can log timing
        // telemetry (AndroLLM.Perf) without breaking tests.
        unitTests.isReturnDefaultValues = true
        targetSdk = 36
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,NOTICE}"
        }
    }
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
    lint {
        targetSdk = 36
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:models"))
    implementation(project(":core:cloud"))
    implementation(project(":engine"))

    // Room 2.6.1 matches core/database — the version proven with this project's
    // Kotlin 2.1.20 + KSP1 toolchain. Room 2.8.x pulls a kotlinx-serialization
    // that clashes with the project's pinned 1.6.3 on the KSP classpath.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    implementation(libs.serialization.core)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
}
