package com.sunshine.freeform.broadcast

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sunshine.freeform.ui.freeform.FreeformService
import com.sunshine.freeform.utils.ShizukuCapability

/**
 * Public, explicit launch API. It replaces the old launcher/recents hook for
 * callers that already know the target package/activity. The actual activity
 * launch still goes through FreeformService and Shizuku.
 */
class StartFreeformApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FreeformService.ACTION_START_FREEFORM_API) return

        val target = buildTargetIntent(context, intent) ?: run {
            Log.w(TAG, "Ignoring API request without a valid target activity")
            return
        }
        val component = target.component ?: return
        val userId = intent.getIntExtra(FreeformService.EXTRA_USER_ID, -1)

        if (!ShizukuCapability.isAuthorized()) {
            // The same API remains useful as a normal taskbar launcher when
            // Shizuku is unavailable; only the freeform display is skipped.
            runCatching {
                context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Log.e(TAG, "Unable to launch standalone target", it)
            }
            return
        }

        runCatching {
            context.startService(
                Intent(context, FreeformService::class.java)
                    .setAction(FreeformService.ACTION_START_INTENT)
                    .putExtra(Intent.EXTRA_INTENT, target)
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                    .putExtra(Intent.EXTRA_USER, userId)
            )
        }.onFailure {
            Log.e(TAG, "Unable to dispatch freeform API request", it)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildTargetIntent(context: Context, request: Intent): Intent? {
        val supplied = request.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        val packageName = request.getStringExtra(EXTRA_PACKAGE_NAME)
        val activityName = request.getStringExtra(EXTRA_ACTIVITY_NAME)

        val target = when {
            supplied != null -> Intent(supplied)
            !packageName.isNullOrBlank() && !activityName.isNullOrBlank() ->
                Intent(Intent.ACTION_MAIN).setComponent(ComponentName(packageName, activityName))
            else -> null
        } ?: return null

        val component = target.component ?: return null
        if (component.packageName != packageName && !packageName.isNullOrBlank()) return null

        // Resolve the component before handing it to the Shizuku service. This
        // prevents malformed or stale broadcast data from creating a broken
        // virtual display that can never be used.
        runCatching { context.packageManager.getActivityInfo(component, 0) }
            .getOrElse {
                Log.w(TAG, "Target activity is not installed: $component")
                return null
            }

        return target.apply {
            setPackage(component.packageName)
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
    }

    companion object {
        private const val TAG = "FreeformApiReceiver"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_ACTIVITY_NAME = "activityName"
    }
}
