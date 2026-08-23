# Engine consumer ProGuard rules
# Keep all engine classes for consumers (the app)

-keep class io.androllm.engine.** { *; }
-keep class io.androllm.engine.llama.LlamaCppEngine { *; }
-keep class io.androllm.engine.api.** { *; }
-keep class io.androllm.engine.di.EngineModule { *; }
-keep class io.androllm.engine.models.** { *; }
-keep class io.androllm.engine.backend.** { *; }
-keep class io.androllm.engine.jni.** { *; }
-keep class io.androllm.engine.utils.** { *; }
