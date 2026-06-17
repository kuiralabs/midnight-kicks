package com.midnight.kicks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Structured summary of the most-recently finished match, rendered as the menu's
 * "Last Match" card ([LastMatchCard]).
 *
 * Deliberately decoupled from the menu's transient `statusMessage` (errors /
 * warnings / progress): previously a win, a loss, and "Join failed" all shared
 * one amber line and read identically. Now the result gets its own colour-coded
 * surface and the amber line is free to speak only about what needs attention.
 *
 * Picks are the per-kick direction labels (L/C/R). They're absent for an
 * early-ended match (forfeit / cancel) and for the bootstrap "your prior match
 * finished" path, which only knows the scoreline — [hasPicks] / [hasScore] gate
 * the optional rows so the card renders correctly in every case.
 */
data class LastMatchSummary(
    val outcome: Outcome,
    val myScore: Int,
    val theirScore: Int,
    val myName: String,
    val theirName: String,
    val myPicks: List<String>,
    val theirPicks: List<String>,
) {
    enum class Outcome { WIN, LOSS, DRAW, FORFEIT_WIN, CANCELLED_REFUND }

    /** A full play-through carries a two-row pick recap; early-ended ones don't. */
    val hasPicks: Boolean get() = myPicks.isNotEmpty() && theirPicks.isNotEmpty()

    /** Forfeit / cancel outcomes have no meaningful scoreline. */
    val hasScore: Boolean
        get() = outcome == Outcome.WIN || outcome == Outcome.LOSS || outcome == Outcome.DRAW
}

/**
 * The menu's glass "Last Match" result card — matches [MenuPanel]'s glass
 * aesthetic, tinted by the outcome. Shows a colour-coded headline, the
 * scoreline (mine – theirs), and a two-row pick recap. Persists until the next
 * action clears it; [onDismiss] is the ✕.
 */
@Composable
fun LastMatchCard(
    summary: LastMatchSummary,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when (summary.outcome) {
        LastMatchSummary.Outcome.WIN,
        LastMatchSummary.Outcome.FORFEIT_WIN -> KicksColors.Success
        LastMatchSummary.Outcome.LOSS -> KicksColors.Danger
        LastMatchSummary.Outcome.DRAW -> KicksColors.Accent
        LastMatchSummary.Outcome.CANCELLED_REFUND -> KicksColors.Warning
    }
    val headline = when (summary.outcome) {
        LastMatchSummary.Outcome.WIN,
        LastMatchSummary.Outcome.FORFEIT_WIN -> "YOU WIN"
        LastMatchSummary.Outcome.LOSS -> "YOU LOST"
        LastMatchSummary.Outcome.DRAW -> "DRAW"
        LastMatchSummary.Outcome.CANCELLED_REFUND -> "CANCELLED"
    }
    val subtitle = when (summary.outcome) {
        LastMatchSummary.Outcome.FORFEIT_WIN -> "Opponent didn't respond in time"
        LastMatchSummary.Outcome.CANCELLED_REFUND -> "Your stake was refunded"
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LAST MATCH",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .kicksPressable(shape = RoundedCornerShape(percent = 50), onClick = onDismiss)
                    .padding(6.dp),
            ) {
                Text("✕", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                headline,
                color = accent,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            if (summary.hasScore) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "${summary.myScore} – ${summary.theirScore}",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.W300,
                    letterSpacing = 2.sp,
                )
            }
        }

        if (subtitle != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        }

        if (summary.hasPicks) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            Spacer(modifier = Modifier.height(12.dp))
            PickRecapRow(name = summary.myName, picks = summary.myPicks, nameColor = accent)
            Spacer(modifier = Modifier.height(8.dp))
            PickRecapRow(
                name = summary.theirName,
                picks = summary.theirPicks,
                nameColor = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun PickRecapRow(name: String, picks: List<String>, nameColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            color = nameColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )
        Text(
            picks.joinToString("  "),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
        )
    }
}
