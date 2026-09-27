# Shizuku 的 UserService / 反射调用涉及大量系统隐藏 API，
# 混淆会导致运行时反射失败，因此保留相关类与成员名。
-keep class com.cliplink.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class android.content.IClipboard { *; }
-keep class android.content.IOnPrimaryClipChangedListener { *; }

# AIDL 生成的 Stub/Proxy 依赖 Binder 反射注入
-keep class **.Stub { *; }
-keep class **.Proxy { *; }

-dontwarn rikka.shizuku.**
