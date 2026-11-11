-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*
-renamesourcefileattribute SourceFile

-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keep class org.signal.libsignal.** { *; }
-keep class net.zetetic.database.** { *; }
-keep class org.torproject.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.duckduckgo.translatekit.** { *; }

-keep class com.google.crypto.tink.shaded.protobuf.InvalidProtocolBufferException
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }

-keep public class * implements com.bumptech.glide.module.GlideModule

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
