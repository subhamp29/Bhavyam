# Retrofit/OkHttp ship their own consumer rules; these silence R8 warnings
# on optional dependencies they reference reflectively but this app doesn't use.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# JNI exports use the Kotlin class and method names verbatim.
# Every native bridge below is bound by the static long name
# (Java_<package>_<class>_<method>) -- none of them register dynamically via
# RegisterNatives/JNI_OnLoad -- so R8 must not rename the class or the method,
# or the .so export stops resolving at runtime.
#   NativeAudioEngine  -> 31 exports in NativeBridge.cpp
#   NativeSecrets      -> 1 export in SecretsBridge.cpp (addon request signing)
#   UsbAudioStream     -> 14 exports in the :audio module's usb-audio-output.cpp
-keep class com.bhavya.music.playback.NativeAudioEngine { *; }
-keep class com.bhavya.music.data.lossless.NativeSecrets { *; }
-keep class com.decent.usbaudio.UsbAudioStream { *; }

# kotlinx.serialization: keep generated serializers and Serializable models
-keepclassmembers class com.bhavya.music.** {
    *** Companion;
}
-keepclasseswithmembers class com.bhavya.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.bhavya.music.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses @kotlinx.serialization.Serializable class * { *; }
-keep,includedescriptorclasses class * implements kotlinx.serialization.KSerializer { *; }

# Room: entities/DAOs are referenced by generated code via reflection-free
# codegen already, but keep annotations so schema export keeps working.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *

# Hilt/Dagger generated components
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }

# Glance app widgets and action callbacks
-keep class * implements androidx.glance.appwidget.action.ActionCallback { *; }
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class com.bhavya.music.widget.** { *; }

# NewPipe's YouTube extractor loads service implementations and its
# JavaScript deobfuscation engine dynamically.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn java.beans.**
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**

# InnerTubeX is reached through the Kotlin-compatibility reflection boundary;
# retain its public constructors/models and the selected Ktor engine in release
# variants so extraction cannot be removed as unreachable code.
-keep class com.metrolist.innertubex.** { *; }
-keep class io.ktor.client.HttpClientJvmKt { *; }
-keep class io.ktor.client.HttpClientKt { *; }
-keep class io.ktor.client.engine.cio.** { *; }

# Kyant Backdrop (AndroidLiquidGlass): AGSL RuntimeShader strings and
# RenderEffect bridges are reached via Compose runtime — keep in release builds.
-keep class com.kyant.backdrop.** { *; }
-keep class com.kyant.shapes.** { *; }

# Silence R8 missing-class warnings for Kotlin 2.x standard library and IO additions
# referenced by the InnerTubeX runtime jar.
-dontwarn kotlin.**
-dontwarn kotlinx.io.**
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.serialization.**
-dontwarn com.metrolist.innertubex.**
-dontwarn io.ktor.**

