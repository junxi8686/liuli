# Liuli — R8 rules for the release build.

# ------------------------------------------------------------------ Kotlin
-dontwarn kotlin.**
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable

# kotlinx.serialization ships its own consumer rules, but the generated
# serializers for our sealed wire hierarchy are looked up through the
# companion `serializer()` accessor, so keep them reachable.
-keepclassmembers class com.liuli.btchat.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.liuli.btchat.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.liuli.btchat.core.**$$serializer { *; }
-if @kotlinx.serialization.Serializable class com.liuli.btchat.**
-keep class com.liuli.btchat.**$$serializer { *; }
-keepclassmembers class com.liuli.btchat.** {
    *** Companion;
}

# --------------------------------------------------------------- Compose
# Compose reports its own unused-code reachability; these are the entries R8
# cannot see through.
-dontwarn androidx.compose.**

# ------------------------------------------------------------ App surface
# Everything the framework instantiates by name.
-keep class com.liuli.btchat.LiuliApp { *; }
-keep class com.liuli.btchat.MainActivity { *; }
-keep class com.liuli.btchat.bt.BluetoothChatService { *; }

# The service is started by name and returns a local binder.
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ------------------------------------------------------------------ Media3
-dontwarn androidx.media3.**

# ------------------------------------------------------------------- misc
# Keep enum valueOf/values: the store persists enums by name.
-keepclassmembers enum com.liuli.btchat.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
