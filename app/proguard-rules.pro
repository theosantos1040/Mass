# ProGuard rules for Mass Patcher

# Kotlin
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Apache Commons IO
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.io.**

# APK Signing
-keep class com.android.tools.** { *; }
-dontwarn com.android.tools.**

# Keep all model classes
-keep class com.mass.patcher.** { *; }

# Keep all Activity, Service, BroadcastReceiver, etc
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.IntentService
-keep public class * extends android.content.ContentProvider

# Generic TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
