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

# ── Firebase Auth + Google Sign-In (CRITICAL for release builds) ────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.firebase.auth.** { *; }
-keep class com.google.firebase.internal.api.** { *; }
-keep class com.google.android.gms.internal.** { *; }
-keep class com.google.android.gms.internal.firebase_auth.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.firebase.auth.**
-dontwarn com.google.android.gms.internal.**
-dontwarn com.google.android.gms.tasks.**

# ── Google Identity / Credential Manager (required for Google Sign-In) ──────
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-keep class androidx.credentials.playservices.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**
-dontwarn androidx.credentials.**
-dontwarn androidx.credentials.playservices.**

# ── Google Play Services Auth + Common ─────────────────────────────────────
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.base.** { *; }
-keep class com.google.android.gms.dynamic.** { *; }
-keep class com.google.android.gms.security.** { *; }
-dontwarn com.google.android.gms.auth.**
-dontwarn com.google.android.gms.common.**
-dontwarn com.google.android.gms.base.**
-dontwarn com.google.android.gms.dynamic.**
-dontwarn com.google.android.gms.security.**

# ── Google API Client ──────────────────────────────────────────────────────
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**

# ── PDFBox (optional JPX/JPEG 2000 classes not bundled on Android) ──────────
-dontwarn com.gemalto.jp2.**

# Suppress warnings
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-dontwarn io.ktor.**
-dontwarn coil.**