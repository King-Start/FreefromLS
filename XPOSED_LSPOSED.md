# Optional LSPosed / Xposed mode

The APK contains an optional Xposed module entry in `app/src/main/assets/xposed_init`.
It is not required for standalone taskbar mode or for the preferred Shizuku/Sui backend.

## Included hooks

- `HookFramework`: registers the `user.mifreeform` binder in the system process, relaxes virtual-display launch checks, and connects the hook-owned freeform display window to the framework display service.
- `HookSystemUI`: keeps the Android 11+ SystemUI display layout workaround guarded and version-safe.
- `HookLauncher`: adds an `Open with Mi-Freeform` action to compatible Launcher3/Quickstep recents cards.
- `FreeFormHookWindow`: renders the Xposed fallback freeform window, launches the selected activity on its display, handles resize/close gestures, input routing, and app termination.

## Capability selection

1. If Shizuku/Sui is authorized, the app uses its Shizuku virtual-display service.
2. Otherwise, if the Xposed binder `user.mifreeform` is available, the app uses the Xposed freeform bridge.
3. Otherwise, the sidebar/app picker behaves as a normal taskbar and launches applications fullscreen.

The app never claims a fullscreen launch is a freeform window.

## LSPosed setup

1. Install the APK.
2. Enable it as an LSPosed/Xposed module.
3. Enable scope for `android`, `com.android.systemui`, and the installed Launcher3/Quickstep package when the launcher recents action is desired.
4. Reboot, or restart the affected processes.
5. Grant overlay and accessibility/keep-alive permissions to Mi-Freeform.
6. Shizuku is optional when the module is active.

The display internals are Android/OEM-specific. The copied framework bridge targets the Android 10/11 overlay-display layout used by the reference implementation; on newer or heavily customized ROMs the bridge safely skips unsupported hooks and the app falls back to fullscreen mode. Physical-device testing is required.
