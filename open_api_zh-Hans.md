# 米窗开放 API

米窗是仅使用 Shizuku/Sui 的应用。其他应用可以打开米窗提供的应用选择器，选择器会通过米窗的 Shizuku 服务启动小窗应用。

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

调用前需要确保米窗保活服务正在运行、已为米窗授权 Shizuku 或 Sui，并已开启必要的悬浮窗权限。本版本不使用启动器/最近任务 hook。

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

这是显式的包名/Activity API。有 Shizuku 时会在小窗中打开目标应用；没有 Shizuku 时会像普通任务栏一样全屏打开目标应用。它不会读取当前前台应用。YAMF/reYAMF 的当前应用启动行为依赖 Xposed 启动器 hook，因此没有加入本版本。

[English](open_api.md)
