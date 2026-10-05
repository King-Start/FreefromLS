package com.sunshine.freeform.hook

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * @author sunshine
 * @date 2021/2/28
 * 经过排查，小窗内旋转屏幕系统UI闪退是因为DisplayLayout中调用set方法会为空，那么，就将上次的参数保存，如果遇到空，就设置给这个
 */
class HookSystemUI : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        // DisplayLayout is not present on every OEM SystemUI build.  Keep a
        // failed optional compatibility hook from crashing SystemUI.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            lpparam?.packageName == "com.android.systemui") {
            runCatching {
                val clazz = XposedHelpers.findClass(
                    "com.android.systemui.wm.DisplayLayout", lpparam.classLoader
                )
                var lastObj: Any? = null
                XposedBridge.hookAllMethods(clazz, "set", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        val args = param?.args ?: return
                        if (args.isEmpty()) return
                        val obj = args[0]
                        if (obj != null) lastObj = obj else if (lastObj != null) args[0] = lastObj
                    }
                })
            }.onFailure {
                XposedBridge.log("[Mi-Freeform] SystemUI DisplayLayout hook skipped: ${it.message}")
            }
        }
    }
}