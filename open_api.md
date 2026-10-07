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

The Mi-FreeForm keep-alive service must be running, Shizuku or Sui must be authorized for Mi-FreeForm, and the required overlay permission must be enabled. The launcher/recents broadcast hook is not part of this build.
