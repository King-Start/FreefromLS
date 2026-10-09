package com.sunshine.freeform.utils

import android.content.Context
import android.content.Intent
import com.sunshine.freeform.app.MiFreeform

/**
 * Memilih backend freeform sesuai pengaturan `backend_mode`.
 * AUTO = Shizuku/Sui -> LSPosed/Xposed -> freeform bawaan ROM -> fullscreen biasa.
 */
object BackendSelector {
    const val AUTO = 0
    const val SHIZUKU = 1
    const val XPOSED = 2
    const val STANDALONE = 3      // tanpa freeform: layar penuh saja
    const val ROM_FREEFORM = 4    // freeform bawaan ROM (tanpa Shizuku/LSPosed)

    fun mode(): Int = runCatching {
        MiFreeform.me.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .getInt("backend_mode", AUTO)
    }.getOrDefault(AUTO)

    fun allowsShizuku(): Boolean = mode().let { it == AUTO || it == SHIZUKU }

    fun allowsXposed(): Boolean = mode().let { it == AUTO || it == XPOSED }

    /** true bila Shizuku diizinkan oleh pengaturan DAN sudah diotorisasi. */
    fun useShizuku(): Boolean = allowsShizuku() && ShizukuCapability.isAuthorized()

    /** Coba luncurkan lewat jembatan Xposed; false bila tidak diizinkan/tidak aktif. */
    fun launchXposed(context: Context, target: Intent, userId: Int = 0): Boolean =
        allowsXposed() && XposedCapability.launch(context, target, userId)

    fun allowsRomFreeform(): Boolean = mode().let { it == AUTO || it == ROM_FREEFORM }

    /** Freeform bawaan ROM (cara Taskbar); false bila tidak diizinkan/tidak didukung ROM. */
    fun launchRomFreeform(context: Context, target: Intent, userId: Int = 0): Boolean =
        allowsRomFreeform() && StandaloneFreeform.launch(context, target, userId)

    /** Semua jalur tanpa Shizuku: LSPosed/Xposed, lalu freeform bawaan ROM. */
    fun launchFallback(context: Context, target: Intent, userId: Int = 0): Boolean =
        launchXposed(context, target, userId) || launchRomFreeform(context, target, userId)
}
