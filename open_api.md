# Mi-FreeForm Open API

Mi-FreeForm is a Shizuku/Sui-only application. External apps can open the built-in app picker through the exported activity below; the picker then starts the selected app in freeform mode through Mi-FreeForm's Shizuku service.

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

The Mi-FreeForm keep-alive service must be running, Shizuku or Sui must be authorized for Mi-FreeForm, and the required overlay permission must be enabled. The launcher/recents hook is not part of this build.

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

This is an explicit package/activity API. With Shizuku it opens the target in freeform; without Shizuku it launches the same target normally in fullscreen. It does not inspect the current launcher task; that behavior in YAMF/reYAMF depended on Xposed launcher hooks and is intentionally not included.
