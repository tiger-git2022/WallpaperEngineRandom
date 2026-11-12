# 没有用到反射；OkHttp / Coil 自带规则。保留行号方便看崩溃日志。
-keepattributes SourceFile,LineNumberTable
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Anthropic Java SDK（新闻翻译用 Claude）：SDK 的 jar 自带 R8 规则；这些是它的依赖引用、安卓上没有也用不到的类
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn io.swagger.v3.oas.annotations.**
-dontwarn com.github.victools.jsonschema.**
-dontwarn io.opentelemetry.**
