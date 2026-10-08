# 米窗开放 API

米窗支持三种能力模式：优先使用 Shizuku/Sui 的虚拟屏幕小窗；可选使用 LSPosed/Xposed 的系统进程小窗桥接；两者都没有时，侧边栏和应用选择器仍可作为普通任务栏全屏打开应用。

## 打开应用选择器

Activity：

```text
com.sunshine.freeform.ui.floating.FloatingActivity
```

调用示例：

```kotlin
val intent = Intent().setClassName(
    "com.sunshine.freeform",
    "com.sunshine.freeform.ui.floating.FloatingActivity"
)
context.startActivity(intent)
```

调用前需要确保米窗保活服务正在运行，并已开启必要的悬浮窗权限。Shizuku/Sui 用于首选虚拟屏幕后端；也可以在 LSPosed/Xposed 中启用本 APK 的 module，使用 `user.mifreeform` 桥接。两者都没有时，选择器仍可全屏启动应用。

## 通过显式广播启动小窗

对于已经知道目标 Activity 的外部应用，可以发送以下广播：

```text
com.sunshine.freeform.action.START_FREEFORM
```

可以通过 `Intent.EXTRA_INTENT` 传入目标 Intent，或者传入以下 extras：

```text
packageName   = 目标应用包名，例如 com.example.app
activityName  = 完整的启动 Activity 类名
com.sunshine.freeform.extra.USER_ID = 可选的 Android 用户 ID
```

示例：

```kotlin
val request = Intent("com.sunshine.freeform.action.START_FREEFORM").apply {
    setPackage("com.sunshine.freeform")
    putExtra("packageName", "com.example.app")
    putExtra("activityName", "com.example.app.MainActivity")
    putExtra("com.sunshine.freeform.extra.USER_ID", 0)
}
context.sendBroadcast(request)
```

这是显式的包名/Activity API。有 Shizuku 时通过虚拟屏幕打开；有 LSPosed/Xposed 时通过 `user.mifreeform` 桥接；两者都没有时像普通任务栏一样全屏打开。可选 Xposed module 还会为兼容的 Launcher3/Quickstep 最近任务增加“使用米窗打开”操作，但不同 ROM/启动器的 hook 兼容性可能不同。

[English](open_api.md)
