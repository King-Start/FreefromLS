# Mi-FreeForm merged / fixed build

This project uses `Mi-FreeFormn-flyme` as the base. The other three uploaded projects were used as reference implementations; they were not copied wholesale because the YAMF and reYAMF projects use a different Android 13+ architecture and package/API surface.

## Merged feature areas

- Flyme-style freeform UI, Shizuku/Sui activation, accessibility/foreground-service modes, notification launching, QS tile, and sidebar from the base project.
- Launcher recents action with defensive handling for Launcher3/Nexus/Motorola launcher variants, based on the YAMF implementations.
- Multiple-window limit with per-user duplicate detection, safer virtual-display cleanup, and secure virtual-display option.
- Android 8–13 compatibility hooks and defensive Android 13/14/15 `TaskDisplayArea` handling, based on the older `freeform_update` compatibility work and the newer reYAMF fixes.
- Safer input injection, pinch resizing, swipe home/forward on pre-Q, phone/screen receiver registration on Android 13+, and idempotent cleanup.

## Important build/runtime notes

- This is an Xposed/LSPosed + Shizuku/Sui system-level tool. It cannot be fully validated in a normal desktop JVM or without a real Android device.
- The source was statically checked and the Gradle task was attempted. The sandbox could not complete Gradle dependency resolution because the Java 11 TLS client could not download several Maven artifacts; this is an environment/network limitation, not a source-level build result.
- Install and test on a spare device first. Enable the module for `android`, SystemUI, and the launcher package, then reboot. Android/OEM framework internals differ, so the hooks intentionally fail closed when a class or field is unavailable.
- The release key and prebuilt APKs from the uploaded reference archives are intentionally not included. Sign the APK with your own key.

## Main fixes in this merge

1. Prevent `ConcurrentModificationException` in `StackSet.clean()`.
2. Remove unsafe component-name force unwraps and repeated service/virtual-display cleanup crashes.
3. Prevent a constant PendingIntent request code from launching the wrong recents app.
4. Do not hook every loaded package; limit the launcher hook to known launcher packages.
5. Handle empty recents shortcut lists and missing OEM reflection classes without crashing Launcher/SystemUI.
6. Add Android 13+ task-display handling and keep the module alive when Android 14/15 changes a hidden API.
7. Validate DPI input and protect input-event arrays/injection from null or invalid state.
8. Add Android 13+ dynamic receiver flags and safe receiver unregistration.
9. Add an optional `secure_virtual_display` setting and clamp the maximum-window setting to 1–10.
