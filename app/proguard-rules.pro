# AndroLLM ProGuard Rules

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
-keep class * extends dagger.hilt.android.HiltViewModel { *; }

# Keep Room database
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * implements androidx.room.TypeConverter { *; }

# Keep Kotlin serialization
-keep class kotlinx.serialization.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }

# Keep Coil
-keep class coil.** { *; }

# Keep Timber
-keep class com.jakewharton.timber.** { *; }

# Keep Material3
-keep class androidx.compose.material3.** { *; }

# Keep Navigation Compose
-keep class androidx.navigation.compose.** { *; }

# Keep Compose runtime
-keep class androidx.compose.runtime.** { *; }

# Keep UI state classes
-keep class io.androllm.core.common.UiState** { *; }
-keep class io.androllm.core.common.Result** { *; }

# Keep Models
-keep class io.androllm.core.models.** { *; }

# Keep ViewModels
-keep class * extends androidx.lifecycle.ViewModel { *; }

# Keep Hilt entry points
-keep class * extends dagger.hilt.EntryPoint { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep DataStore
-keep class androidx.datastore.** { *; }

# Keep application class
-keep class io.androllm.app.AndroLLMApplication { *; }

# Keep MainActivity
-keep class io.androllm.app.MainActivity { *; }

# Keep generated Hilt components
-keep class dagger.hilt.internal.** { *; }

# Keep Engine classes (prevent stripping by R8)
-keep class io.androllm.engine.** { *; }
-keep class io.androllm.engine.llama.LlamaCppEngine { *; }
-keep class io.androllm.engine.api.** { *; }
-keep class io.androllm.engine.di.EngineModule { *; }
-keep class io.androllm.engine.models.** { *; }

# Suppress warnings
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-dontwarn io.ktor.**
-dontwarn coil.**