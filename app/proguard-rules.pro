# Mihomet R8 rules

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class org.mihomet.app.**$$serializer { *; }
-keepclassmembers class org.mihomet.app.** { *** Companion; }
-keepclasseswithmembers class org.mihomet.app.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Koin ---
-keep class org.koin.** { *; }
-keep class org.mihomet.app.di.** { *; }

# --- Ktor ---
-dontwarn org.slf4j.impl.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.client.** { *** Companion; }
-keepclasseswithmembers class io.ktor.client.** { kotlinx.serialization.KSerializer serializer(...); }

# --- LibSu (root shell) ---
-keep class com.topjohnwu.superuser.** { *; }

# --- SnakeYAML (subscription/config parsing) ---
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# --- Room ---
-keep class androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- Keep reflection-based service constructors (Android framework) ---
-keepclasseswithmembers class * extends android.app.Service {
    public <init>();
}
