# Mi-FreeForm Open API

Mi-FreeForm supports three capability levels. Shizuku/Sui is the preferred freeform backend; an optional LSPosed/Xposed module provides a system-process freeform bridge; without either one, the same sidebar/app picker works as a normal fullscreen taskbar.

## Open the app picker

Activity:

```text
com.sunshine.freeform.ui.floating.FloatingActivity
```

Example:

```kotlin
val intent = Intent().setClassName(
    "com.sunshine.freeform",
    "com.sunshine.freeform.ui.floating.FloatingActivity"
)
context.startActivity(intent)
```

The Mi-FreeForm keep-alive service and overlay permission are required. Shizuku/Sui is required for the preferred virtual-display backend; alternatively enable this APK as an LSPosed/Xposed module so the `user.mifreeform` bridge is available. If neither capability is active, the picker still launches apps fullscreen.

## Explicit Shizuku launch broadcast

For integrations that already know the target activity, send an explicit broadcast with action:

```text
com.sunshine.freeform.action.START_FREEFORM
```

Either provide a target `Intent` in `Intent.EXTRA_INTENT`, or provide these extras:

```text
packageName   = target package, for example com.example.app
activityName  = fully-qualified launcher activity name
com.sunshine.freeform.extra.USER_ID = optional Android user ID
```

Example:

```kotlin
val request = Intent("com.sunshine.freeform.action.START_FREEFORM").apply {
    setPackage("com.sunshine.freeform")
    putExtra("packageName", "com.example.app")
    putExtra("activityName", "com.example.app.MainActivity")
    putExtra("com.sunshine.freeform.extra.USER_ID", 0)
}
context.sendBroadcast(request)
```

This is an explicit package/activity API. With Shizuku it opens the target through the virtual display; with LSPosed/Xposed it uses the `user.mifreeform` bridge; without either it launches the same target normally in fullscreen. The optional Xposed module also adds an “Open with Mi-Freeform” action to compatible Launcher3/Quickstep recents. Launcher/OEM taskbar hooks remain version-sensitive.
