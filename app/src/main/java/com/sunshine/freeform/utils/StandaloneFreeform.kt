package com.sunshine.freeform.utils

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.sunshine.freeform.app.MiFreeform
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Mode tanpa Shizuku/LSPosed: memakai freeform bawaan ROM (cara yang sama dengan Taskbar
 * oleh farmerbb, Apache-2.0). Jendela digambar sistem; aplikasi hanya meminta
 * windowing mode freeform + ukuran lewat ActivityOptions.
 *
 * Syarat di perangkat: Opsi Pengembang -> "Enable freeform windows" (Android 7+).
 */
object StandaloneFreeform {
    const val SIZE_STANDARD = 0
    const val SIZE_LARGE = 1
    const val SIZE_HALF_LEFT = 2
    const val SIZE_HALF_RIGHT = 3
    const val SIZE_MAXIMIZED = 4

    private const val WINDOWING_MODE_FREEFORM = 5      // Android 9+
    private const val FREEFORM_WORKSPACE_STACK_ID = 2  // Android 7.x - 8.x

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true
        return runCatching {
            val resolver = context.contentResolver
            Settings.Global.getInt(resolver, "enable_freeform_support", 0) != 0 ||
                (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 &&
                    Settings.Global.getInt(resolver, "force_resizable_activities", 0) != 0)
        }.getOrDefault(false)
    }

    /** true bila permintaan peluncuran sudah dikirim ke sistem. */
    fun launch(context: Context, target: Intent, userId: Int = 0): Boolean {
        // Profil kerja/klon memerlukan startActivityAsUser (izin sistem); biarkan fallback.
        if (userId != 0 || !isSupported(context)) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            }
            val options = ActivityOptions.makeBasic()
            val intType = Int::class.javaPrimitiveType
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ActivityOptions::class.java.getMethod("setLaunchWindowingMode", intType)
                    .invoke(options, WINDOWING_MODE_FREEFORM)
            } else {
                ActivityOptions::class.java.getMethod("setLaunchStackId", intType)
                    .invoke(options, FREEFORM_WORKSPACE_STACK_ID)
            }
            options.setLaunchBounds(bounds(context, windowSize()))

            val intent = Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent, options.toBundle())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun windowSize(): Int = runCatching {
        MiFreeform.me.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .getInt("standalone_window_size", SIZE_STANDARD)
    }.getOrDefault(SIZE_STANDARD)

    @Suppress("DEPRECATION")
    private fun bounds(context: Context, size: Int): Rect {
        val dm = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val landscape = w > h
        val statusBar = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) context.resources.getDimensionPixelSize(it) else 0 }

        return when (size) {
            SIZE_LARGE -> Rect(w / 8, h / 8, w - w / 8, h - h / 8)
            SIZE_HALF_LEFT -> if (landscape) Rect(0, statusBar, w / 2, h) else Rect(0, statusBar, w, h / 2)
            SIZE_HALF_RIGHT -> if (landscape) Rect(w / 2, statusBar, w, h) else Rect(0, h / 2, w, h)
            SIZE_MAXIMIZED -> Rect(0, statusBar, w, h)
            else -> Rect(w / 4, h / 4, w - w / 4, h - h / 4)
        }
    }
}
