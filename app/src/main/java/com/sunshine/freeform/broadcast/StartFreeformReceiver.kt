package com.sunshine.freeform.broadcast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.util.Log
import com.sunshine.freeform.ui.freeform.FreeformService

/** Receives the launcher recents action and forwards a validated target. */
class StartFreeformReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", -1)
        val parcelable = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_INTENT)

        val target = when {
            parcelable is Intent -> Intent(parcelable)
            !packageName.isNullOrBlank() && !activityName.isNullOrBlank() -> {
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(packageName, activityName))
                    .setPackage(packageName)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
            }
            else -> null
        }

        if (target?.component == null && target?.`package`.isNullOrBlank()) {
            Log.w(TAG, "Ignoring recents action without a valid target")
            return
        }

        runCatching {
            context.startService(
                Intent(context, FreeformService::class.java)
                    .setAction(FreeformService.ACTION_START_INTENT)
                    .putExtra(Intent.EXTRA_INTENT, target)
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, target?.component)
                    .putExtra(Intent.EXTRA_USER, userId)
            )
        }.onFailure {
            Log.e(TAG, "Unable to start freeform service", it)
        }
    }

    companion object {
        private const val TAG = "Mi-FreeformReceiver"
    }
}
