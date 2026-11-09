# ICQ New — R8 rules. Code is shrunk but NOT renamed (easier crash logs, no reflection surprises).
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# our own code (serializers, Room entities, view models) stays as is
-keep class com.icqnew.app.** { *; }

# WebRTC is called from native code
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Ktor (OkHttp engine, websockets, content negotiation)
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn kotlinx.serialization.**

# OkHttp optional TLS providers
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# misc
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
