# Moka 壳模式的 R8 保留规则。
#
# 上游一直没开 R8,所以这份规则是从零写的。原则:**只保反射和序列化真正需要的东西**,
# 不做大范围 -keep class * —— 那等于没开。
#
# 判断依据(不要凭感觉加规则):
# - 用 Class.forName / getDeclaredMethod 拿的类,如果**是系统类**(android.*, com.android.*),
#   R8 碰不到它们,不用 keep。本项目里的反射全属这一类
#   (DisplayManagerGlobal、WindowManagerGlobal、ServiceManager、ITaskStackListener$Stub 等)。
# - 用 Class.forName 拿**自己代码里的类**,才必须 keep。

# ── kotlinx.serialization ────────────────────────────────────────────
# 序列化器是编译期生成的 Companion.serializer();R8 看不见调用方,会把它们当无用代码删掉。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 本项目所有 @Serializable 类(@Serializable 会生成 $$serializer 与 Companion)
-keep,includedescriptorclasses class com.wangzan101.mika.**$$serializer { *; }
-keepclassmembers class com.wangzan101.mika.** {
    *** Companion;
}
-keepclasseswithmembers class com.wangzan101.mika.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── 反射调用的系统服务 ──────────────────────────────────────────────
# 保留注解与签名信息,反射拿方法/构造器时不至于因为丢了泛型和注解而失败。
-keepattributes Signature, Exceptions

# ── Shizuku ─────────────────────────────────────────────────────────
# AAR 自带 consumer 规则,这里只兜底接口回调与 Binder 代理。
-keep interface rikka.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ── protobuf-lite ───────────────────────────────────────────────────
# 生成的 Message 类走反射解析 descriptor,按官方建议整体保留。
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**

# ── 系统/平台组的告警 ───────────────────────────────────────────────
# 这些类只在特定 Android 版本才存在,编译期看不到,R8 会报缺类警告。
-dontwarn android.app.**
-dontwarn android.hardware.display.**
-dontwarn android.view.**
-dontwarn android.os.**
-dontwarn android.content.**

# ── ML Kit ──────────────────────────────────────────────────────────
# AAR 自带规则;这里只压掉可选的离线模型告警。
-dontwarn com.google.mlkit.**
