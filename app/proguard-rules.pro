# ProGuard rules for Agent42
# Nexa SDK
-keep class ai.nexa.** { *; }
-dontwarn ai.nexa.**

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }
-keep class com.agent42.memory.** { *; }
-dontwarn androidx.room.paging.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# General
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
