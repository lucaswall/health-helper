# HealthHelper ProGuard Rules

# ============================================================
# Kotlin Serialization
# ============================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable annotated classes and their serializer companions
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers @kotlinx.serialization.Serializable class ** {
    *;
}

# ============================================================
# Ktor HTTP Client
# ============================================================
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }

# OkHttp engine optional dependencies
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
# Health Connect API
# ============================================================
-keep class androidx.health.connect.client.** { *; }
-keep interface androidx.health.connect.client.** { *; }
-keep class androidx.health.** { *; }
-dontwarn androidx.health.**

# ============================================================
# WorkManager — keep Worker subclasses (referenced by class name)
# ============================================================
-keep public class * extends androidx.work.Worker
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.** { *; }

# ============================================================
# Hilt (annotation processor generates its own rules, but add extras)
# ============================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }

# ============================================================
# DataStore Preferences
# ============================================================
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }

# ============================================================
# Timber Logging
# ============================================================
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# ============================================================
# Android & Kotlin standard
# ============================================================
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
