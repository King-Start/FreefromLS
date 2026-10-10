package com.sunshine.freeform.utils

import android.content.Context
import android.content.Intent
import com.sunshine.freeform.broadcast.StartFreeformApiReceiver
import com.sunshine.freeform.ui.freeform.FreeformService

/**
 * Membuka aplikasi lewat API peluncur Mi-Freeform sendiri, sehingga mengikuti pilihan
 * backend (Shizuku -> LSPosed -> freeform ROM -> layar penuh). Memakai broadcast agar
 * aman dipanggil dari service latar belakang (taskbar).
 */
object AppLauncher {
    fun launch(context: Context, packageName: String, activityName: String, userId: Int = 0) {
        context.sendBroadcast(
            Intent(FreeformService.ACTION_START_FREEFORM_API)
                .setPackage(context.packageName)
                .putExtra(StartFreeformApiReceiver.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(StartFreeformApiReceiver.EXTRA_ACTIVITY_NAME, activityName)
                .putExtra(FreeformService.EXTRA_USER_ID, userId)
        )
    }
}
