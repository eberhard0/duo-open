package com.duoopen.overlay

import android.content.Context

/** Full edition: the system-wide fold via [FoldOverlayService]. */
object OverlayFeature {
    const val AVAILABLE = true

    fun isEnabled(context: Context): Boolean = FoldOverlayService.isEnabled(context)

    /** Replays the effect over the current screen; false if the service isn't connected. */
    fun playDemo(): Boolean {
        val service = FoldOverlayService.instance ?: return false
        service.playDemo()
        return true
    }
}
