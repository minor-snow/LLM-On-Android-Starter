# Add project specific ProGuard rules here.
# Keep OkHttp and Coroutines
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# LLM Bridge SDK
-keep class dev.llmbridge.client.** { *; }
-keep class dev.llmbridge.reliability.** { *; }
