# WireGuard tunnel
-keep class com.wireguard.** { *; }
-keep class com.wireguard.android.backend.** { *; }

# strongSwan JNI
-keep class net.swlr.vpnmaster.vpn.ikev2.CharonBridge { *; }
-keep class net.swlr.vpnmaster.vpn.ikev2.CharonBridge$* { *; }
-keepclassmembers class net.swlr.vpnmaster.vpn.ikev2.CharonBridge {
    native <methods>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class net.swlr.vpnmaster.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# General
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
