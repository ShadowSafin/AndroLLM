pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.13.2" apply false
        id("com.android.library") version "8.13.2" apply false
        id("org.jetbrains.kotlin.android") version "2.1.20" apply false
        id("com.google.dagger.hilt.android") version "2.57.1" apply false
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
        id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
        id("com.google.devtools.ksp") version "2.1.20-1.0.32" apply false
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
        // sherpa-onnx Android AAR (keyword spotting + streaming ASR + offline TTS)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AndroLLM"

include(":app")
include(":core:common")
include(":core:ui")
include(":core:database")
include(":core:datastore")
include(":core:navigation")
include(":core:models")
include(":core:network")
include(":core:cloud")
include(":core:utils")
include(":core:telemetry")
include(":core:memory")
include(":core:permissions")
include(":core:voice")
include(":core:tools")
include(":core:accessibility")
include(":core:mcp")
include(":core:runtime")
include(":feature:voice")
include(":feature:home")
include(":feature:chat")
include(":feature:models")
include(":feature:settings")
include(":feature:splash")
include(":feature:onboarding")
include(":feature:setup")
include(":feature:profile")
include(":feature:prompts")
include(":feature:developer")
include(":feature:cloud")
include(":engine")
include(":documentation")
include(":whisper")