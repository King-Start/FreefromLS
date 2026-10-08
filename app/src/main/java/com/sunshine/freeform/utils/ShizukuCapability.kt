package com.sunshine.freeform.utils

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Runtime capability check used by the optional no-Shizuku taskbar fallback. */
object ShizukuCapability {
    fun isAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
