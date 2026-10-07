package com.sunshine.freeform.service

import android.content.Intent
import android.service.quicksettings.TileService
import com.sunshine.freeform.ui.freeform.FreeformService

/** Quick Settings action to close every active freeform window. */
class ResetAllWindowsTileService : TileService() {
    override fun onClick() {
        super.onClick()
        startService(
            Intent(this, FreeformService::class.java)
                .setAction(FreeformService.ACTION_DESTROY_ALL_FREEFORM)
        )
    }
}
