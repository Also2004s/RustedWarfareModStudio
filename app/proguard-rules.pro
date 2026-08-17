-dontwarn javax.annotation.**
-keep class com.rwmodstudio.core.** { *; }
-keep class kotlinx.serialization.** { *; }

# Core library desugaring
-keep class java.util.** { *; }
-keep class java.time.** { *; }

# sora-editor
-keep class io.github.rosemoe.sora.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**

# jcodings（sora-editor language-textmate 内部使用，避免 R8 删除编码实现类）
-keep class org.jcodings.** { *; }
-dontwarn org.jcodings.**

# Kotlin stdlib 内部实现被 sora-editor 反射引用
-dontwarn kotlin.Cloneable$DefaultImpls
