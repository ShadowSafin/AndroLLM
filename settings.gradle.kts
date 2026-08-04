pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.13.2" apply false
        id("com.android.library") version "8.13.2" apply false
        id("org.jetbrains.kotlin.android") version "2.0.20" apply false
        id("com.google.dagger.hilt.android") version "2.51.1" apply false
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
        id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
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
include(":core:utils")
include(":feature:home")
include(":feature:chat")
include(":feature:models")
include(":feature:settings")
include(":feature:splash")
include(":engine")
include(":docs")