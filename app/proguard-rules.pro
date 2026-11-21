# Standard Android R8/ProGuard rules for Na Na (AI_TALK)
# Note: debug and release currently set isMinifyEnabled = false; rules are kept ready for release builds.

# Keep Kotlinx Serialization generated serializers & companion objects
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.adwewecat.nana.core.model.**$$serializer { *; }
-keep,includedescriptorclasses class com.adwewecat.nana.core.sync.**$$serializer { *; }
-keep,includedescriptorclasses class com.adwewecat.nana.data.local.**$$serializer { *; }

# Keep Room database entities and generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}

# Keep Vosk offline speech recognition & JNA native bindings
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-dontwarn com.sun.jna.**

# Keep AppAuth OAuth classes
-keep class net.openid.appauth.** { *; }
-dontwarn net.openid.appauth.**
