# libsignal: JNI calls back into these classes by name.
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

# SQLCipher JNI
-keep class net.zetetic.database.** { *; }

# Tink uses protobuf-lite reflection
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.rajdeep.vesper.** {
    *** Companion;
}
-keepclasseswithmembers class com.rajdeep.vesper.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.rajdeep.vesper.**$$serializer { *; }

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
