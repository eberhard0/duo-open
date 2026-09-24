package com.duoopen.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.fold.isInnerPanel
import com.duoopen.overlay.OverlayState
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live wallpaper that plays the Duo frosted-glass fold whenever the inner
 * display is partly folded: the wallpaper image sits on the flat plane, the
 * moving half of the screen acts as a glass pane hinged at the crease, and the
 * image settles into focus as the hinge reaches flat.
 *
 * Three ways the fold is driven, best available wins:
 *  - a fine hinge angle sensor tracks the hand directly;
 *  - a stops-only sensor (0/90/180) plays a timed ease per stop change;
 *  - with no readable sensor at all (Samsung locks its fold angle to system
 *    apps), the fold plays as a timed frost-to-clear the moment the inner
 *    panel's surface appears, which is exactly when the phone opens.
 *
 * Draws only on hinge movement / surface changes / timed plays (no idle
 * frames). On the cover screen (tall, narrow) the image is drawn plain, and
 * while the full-screen overlay is running it steps aside so nothing is
 * frosted twice.
 */
class DuoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DuoEngine()

    private inner class DuoEngine : Engine() {
        private val scope = MainScope()
        private val context = this@DuoWallpaperService
        private val handler = Handler(Looper.getMainLooper())
        private val hinge = HingeAngleSource(context, ::onHingeAngle)
        private val foldShader: RuntimeShader? = DuoShader.create(context)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val follower = TiltFollower { tilt ->
            if (timedPlay && tilt <= 0f) endTimedPlay()
            draw()
        }

        private var config: DuoConfig = DuoSettings.config.value
        private var bitmap: Bitmap? = null
        private var bitmapVersion = -1L
        private var imageShader: BitmapShader? = null
        private var pxPerMm = DuoShader.pxPerMm(context)

        private var surfaceReady = false
        private var width = 0f
        private var height = 0f

        /** Which panel the surface last belonged to; null before the first surface. */
        private var lastPanelInner: Boolean? = null

        /** A timed (sensor-less or stops-only) play is in flight. */
        private var timedPlay = false

        private val release = Runnable {
            follower.tauS = TIMED_TAU_S
            follower.setTarget(0f)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            displayContext?.let { pxPerMm = DuoShader.pxPerMm(it) }
            // Listen for the engine's whole life, not just while visible: the
            // OnePlus Open's hinge HAL sends nothing on registration, so a
            // listener started at unfold time would miss a fast open entirely.
            // It's on-change, so it's silent unless the hinge actually moves.
            hinge.start()
            Log.i(TAG, "hinge: ${hinge.statusText()}")
            scope.launch {
                DuoSettings.config.collect { c ->
                    config = c
                    if (c.imageVersion != bitmapVersion) loadImage(c.imageVersion)
                    if (!timedPlay) follower.snap(tiltFor(hinge.lastAngle))
                    draw()
                }
            }
            scope.launch { OverlayState.running.collect { draw() } }
            scope.launch { WallpaperDemo.play.collect { playTimedFold() } }
        }

        override fun onDestroy() {
            handler.removeCallbacks(release)
            hinge.stop()
            follower.cancel()
            scope.cancel()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                if (!timedPlay) follower.snap(follower.target)
                draw()
            } else if (!timedPlay) {
                follower.cancel()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            surfaceReady = true
            width = w.toFloat()
            height = h.toFloat()
            displayContext?.let { pxPerMm = DuoShader.pxPerMm(it) }
            rebuildImageShader()
            val inner = isInner()
            val swappedIn = inner && lastPanelInner != true
            lastPanelInner = inner
            Log.i(TAG, "surface ${w}x${h} inner=$inner swappedIn=$swappedIn sensorFresh=${sensorFresh()}")
            if (swappedIn && !sensorFresh()) {
                // The inner panel just lit up and no sensor is telling us where
                // the hinge is: play the fold as a timed frost-to-clear.
                playTimedFold()
                return
            }
            // Folded -> unfolding swaps panels: start from the current hinge
            // pose instead of animating in from a stale value.
            if (!timedPlay) follower.snap(follower.target)
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            handler.removeCallbacks(release)
            follower.cancel()
            timedPlay = false
            super.onSurfaceDestroyed(holder)
        }

        private fun onHingeAngle(angle: Float) {
            if (hinge.isCoarse) {
                coarseStop(angle)
                return
            }
            if (timedPlay) endTimedPlay()
            val tilt = tiltFor(angle)
            if (isInner() && isVisible && surfaceReady) follower.setTarget(tilt) else follower.snap(tilt)
        }

        /**
         * A stops-only sensor can't be followed, so each stop change plays a
         * fixed ease: frost in on leaving flat, frost out on reaching it.
         */
        private fun coarseStop(angle: Float) {
            if (!isInner() || !surfaceReady) {
                follower.snap(0f)
                return
            }
            when {
                angle >= DuoShader.FLAT_HINGE -> easeTo(0f)
                angle <= DuoShader.PANEL_ON_HINGE -> follower.snap(0f)
                // A surface-swap play may already be running; don't restart it.
                else -> if (!timedPlay) easeTo(peakTilt())
            }
        }

        private fun easeTo(tilt: Float) {
            handler.removeCallbacks(release)
            timedPlay = true
            follower.tauS = TIMED_TAU_S
            follower.setTarget(tilt)
        }

        /** Frost fully, hold a beat, then clear — the fold seen from the fresh inner panel. */
        private fun playTimedFold() {
            if (!surfaceReady || !isInner()) {
                Log.i(TAG, "timed fold skipped: surfaceReady=$surfaceReady inner=${isInner()}")
                return
            }
            handler.removeCallbacks(release)
            timedPlay = true
            follower.snap(peakTilt())
            draw()
            handler.postDelayed(release, PEAK_HOLD_MS)
        }

        private fun endTimedPlay() {
            timedPlay = false
            handler.removeCallbacks(release)
            follower.tauS = TiltFollower.DEFAULT_TAU_S
        }

        /** True when a fine sensor reported within the last moment, so it can drive the fold itself. */
        private fun sensorFresh(): Boolean =
            hinge.activeSensor != null && !hinge.isCoarse && hinge.lastEventAgeMs() < SENSOR_FRESH_MS

        private fun peakTilt(): Float = DuoShader.tiltForHinge(DuoShader.PANEL_ON_HINGE, config)

        private fun tiltFor(angle: Float): Float =
            if (angle.isNaN()) 0f else DuoShader.tiltForHinge(angle, config)

        private fun isInner(): Boolean = displayContext?.display.isInnerPanel()

        private fun loadImage(version: Long) {
            bitmapVersion = version
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { WallpaperImage.load(context, version) }
                if (version != bitmapVersion) return@launch
                bitmap = bmp
                rebuildImageShader()
                draw()
            }
        }

        private fun rebuildImageShader() {
            val bmp = bitmap ?: return
            if (width <= 0f || height <= 0f) return
            val (scale, dx, dy) = WallpaperImage.centerCrop(bmp.width, bmp.height, width, height)
            // DECAL: samples outside the image are transparent, so the frost
            // fades to black at the edges like the original instead of smearing.
            imageShader = BitmapShader(bmp, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(dx, dy)
                })
            }
        }

        private fun draw() {
            if (!surfaceReady) return
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "lockHardwareCanvas failed", e)
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val image = imageShader ?: return
                val shader = foldShader
                val tilt = if (isInner() && !OverlayState.running.value) follower.current else 0f
                if (shader == null || tilt < DuoShader.FLAT_EPSILON) {
                    paint.shader = image
                } else {
                    val fold = DuoShader.centeredFold(width, height, config.foldSplitsLong)
                    DuoShader.setUniforms(shader, width, height, tilt, config, pxPerMm, fold)
                    shader.setInputShader("content", image)
                    paint.shader = shader
                }
                canvas.drawRect(0f, 0f, width, height, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private companion object {
        const val TAG = "DuoWallpaper"
        /** Slow ease for timed plays, so a play reads as a fold (≈ 450 ms). */
        const val TIMED_TAU_S = 0.12f
        /** Frost holds this long on the fresh panel before clearing. */
        const val PEAK_HOLD_MS = 250L
        /** A fine sensor reading younger than this means the sensor is driving. */
        const val SENSOR_FRESH_MS = 3_000L
    }
}
