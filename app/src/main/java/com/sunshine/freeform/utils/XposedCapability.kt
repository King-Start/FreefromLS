package com.sunshine.freeform.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sunshine.freeform.hook.IMiFreeFormService

/**
 * Optional LSPosed/Xposed bridge.
 *
 * The bridge is deliberately discovered at runtime. A normal installation
 * does not need Xposed, and a device without the module simply falls back to
 * the standalone taskbar/fullscreen launcher path.
 */
object XposedCapability {
    private const val TAG = "XposedCapability"
    private const val SERVICE_NAME = "user.mifreeform"

    private var cachedClient: IMiFreeFormService? = null

    fun isAvailable(): Boolean = client() != null

    /**
     * Ask the optional system-process Xposed service to create a freeform
     * window. Returns false when LSPosed/Xposed is not active, so callers can
     * continue with the non-Shizuku fullscreen fallback.
     */
    fun launch(
        context: Context,
        target: Intent,
        userId: Int = 0
    ): Boolean {
        val component = target.component ?: return false
        val service = client() ?: return false
        val command = buildCommand(component, userId)
        return runCatching {
            service.startWithMiFreeForm(component.packageName, command, userId)
            true
        }.onFailure {
            Log.w(TAG, "Xposed freeform bridge unavailable", it)
            cachedClient = null
        }.getOrDefault(false)
    }

    private fun buildCommand(component: ComponentName, userId: Int): String {
        return "am start -n ${component.packageName}/${component.className} --user $userId --display"
    }

    private fun client(): IMiFreeFormService? {
        cachedClient?.let { return it }
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, SERVICE_NAME) as? IBinder ?: return null
            IMiFreeFormService.Stub.asInterface(binder)?.also { cachedClient = it }
        }.onFailure {
            // Hidden APIs are unavailable in an ordinary process on some ROMs;
            // that is a normal condition and must not break taskbar fallback.
            Log.d(TAG, "Xposed service not found", it)
        }.getOrNull()
    }
}
