package com.sunshine.freeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.InputEvent
import com.sunshine.freeform.hook.utils.XLog
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookFramework : IXposedHookLoadPackage, IXposedHookZygoteInit {

    /**
     * 10
     */
    private fun hookASSOnQ(classLoader: ClassLoader) {
        //val classLoader = Thread.currentThread().contextClassLoader
        val ass = XposedHelpers.findClass("com.android.server.wm.ActivityStackSupervisor", classLoader)
        val taskRecordClazz = XposedHelpers.findClass("com.android.server.wm.TaskRecord", classLoader)
        val activityStackClazz = XposedHelpers.findClass("com.android.server.wm.ActivityStack", classLoader)

        XposedBridge.hookAllMethods(
            ass,
            "handleNonResizableTaskIfNeeded",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mStackField = taskRecordClazz.getDeclaredField("mStack")
                    mStackField.isAccessible = true
                    val mStackObj = mStackField.get(param.args[0])
                    val mDisplayIdField = activityStackClazz.getDeclaredField("mDisplayId")
                    mDisplayIdField.isAccessible = true
                    val mDisplayIdObj = mDisplayIdField.get(mStackObj)

                    XLog.d("mDisplayId:$mDisplayIdObj param2:${param.args[2]}")
                    if (param.args[2] as Int != mDisplayIdObj as Int) {
                        param.args[2] = mDisplayIdObj
                    }

                    XLog.d("hook handleNonResizableTaskIfNeeded success")
                }
            }
        )
    }

    private fun hookASSPreQ(classLoader: ClassLoader) {
        //val classLoader = Thread.currentThread().contextClassLoader
        val ass = XposedHelpers.findClass("com.android.server.am.ActivityStackSupervisor", classLoader)
        XposedHelpers.findAndHookMethod(
            ass,
            "isCallerAllowedToLaunchOnDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ActivityInfo::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                    XLog.d("hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        )
    }

    /**
     * 10 11
     */
    private fun hookASS(classLoader: ClassLoader) {
        //val classLoader = Thread.currentThread().contextClassLoader
        val ass = XposedHelpers.findClass("com.android.server.wm.ActivityStackSupervisor", classLoader)
        XposedHelpers.findAndHookMethod(
            ass,
            "isCallerAllowedToLaunchOnDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ActivityInfo::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                    XLog.d("hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        )
    }

    /**
     * 12 别找了，logcat突然没有这个报错了，可能要换hook点了，明明前几天还有
     */
    private fun hookATS(classLoader: ClassLoader) {
        //val classLoader = Thread.currentThread().contextClassLoader
        val ats = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", classLoader)
        XposedHelpers.findAndHookMethod(
            ats,
            "isCallerAllowedToLaunchOnDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            ActivityInfo::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                    XLog.d("hook isCallerAllowedToLaunchOnDisplay success")
                }
            }
        )
    }

    /**
     * 13
     */
    private fun hookATSOnT(classLoader: ClassLoader) {
        //val classLoader = Thread.currentThread().contextClassLoader
        val ats = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", classLoader)
        val taskClazz = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
        val taskDisplayAreaClazz = XposedHelpers.findClass("com.android.server.wm.TaskDisplayArea", classLoader)
        val displayContentClazz = XposedHelpers.findClass("com.android.server.wm.DisplayContent", classLoader)

        XposedBridge.hookAllMethods(
            ats,
            "handleNonResizableTaskIfNeeded",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mPrevDisplayIdField = taskClazz.getDeclaredField("mPrevDisplayId")
                    mPrevDisplayIdField.isAccessible = true
                    val mPrevDisplayIdObj = mPrevDisplayIdField.get(param.args[0])

                    val mDisplayContentField = taskDisplayAreaClazz.getDeclaredField("mDisplayContent")
                    mDisplayContentField.isAccessible = true
                    val mDisplayContentObj = mDisplayContentField.get(param.args[2])
                    val mDisplayIdField = displayContentClazz.getDeclaredField("mDisplayId")
                    mDisplayIdField.isAccessible = true
                    mDisplayIdField.set(mDisplayContentObj, mPrevDisplayIdObj)
                    XLog.d("hook handleNonResizableTaskIfNeeded success")
                }
            }
        )
    }

    /**
     * 冻结虚拟屏幕方向
     */
    private fun hookDisplayRotation() {
        val classLoader = Thread.currentThread().contextClassLoader
        val displayRotationClazz = XposedHelpers.findClass("com.android.server.wm.DisplayRotation", classLoader)
        XposedBridge.hookAllConstructors(
            displayRotationClazz,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val isDefaultDisplay = XposedHelpers.getBooleanField(param.thisObject, "isDefaultDisplay")
                    if (!isDefaultDisplay) {
                        XposedHelpers.callMethod(param.thisObject, "freezeRotation", 0)
                        XLog.d("freezeRotation success")
                    }
                }
            }
        )
    }

    /**
     * 暂时无用
     */
    private fun hookATSHandleNonResizableTaskIfNeeded() {
        val classLoader = Thread.currentThread().contextClassLoader
        val ats = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", classLoader)
        val taskClazz = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
        val taskDisplayAreaClazz = XposedHelpers.findClass("com.android.server.wm.TaskDisplayArea", classLoader)
        //val activityStackClazz = XposedHelpers.findClass("com.android.server.wm.ActivityStack", classLoader)
        XposedHelpers.findAndHookMethod(
            ats,
            "handleNonResizableTaskIfNeeded",
            taskClazz,
            Int::class.javaPrimitiveType,
            taskDisplayAreaClazz,
            taskClazz,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val taskObj = param.args[0]
                    val preferredTaskDisplayAreaObj = param.args[2]
                    val preferredDisplayField = XposedHelpers.findField(taskDisplayAreaClazz, "mDisplayContent")
                    val displayContentObj = XposedHelpers.callMethod(taskObj, "getDisplayContent")
                    preferredDisplayField.set(preferredTaskDisplayAreaObj, displayContentObj)

                    XLog.d("hook ATSHandleNonResizableTaskIfNeeded success")
                }
            }
        )
    }

    private fun hookIMS() {
        val classLoader = Thread.currentThread().contextClassLoader
        val ims = XposedHelpers.findClass("com.android.server.input.InputManagerService", classLoader)

        XposedBridge.hookAllMethods(
            ims,
            "injectInputEvent",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(p0: MethodHookParam): Any {
                    //以mode代替display
                    return XposedHelpers.callMethod(
                        p0.thisObject,
                        "injectInputEventInternal",
                        p0.args[0],
                        p0.args[1],
                        0
                    )
                }
            }
        )

        /*XposedBridge.hookAllMethods(
            ims,
            "createInputForwarder",
            object : XC_MethodHook() {
                @SuppressLint("SoonBlockedPrivateApi")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mContext = XposedHelpers.getObjectField(param.thisObject, "mContext") as Context
                    val displayManager = mContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val display = displayManager.getDisplay(param.args[0] as Int)
                    val ownerUidField = Display::class.java.getDeclaredField("mOwnerUid")
                    ownerUidField.isAccessible = true
                    ownerUidField.set(display, Binder.getCallingUid())
                }
            }
        )

        XposedBridge.hookAllMethods(
            ims,
            "injectInputEventInternal",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any {
                    val mPtr = XposedHelpers.getLongField(param.thisObject, "mPtr")

                    val event = param.args[0] as InputEvent?
                    val displayId = param.args[1] as Int
                    val mode = param.args[2] as Int
                    if (event == null) {
                        throw IllegalArgumentException("event must not be null")
                    }
                    if (mode != INJECT_INPUT_EVENT_MODE_ASYNC &&
                        mode != INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH &&
                        mode != INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
                    ) {
                        throw IllegalArgumentException("mode is invalid")
                    }
                    val pid = Binder.getCallingPid()
                    val uid = 0
                    val ident = Binder.clearCallingIdentity()
                    var result: Int = -1
                    try {
                        result = XposedHelpers.callMethod(
                            param.thisObject,
                            "nativeInjectInputEvent",
                            mPtr,
                            event,
                            displayId,
                            pid,
                            uid,
                            mode,
                            INJECTION_TIMEOUT_MILLIS,
                            FLAG_DISABLE_KEY_REPEAT
                        ) as Int
                    } finally {
                        Binder.restoreCallingIdentity(ident)
                    }
                    when(result) {
                        INPUT_EVENT_INJECTION_PERMISSION_DENIED -> {
                            XLog.d("Input event injection from pid $pid permission denied.")
                            throw SecurityException("Injecting to another application requires INJECT_EVENTS permission")
                        }
                        INPUT_EVENT_INJECTION_SUCCEEDED -> {
                            return true
                        }
                        INPUT_EVENT_INJECTION_TIMED_OUT -> {
                            XLog.d("Input event injection from pid $pid timed out.")
                            return false
                        }
                        else -> {
                            XLog.d("Input event injection from pid $pid failed.")
                            return false
                        }
                    }
                }
            }
        )*/
    }

    override fun handleLoadPackage(p0: XC_LoadPackage.LoadPackageParam) {
        if (p0.packageName != "android") return

        val classLoader = p0.classLoader
        // Android's internal WM classes change between releases.  A missing
        // class or signature must not abort the whole Xposed module; the
        // original implementation did exactly that on Android 14/15.
        when {
            Build.VERSION.SDK_INT >= 33 -> {
                XLog.d("hooking Android ${Build.VERSION.SDK_INT} task supervisor")
                runCatching { hookATS(classLoader) }
                    .onFailure { XLog.d("caller/display hook skipped: ${it.message}") }
                runCatching { hookATSOnTPlus(classLoader) }
                    .onFailure { XLog.d("Android 13+ non-resizable hook skipped: ${it.message}") }
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.R ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                runCatching { hookASS(classLoader) }
                    .onFailure { XLog.d("Android 10/11 hook skipped: ${it.message}") }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                runCatching { hookASSPreQ(classLoader) }
                    .onFailure { XLog.d("Android 8/9 hook skipped: ${it.message}") }
            }
        }
    }

    /**
     * Android 13 introduced Task/TaskDisplayArea and Android 14/15 changed
     * the overloads of handleNonResizableTaskIfNeeded.  Hook all overloads
     * and only touch the fields when they are present, instead of hard-coding
     * one method signature.
     */
    private fun hookATSOnTPlus(classLoader: ClassLoader) {
        val supervisor = XposedHelpers.findClass(
            "com.android.server.wm.ActivityTaskSupervisor", classLoader
        )
        val taskClass = XposedHelpers.findClass("com.android.server.wm.Task", classLoader)
        val displayAreaClass = XposedHelpers.findClass(
            "com.android.server.wm.TaskDisplayArea", classLoader
        )

        XposedBridge.hookAllMethods(
            supervisor,
            "handleNonResizableTaskIfNeeded",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        if (param.args.size < 3) return@runCatching
                        val task = param.args[0] ?: return@runCatching
                        val preferredArea = param.args[2] ?: return@runCatching
                        if (!taskClass.isInstance(task) || !displayAreaClass.isInstance(preferredArea)) {
                            return@runCatching
                        }
                        val displayContent = XposedHelpers.callMethod(task, "getDisplayContent")
                            ?: return@runCatching
                        val field = XposedHelpers.findField(displayAreaClass, "mDisplayContent")
                        field.set(preferredArea, displayContent)
                        XLog.d("hook Android 13+ non-resizable task")
                    }.onFailure {
                        // OEM builds may remove or rename these fields.
                        XLog.d("non-resizable callback skipped: ${it.message}")
                    }
                }
            }
        )
    }

    companion object {
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
        private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
        private const val INJECTION_TIMEOUT_MILLIS = 30 * 1000
        private const val FLAG_DISABLE_KEY_REPEAT = 0x08000000
        private const val INPUT_EVENT_INJECTION_SUCCEEDED = 0
        private const val INPUT_EVENT_INJECTION_PERMISSION_DENIED = 1
        private const val INPUT_EVENT_INJECTION_FAILED = 2
        private const val INPUT_EVENT_INJECTION_TIMED_OUT = 3
    }

    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                XposedBridge.hookAllMethods(activityThread, "systemMain", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { hookIMS() }
                            .onFailure { XLog.d("legacy input hook skipped: ${it.message}") }
                    }
                })
            }.onFailure {
                XLog.d("legacy zygote hook skipped: ${it.message}")
            }
        }
    }
}