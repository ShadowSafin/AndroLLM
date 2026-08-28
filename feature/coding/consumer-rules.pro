# Keep kotlinx.serialization generated serializers for coding session state.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.androllm.feature.coding.** {
    *** Companion;
}
-keepclasseswithmembers class io.androllm.feature.coding.** {
    kotlinx.serialization.KSerializer serializer(...);
}
