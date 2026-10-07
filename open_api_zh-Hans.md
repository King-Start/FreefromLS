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

调用前需要确保米窗保活服务正在运行、已为米窗授权 Shizuku 或 Sui，并已开启必要的悬浮窗权限。本版本不提供启动器/最近任务广播 hook。

[English](open_api.md)
