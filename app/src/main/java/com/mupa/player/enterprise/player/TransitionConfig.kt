package com.mupa.player.enterprise.player

data class TransitionConfig(
    val enabled: Boolean,
    val mode: Mode,
    val durationMs: Long,
) {
    enum class Mode {
        NONE,
        FADE,
        CROSSFADE,
    }

    companion object {
        fun default(profile: PlaybackProfile): TransitionConfig {
            if (!profile.isHighEnd && profile.isLowEnd) {
                return TransitionConfig(enabled = true, mode = Mode.FADE, durationMs = 150L)
            }
            if (profile.isHighEnd) {
                return TransitionConfig(enabled = true, mode = Mode.CROSSFADE, durationMs = 300L)
            }
            return TransitionConfig(enabled = true, mode = Mode.FADE, durationMs = 250L)
        }
    }
}
