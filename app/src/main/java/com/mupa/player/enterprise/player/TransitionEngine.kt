package com.mupa.player.enterprise.player

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

internal class TransitionEngine(private val profile: PlaybackProfile) {
    private val configRef = AtomicReference(TransitionConfig.default(profile))

    fun setConfig(config: TransitionConfig) {
        configRef.set(normalize(config))
    }

    fun getConfig(): TransitionConfig = configRef.get()

    fun effectiveDurationMs(): Long {
        val cfg = configRef.get()
        if (!cfg.enabled || cfg.mode == TransitionConfig.Mode.NONE) return 0L
        return cfg.durationMs.coerceAtLeast(0L)
    }

    private fun normalize(config: TransitionConfig): TransitionConfig {
        val allowed = setOf(150L, 200L, 250L, 300L, 400L, 500L)
        val d =
            if (config.durationMs in allowed) config.durationMs
            else TransitionConfig.default(profile).durationMs

        val capped =
            if (profile.isLowEnd) d.coerceAtMost(200L)
            else d

        val mode =
            if (!config.enabled) TransitionConfig.Mode.NONE
            else config.mode

        return config.copy(mode = mode, durationMs = capped)
    }

    suspend fun swap(from: View, to: View, durationMs: Long) {
        from.animate().cancel()
        to.animate().cancel()

        if (durationMs <= 0L) {
            to.visibility = View.VISIBLE
            to.alpha = 1f
            from.alpha = 0f
            from.visibility = View.INVISIBLE
            return
        }

        val interp = AccelerateDecelerateInterpolator()
        to.visibility = View.VISIBLE
        to.alpha = 0f
        to.animate().alpha(1f).setDuration(durationMs).setInterpolator(interp).start()
        from.animate().alpha(0f).setDuration(durationMs).setInterpolator(interp).start()
        delay(durationMs)
        from.visibility = View.INVISIBLE
    }

    suspend fun fadeThrough(from: View, to: View, durationMs: Long) {
        val d = durationMs.coerceAtLeast(0L)
        if (d <= 0L) {
            swap(from, to, 0L)
            return
        }

        val minAlpha = if (profile.isLowEnd) 0.22f else 0.16f
        val half = (d / 2L).coerceAtLeast(1L)

        from.animate().cancel()
        to.animate().cancel()

        to.visibility = View.VISIBLE
        to.alpha = minAlpha

        val interp = AccelerateDecelerateInterpolator()
        from.animate().alpha(minAlpha).setDuration(half).setInterpolator(interp).start()
        delay(half)

        from.alpha = 0f
        from.visibility = View.INVISIBLE

        to.alpha = minAlpha
        to.animate().alpha(1f).setDuration(half).setInterpolator(interp).start()
        delay(half)
    }

    fun imageFadeMs(durationMs: Long?): Long {
        val d = (durationMs ?: 8000L).coerceAtLeast(200L)
        return profile.imageCrossfadeMs.coerceAtMost(d)
    }
}
