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
 * Adds the "Open by Mi-Freeform" action to launcher recents.
 *
 * Launcher internals are not a stable API. This hook is deliberately
 * best-effort: it only runs in known launcher packages, tolerates an empty
 * shortcut list, and never lets a launcher reflection error bring down the
 * launcher process.
 */
class HookLauncher : IXposedHookLoadPackage {

    private var moduleContext: Context? = null

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (param.packageName !in LAUNCHER_PACKAGES) return

        runCatching { hookLauncherAfterQ(param.classLoader) }
            .onFailure {
                XposedBridge.log(
                    "[Mi-Freeform] launcher hook skipped for ${param.packageName}: ${it.message}"
                )
            }
    }

    private fun hookLauncherAfterQ(classLoader: ClassLoader) {
        val taskOverlayFactory = XposedHelpers.findClass(
            "com.android.quickstep.TaskOverlayFactory", classLoader
        )
        val shortcutClass = runCatching {
            XposedHelpers.findClass(
                "com.android.launcher3.popup.RemoteActionShortcut", classLoader
            )
        }.getOrElse {
            XposedBridge.log("[Mi-Freeform] RemoteActionShortcut is unavailable")
            return
        }

        XposedBridge.hookAllMethods(
            taskOverlayFactory,
            "getEnabledShortcuts",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        addFreeformShortcut(param, shortcutClass)
                    }.onFailure {
                        // This callback executes on the launcher main thread.
                        // Never propagate reflection failures into Launcher3.
                        XposedBridge.log("[Mi-Freeform] recents shortcut skipped: ${it.message}")
                    }
                }
            }
        )
    }

    private fun addFreeformShortcut(
        param: XC_MethodHook.MethodHookParam,
        shortcutClass: Class<*>
    ) {
        val taskView = param.args.firstOrNull() as? View ?: return
        @Suppress("UNCHECKED_CAST")
        val shortcuts = param.result as? MutableList<Any> ?: return
        if (shortcuts.isEmpty()) return

        val itemInfo = runCatching {
            XposedHelpers.getObjectField(shortcuts.first(), "mItemInfo")
        }.getOrNull() ?: return
        val topComponent = runCatching {
            XposedHelpers.callMethod(itemInfo, "getTargetComponent") as? ComponentName
        }.getOrNull() ?: return

        val task = runCatching {
            XposedHelpers.callMethod(taskView, "getTask")
        }.getOrNull()
        val key = task?.let {
            runCatching { XposedHelpers.getObjectField(it, "key") }.getOrNull()
        }
        val userId = key?.let {
            runCatching { XposedHelpers.getIntField(it, "userId") }.getOrNull()
        } ?: 0
        val taskId = key?.let {
            runCatching { XposedHelpers.getIntField(it, "id") }.getOrNull()
        } ?: 0
        val activity = taskView.context.getActivity() ?: return
        val appContext = getModuleContext() ?: return

        val intent = Intent(ACTION_START_FREEFORM).apply {
            setPackage(MiFreeform.PACKAGE_NAME)
            putExtra("packageName", topComponent.packageName)
            putExtra("activityName", topComponent.className)
            putExtra("userId", userId)
            putExtra("taskId", taskId)
        }

        // PendingIntent identity ignores most extras. A constant request code
        // made every recents item launch the first app after the first click.
        // Use the task id and update the existing token when Launcher refreshes.
        val requestCode = (taskId * 31 + topComponent.hashCode()) and Int.MAX_VALUE
        val pendingIntent = PendingIntent.getBroadcast(
            AndroidAppHelper.currentApplication(),
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = RemoteAction(
            Icon.createWithResource(appContext, R.drawable.tile_icon),
            appContext.getString(R.string.recent_open_by_freeform),
            appContext.getString(R.string.recent_open_by_freeform),
            pendingIntent
        )

        val shortcut = shortcutClass.constructors.firstNotNullOfOrNull { constructor ->
            runCatching {
                when (constructor.parameterCount) {
                    4 -> constructor.newInstance(action, activity, itemInfo, null)
                    3 -> constructor.newInstance(action, activity, itemInfo)
                    else -> null
                }
            }.getOrNull()
        }
        if (shortcut != null) shortcuts.add(shortcut)
    }

    private fun getModuleContext(): Context? {
        moduleContext?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThread
                .getMethod("currentActivityThread")
                .invoke(null)
            val application = activityThread
                .getMethod("getApplication")
                .invoke(currentActivityThread) as Application
            application.createPackageContext(
                MiFreeform.PACKAGE_NAME,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ).also { moduleContext = it }
        }.onFailure {
            XposedBridge.log("[Mi-Freeform] module context unavailable: ${it.message}")
        }.getOrNull()
    }

    private fun Context.getActivity(): Activity? {
        if (this is Activity) return this
        if (this is ContextWrapper && baseContext !== this) {
            return baseContext.getActivity()
        }
        return null
    }

    companion object {
        private const val ACTION_START_FREEFORM = "com.sunshine.freeform.start_freeform"
        private val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.motorola.launcher3"
        )
    }
}
