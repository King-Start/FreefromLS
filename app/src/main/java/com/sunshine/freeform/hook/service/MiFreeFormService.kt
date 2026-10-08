package com.sunshine.freeform.hook.service

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.view.View
import com.sunshine.freeform.hook.view.FreeFormHookWindow
import com.sunshine.freeform.hook.view.FreeFormHookWindowAbs
import com.sunshine.freeform.hook.IMiFreeFormService
import com.sunshine.freeform.hook.utils.HookFailException
import de.robv.android.xposed.XposedBridge

/**
 * @author sunshine
 * @date 2021/7/28
 */
@SuppressLint("PrivateApi")
class MiFreeFormService : IMiFreeFormService.Stub() {

    companion object {
        private const val MI_FREEFORM_PACKAGE_NAME = "com.sunshine.freeform"

        private val COMMAND_REGEX = Regex("^am start -n ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+) --user ([0-9]+) --display$")

        private var mClient: IMiFreeFormService? = null

        private var mUserContext: Context? = null

        @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
        fun getClient(): IMiFreeFormService? {
            if (mClient != null) return mClient
            return try {
                val getServiceMethod =
                    Class.forName("android.os.ServiceManager").getDeclaredMethod(
                        "getService",
                        String::class.java
                    )
                asInterface(
                    getServiceMethod.invoke(null, "user.mifreeform") as IBinder
                )
            } catch (e: Exception) {
                return null
            }
        }

        private fun getUserContext(): Context{
            return if (null != mUserContext) mUserContext!!
            else {
                val activityThread = Class.forName("android.app.ActivityThread")
                val currentActivityThread = activityThread.getMethod("currentActivityThread").invoke(null)
                val application = activityThread.getMethod("getApplication").invoke(currentActivityThread) as Application
                mUserContext = application.createPackageContext(MI_FREEFORM_PACKAGE_NAME, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                mUserContext!!
            }
        }
    }

    override fun startWithMiFreeForm(packageName: String, command: String, userId: Int) {
        // Service ini berjalan di system_server dan menjalankan perintah shell.
        // Hanya aplikasi Mi-Freeform yang boleh memanggil, dan perintah divalidasi
        // ketat lalu disusun ulang agar aplikasi lain tidak bisa menyuntikkan perintah.
        if (!isCallerMiFreeform()) {
            XposedBridge.log("Mi-Freeform: panggilan ditolak dari uid ${Binder.getCallingUid()}")
            return
        }
        val safeCommand = sanitizeCommand(packageName, command, userId) ?: run {
            XposedBridge.log("Mi-Freeform: perintah ditolak (format tidak valid)")
            return
        }
        try {
            FreeFormHookWindow(
                getUserContext(),
                packageName,
                safeCommand,
                userId
            )
        } catch (e: HookFailException) {

        }
    }

    private fun isCallerMiFreeform(): Boolean {
        val callerAppId = Binder.getCallingUid() % 100000
        return try {
            val uid = getUserContext().packageManager.getApplicationInfo(MI_FREEFORM_PACKAGE_NAME, 0).uid
            uid % 100000 == callerAppId
        } catch (e: Exception) {
            false
        }
    }

    private fun sanitizeCommand(packageName: String, command: String, userId: Int): String? {
        if (userId < 0) return null
        val match = COMMAND_REGEX.matchEntire(command.trim()) ?: return null
        val (pkg, cls, user) = match.destructured
        if (pkg != packageName || user.toIntOrNull() != userId) return null
        return "am start -n $pkg/$cls --user $userId --display"
    }
}