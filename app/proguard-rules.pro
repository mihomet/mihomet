# Mihomet R8 rules (audit P2.11: trimmed to narrow keeps only).
#
# Ktor, Koin and Room publish their own consumer rules in their AARs — the old
# blanket `-keep class <lib>.** { *; }` lines defeated shrinking/obfuscation for
# them and are gone. If a release build crashes with ClassNotFoundException /
# reflection failures on-device, re-add the NARROWEST rule that fixes it and
# document the why on the line.

# --- kotlinx.serialization (required: generated serializers via reflection) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class org.mihomet.app.**$$serializer { *; }
-keepclassmembers class org.mihomet.app.** { *** Companion; }
-keepclasseswithmembers class org.mihomet.app.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Ktor (narrow: same serializer pattern as above; no blanket keep) ---
-dontwarn org.slf4j.impl.**
-keepclassmembers class io.ktor.client.** { *** Companion; }
-keepclasseswithmembers class io.ktor.client.** { kotlinx.serialization.KSerializer serializer(...); }

# --- LibSu (root shell): spawns services via reflection on our IpcService
#     subclasses and reads its annotations — keep the API surface. ---
-keep class com.topjohnwu.superuser.** { *; }

# --- Keep reflection-based service constructors (Android framework) ---
-keepclasseswithmembers class * extends android.app.Service {
    public <init>();
}

# --- SnakeYAML: references java.beans.* (desktop JVM introspection) that does
#     not exist on Android. We only parse YAML into Map/List/POJO-safe
#     structures, never bean introspection, so these classes are never reached
#     at runtime — silence the compile-time references only (no keep needed). ---
-dontwarn java.beans.**

