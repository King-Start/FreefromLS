package com.sunshine.freeform.hook

import android.app.Activity
import android.app.AndroidAppHelper
import android.app.Application
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.view.View
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Optional recents integration. It adds an "open with Mi-Freeform" action to
 * Launcher3/Quickstep task cards and leaves the normal recents actions intact.
 */
class HookLauncher : IXposedHookLoadPackage {
    private var moduleContext: Context? = null

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (param.packageName != "com.android.launcher3" &&
            param.packageName != "com.android.quickstep") return

        // Simpan class loader paket di sini: di dalam callback hook, `param`
        // adalah MethodHookParam (tidak punya classLoader).
        val packageClassLoader = param.classLoader
        runCatching {
            val taskOverlayFactory = XposedHelpers.findClass(
                "com.android.quickstep.TaskOverlayFactory",
                packageClassLoader
            )
            XposedBridge.hookAllMethods(
                taskOverlayFactory,
                "getEnabledShortcuts",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val taskView = param.args.firstOrNull() as? View ?: return
                        val shortcuts = param.result as? MutableList<Any> ?: return
                        if (shortcuts.isEmpty()) return

                        val itemInfo = XposedHelpers.getObjectField(shortcuts[0], "mItemInfo")
                        val topComponent = runCatching {
                            XposedHelpers.callMethod(itemInfo, "getTargetComponent") as ComponentName
                        }.getOrNull() ?: return
                        val task = runCatching {
                            XposedHelpers.callMethod(taskView, "getTask")
                        }.getOrNull() ?: return
                        val key = runCatching {
                            XposedHelpers.getObjectField(task, "key")
                        }.getOrNull() ?: return
                        val userId = runCatching {
                            XposedHelpers.getIntField(key, "userId")
                        }.getOrDefault(0)
                        val appContext = getModuleContext() ?: return
                        val shortcutClass = XposedHelpers.findClass(
                            "com.android.launcher3.popup.RemoteActionShortcut",
                            packageClassLoader
                        )

                        val request = Intent("com.sunshine.freeform.action.START_FREEFORM").apply {
                            setPackage(MiFreeform.PACKAGE_NAME)
                            putExtra("packageName", topComponent.packageName)
                            putExtra("activityName", topComponent.className)
                            putExtra("userId", userId)
                        }
                        val action = RemoteAction(
                            Icon.createWithResource(appContext, R.drawable.tile_icon),
                            appContext.getString(R.string.recent_open_by_freeform),
                            appContext.getString(R.string.recent_open_by_freeform),
                            PendingIntent.getBroadcast(
                                AndroidAppHelper.currentApplication(),
                                (topComponent.packageName + userId).hashCode(),
                                request,
                                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                        val constructor = shortcutClass.constructors.firstOrNull() ?: return
                        val shortcut = when (constructor.parameterCount) {
                            4 -> constructor.newInstance(action, taskView.context, itemInfo, null)
                            3 -> constructor.newInstance(action, taskView.context, itemInfo)
                            else -> null
                        }
                        if (shortcut != null && shortcuts.none { it.javaClass == shortcut.javaClass }) {
                            shortcuts.add(shortcut)
                        }
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("Mi-Freeform HookLauncher skipped: ${it.message}")
        }
    }

    private fun getModuleContext(): Context? {
        moduleContext?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            val application = activityThread.getMethod("getApplication").invoke(current) as Application
            application.createPackageContext(
                MiFreeform.PACKAGE_NAME,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ).also { moduleContext = it }
        }.getOrNull()
    }

    private fun Context.getActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.getActivity()
        else -> null
    }
}
