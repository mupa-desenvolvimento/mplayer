package com.mupa.player.enterprise.player

import kotlinx.coroutines.CoroutineScope
import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PlayerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val layerA: LayerViews,
    private val layerB: LayerViews,
    private val telemetrySink: PlayerTelemetrySink? = null,
) {
    data class PlaybackItem(
        val id: String,
        val type: String,
        val file: File,
        val durationMs: Long?,
        val volume: Float?,
        val offsetStartMs: Long?,
        val offsetEndMs: Long?,
        val name: String? = null,
    )

    data class LayerViews(
        val container: View,
        val playerView: PlayerView,
        val imageView: ImageView,
    )

    private val profile = PlaybackProfile.detect(context.applicationContext)
    private val videoEngine = VideoEngine(context.applicationContext, profile, layerA, layerB)
    private val imageEngine = ImageEngine(context.applicationContext)
    private val transitionEngine = TransitionEngine(profile)
    private val playlistEngine =
        PlaylistEngine(
            context = context.applicationContext,
            scope = scope,
            profile = profile,
            layerA = layerA,
            layerB = layerB,
            videoEngine = videoEngine,
            imageEngine = imageEngine,
            transitionEngine = transitionEngine,
            telemetrySink = telemetrySink,
        )
    private val releasedRef = AtomicBoolean(false)

    fun getCurrentItemId(): String? = playlistEngine.getCurrentItemId()

    fun setTransitionConfig(config: TransitionConfig) {
        transitionEngine.setConfig(config)
    }

    fun start(items: List<PlaybackItem>) {
        if (releasedRef.get()) return
        playlistEngine.start(items)
    }

    fun setPlaylist(items: List<PlaybackItem>) {
        if (releasedRef.get()) return
        playlistEngine.setPlaylist(items)
    }

    fun pause() {
        if (releasedRef.get()) return
        playlistEngine.pause()
    }

    fun resume() {
        if (releasedRef.get()) return
        playlistEngine.resume()
    }

    fun stop() {
        if (releasedRef.get()) return
        playlistEngine.stop()
    }

    fun release() {
        if (!releasedRef.compareAndSet(false, true)) return
        playlistEngine.release()
    }
}
