# WireGuard tunnel
-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher
-keep class net.zetetic.database.sqlcipher.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class net.swlr.vpnmaster.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class net.swlr.vpnmaster.backup.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# Strip verbose, debug, and info logging from release builds.
# Only Log.w and Log.e remain, which indicate actual problems.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# General
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
