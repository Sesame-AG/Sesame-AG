# ---------- 框架 ----------
-keep class io.github.libxposed.service.** { *; }
-dontwarn io.github.libxposed.service.**
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# ---------- Shizuku ----------
-keep class dev.rikka.shizuku.** { *; }
-dontwarn dev.rikka.shizuku.**

# ---------- cmd-android ----------
-keep class com.niki.** { *; }
-dontwarn com.niki.**


# ---------- 日志 ----------
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn ch.qos.logback.**, org.slf4j.**

# ---------- 本工程 ----------
-keep class io.github.aoguai.sesameag.** { *; }

# ---------- Jackson（最小必要） ----------
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature, *Annotation*
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.** *;
}

# ---------- 序列化 & 缺失类 ----------
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable { *; }
-dontwarn java.beans.ConstructorProperties, java.beans.Transient
