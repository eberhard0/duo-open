package com.duoopen.wallpaper

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * "Play the fold on the wallpaper now" requests from the app to the live
 * wallpaper engine. Both run in this process, so a shared flow is enough; the
 * engine plays a timed frost-to-clear as if the inner screen had just lit up.
 */
object WallpaperDemo {
    private val _play = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val play: SharedFlow<Unit> = _play

    fun request() {
        _play.tryEmit(Unit)
    }
}
