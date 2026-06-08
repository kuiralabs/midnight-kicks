package com.midnight.kicks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kuira Labs brand splash, shown once on cold start before the menu. The logo
 * settles in during a beat of silence, the Bombonera drum double-strike hits
 * (with a small synced logo punch), then a beat of silence lets it breathe
 * before fading to the menu.
 *
 * **Timing:** ~1s silence → drum (2s) → ~2s silence (~5.5s total). The lead-in
 * silence makes the drum land; the tail lets the logo hold. [onFinished] hands
 * off to the menu and is where the lobby theme starts (see [KicksActivity]).
 */
@Composable
fun KuiraSplashScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val contentAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.84f) }

    LaunchedEffect(Unit) {
        // Logo settles in with an overshoot during the lead-in silence.
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)) }
        contentAlpha.animateTo(1f, tween(durationMillis = 500))
        delay(SILENCE_LEAD_MS) // ~1s of silence (0.5s fade-in + this) before the drum
        // The drum hits — give the logo a small synced punch so the silence reads
        // as loaded anticipation, not dead air.
        SplashSound.playDrum(context)
        launch {
            logoScale.animateTo(1.05f, tween(durationMillis = 110))
            logoScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
        delay(DRUM_MS)          // drum plays out
        delay(SILENCE_TAIL_MS)  // breathe, then fade to the menu
        contentAlpha.animateTo(0f, tween(durationMillis = 450))
        SplashSound.stop()
        onFinished()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = SplashBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The brand mark, presented as a rounded badge so its dark tile reads
            // as an intentional app-icon-style element rather than a square seam.
            Image(
                painter = painterResource(R.drawable.kuira_logo),
                contentDescription = "Kuira Labs",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(156.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .graphicsLayer { scaleX = logoScale.value; scaleY = logoScale.value },
            )
            Spacer(modifier = Modifier.height(30.dp))
            Text(
                text = "KUIRA LABS",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp,
            )
        }
    }
}

/** Lead-in silence after the ~0.5s fade-in, so the drum lands ~1s in. */
private const val SILENCE_LEAD_MS = 500L

/** Drum double-strike length (clip is 2.0s). */
private const val DRUM_MS = 2000L

/** Trailing silence — the logo holds before fading to the menu. */
private const val SILENCE_TAIL_MS = 2000L

/** Near-black to sit under the logo tile (close enough to read as one surface). */
private val SplashBackground = Color(0xFF0A0A0A)
