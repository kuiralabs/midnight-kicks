package com.midnight.kicks

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

/**
 * Brand sting for the [KuiraSplashScreen]: a single Bombonera drum double-strike
 * (2.0s), played between two beats of silence (the splash composable owns the
 * timing — ~1s silence → drum → ~2s silence). Minimal on purpose; the silence
 * makes the drum land.
 *
 * Plain Android MediaPlayer; the splash runs in the Compose menu process, before
 * any Unity audio exists. Self-releasing; [stop] tears it down if the splash is
 * cut short.
 */
object SplashSound {
    private const val TAG = "SplashSound"
    private const val DRUM_VOLUME = 0.95f

    private var player: MediaPlayer? = null

    /** Play the drum double-strike once. */
    fun playDrum(context: Context) {
        val mp = MediaPlayer.create(context.applicationContext, R.raw.splash_drum) ?: run {
            Log.w(TAG, "could not create drum player")
            return
        }
        mp.setVolume(DRUM_VOLUME, DRUM_VOLUME)
        mp.setOnCompletionListener {
            runCatching { it.release() }
            if (player === it) player = null
        }
        player = mp
        runCatching { mp.start() }
    }

    fun stop() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}
