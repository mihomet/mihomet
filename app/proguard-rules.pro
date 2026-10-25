# Keep LibSu classes
-keep class com.topjohnwu.superuser.** { *; }

# Keep Mihomo API serialization models
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
