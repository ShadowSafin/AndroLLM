# ProGuard rules for core:database
-keep class io.androllm.core.database.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * implements androidx.room.TypeConverter { *; }