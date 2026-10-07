# Mi-FreeForm merged / fixed build

This project uses `Mi-FreeFormn-flyme` as the base. The other three uploaded projects were used as reference implementations; they were not copied wholesale because the YAMF and reYAMF projects use a different Android 13+ architecture and package/API surface.

## Merged feature areas

- Flyme-style freeform UI, Shizuku/Sui activation, accessibility/foreground-service modes, notification launching, QS tile, and sidebar from the base project.
- Sidebar, QS tile, notification actions, and the in-app app picker remain the supported launch paths. The launcher/Xposed recent-task hook was removed from this Shizuku-only build.
- Multiple-window limit with per-user duplicate detection, safer virtual-display cleanup, and secure virtual-display option.
- Shizuku-side Android 8–15 compatibility hardening and defensive task/display handling. System-wide Xposed hooks are intentionally not included.
- Safer input injection, pinch resizing, swipe home/forward on pre-Q, phone/screen receiver registration on Android 13+, and idempotent cleanup.

## Important build/runtime notes

- This is a Shizuku/Sui-only system-level tool. LSPosed/Xposed is not required. It cannot be fully validated in a normal desktop JVM or without a real Android device.
- The source was statically checked and the Gradle task was attempted. The sandbox could not complete Gradle dependency resolution because the Java 11 TLS client could not download several Maven artifacts; this is an environment/network limitation, not a source-level build result.
- Install and test on a spare device first. Authorize Shizuku/Sui, enable the required accessibility/overlay permissions, then start the app. Android/OEM framework internals differ, so the Shizuku API path may still vary by ROM.
- The release key and prebuilt APKs from the uploaded reference archives are intentionally not included. Sign the APK with your own key.

## Main fixes in this merge

1. Prevent `ConcurrentModificationException` in `StackSet.clean()`.
2. Remove unsafe component-name force unwraps and repeated service/virtual-display cleanup crashes.
3. Validate DPI input and protect input-event arrays/injection from null or invalid state.
4. Add Android 13+ dynamic receiver flags and safe receiver unregistration.
5. Add an optional `secure_virtual_display` setting and clamp the maximum-window setting to 1–10.
