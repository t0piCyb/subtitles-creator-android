-keep class com.subtitlecreator.jni.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.arthenica.ffmpegkit.** { *; }

# Media3 Transformer pulls classes reflectively via MediaCodec selector.
-keep class androidx.media3.** { *; }
-keepclassmembers class androidx.media3.** { *; }

# kotlinx.serialization — keep @Serializable data classes + generated serializers.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.subtitlecreator.**$$serializer { *; }
-keepclassmembers class com.subtitlecreator.** {
    *** Companion;
}
-keepclasseswithmembers class com.subtitlecreator.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WhisperLib.ProgressCallback is invoked via JNI from native code.
-keep class com.subtitlecreator.jni.WhisperLib$ProgressCallback { *; }
