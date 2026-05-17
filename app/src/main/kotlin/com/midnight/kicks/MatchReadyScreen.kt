package com.midnight.kicks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Both players are in the contract — match is ready to start. Today this
 * is a terminal screen with a stub CONTINUE that launches the Unity choice
 * phase only for [Player.P1] via the existing PvAI orchestrator (P2's
 * gameplay path is Phase 4 step 3). Once `playAsP1` / `playAsP2`
 * orchestrators land in `MatchManager`, both roles route through
 * `launchUnityChoicePhase` and the bridge.
 */
@Composable
fun MatchReadyScreen(
    address: String,
    role: Player,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            TopBackBar(label = "MATCH READY", onBack = onBack)

            Spacer(modifier = Modifier.height(80.dp))

            Text(
                "✓",
                color = Color(0xFF8CFF7B),
                fontSize = 64.sp,
                fontWeight = FontWeight.W200,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "BOTH PLAYERS IN",
                color = Color.White,
                fontSize = 14.sp,
                letterSpacing = 6.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                when (role) {
                    Player.P1 -> "You are P1"
                    Player.P2 -> "You are P2"
                },
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = address.shortAddress(),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(72.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(onClick = onContinue),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "CONTINUE",
                    color = Color.White,
                    fontSize = 14.sp,
                    letterSpacing = 4.sp,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "PvP gameplay loop = Phase 4 step 3 (next session).\nTap CONTINUE today to log the role/address.",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
