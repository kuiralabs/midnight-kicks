package com.midnight.kicks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen, centred "stage" shown during the long blockchain waits
 * (`TX_IN_FLIGHT` while a tx proves/submits, `WAITING_FOR_OPPONENT` while we're
 * blocked on the other player). Rendered above Unity by [KicksMatchActivity].
 *
 * **Why it exists.** Two reasons converge here:
 *  1. It **covers Unity's idle "Waiting for match…" IMGUI label** (small,
 *     centred, awkward) so all in-match copy reads as Compose. Once Unity is
 *     re-exported with that label removed this is purely additive.
 *  2. It **masks the wait** — instead of a frozen pitch, a breathing ball +
 *     calm status + elapsed counter make the 30–120 s commit/reveal feel like
 *     part of the tension, not a hang.
 *
 * The compact top banner ([MatchHudOverlay]) suppresses itself for these two
 * modes (see its `visible` gate) so the status isn't shown twice; this stage is
 * the sole presentation while waiting. PICKING is owned by [MatchPickerOverlay],
 * the replay by [MatchReplayOverlay], so this only ever fires between them.
 */
@Composable
fun MatchStageOverlay() {
    val state by MatchHud.state.collectAsState()
    val waiting = state.mode == MatchHud.Mode.WAITING_FOR_OPPONENT
    val active = waiting || state.mode == MatchHud.Mode.TX_IN_FLIGHT

    // Elapsed counter for the opponent-wait, reset whenever a new wait begins
    // (sessionEpochMs bumps on each state transition). Only ticks while waiting.
    var elapsed by remember(state.sessionEpochMs) { mutableIntStateOf(0) }
    LaunchedEffect(state.sessionEpochMs, state.mode) {
        if (state.mode == MatchHud.Mode.WAITING_FOR_OPPONENT) {
            while (true) {
                delay(1_000L)
                elapsed += 1
            }
        }
    }

    AnimatedVisibility(
        visible = active && state.primary != null,
        enter = fadeIn(tween(240)),
        exit = fadeOut(tween(180)),
    ) {
        StageContent(
            primary = state.primary.orEmpty(),
            secondary = state.secondary,
            waiting = waiting,
            elapsedSeconds = elapsed,
        )
    }
}

@Composable
private fun StageContent(
    primary: String,
    secondary: String?,
    waiting: Boolean,
    elapsedSeconds: Int,
) {
    val accent = if (waiting) KicksColors.Pending else KicksColors.Accent

    // Breathing ball: scale + alpha pulse so the screen is alive during the
    // longest waits (proof generation can sit ~15 s with no text change).
    val pulse = rememberInfiniteTransition(label = "stagePulse")
    val scale by pulse.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale",
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "ring",
    )

    val sub = secondary ?: if (waiting && elapsedSeconds >= 1) {
        "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    } else {
        null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Dim the idle pitch toward the centre to focus the status + hide
            // Unity's label; lighter at the very top so the goal still peeks.
            .background(
                Brush.verticalGradient(
                    0f to KicksColors.Background.copy(alpha = 0.30f),
                    0.45f to KicksColors.Background.copy(alpha = 0.80f),
                    1f to KicksColors.Background.copy(alpha = 0.88f),
                ),
            )
            // Modal while waiting — don't leak taps to Unity behind us.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Pulsing ball with a soft accent ring behind it.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale * 1.6f; scaleY = scale * 1.6f }
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = ringAlpha)),
                )
                Text("⚽", fontSize = 52.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
            }

            Spacer(Modifier.height(28.dp))
            Text(
                text = primary,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (sub != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = sub,
                    color = accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                )
            }
        }
    }
}
