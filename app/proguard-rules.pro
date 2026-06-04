# This is a configuration file for ProGuard.
# http://proguard.sourceforge.net/index.html#manual/usage.html

-dontobfuscate

# Keep all public classes and methods
-keep public class * {
    public *;
}

# Keep Kotlin metadata
-keepclassmembers class ** {
    *** Companion;
}

# Keep serialization classes
-keep class kotlinx.serialization.** { *; }
-keep class kotlin.serialization.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep Jetpack Compose
-keep class androidx.compose.** { *; }

# Keep Android classes
-keep class android.** { *; }
-keep interface android.** { *; }

# Keep our application classes
-keep class com.juwan.lynx.** { *; }
