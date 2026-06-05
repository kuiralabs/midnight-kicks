package com.midnight.kicks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Visual variants for [KicksButton]. */
enum class KicksButtonStyle { Primary, Danger }

/**
 * The shared Kicks action button. Replaces the per-screen hand-rolled
 * `clickable` Boxes so every button gets, consistently:
 *  - **pressed-state feedback** (scale-down + scrim) — the bespoke Boxes used a
 *    bare `clickable` with no indication, so a tap looked like nothing happened.
 *  - a **centered loading spinner** that occupies the button at its normal size
 *    instead of swapping the label for a differently-sized indicator, so the
 *    button no longer changes width/height (the "jump") when it toggles.
 *
 * Full-width + fixed-height by default; pass a [modifier] to override the width.
 */
@Composable
fun KicksButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    loading: Boolean = false,
    style: KicksButtonStyle = KicksButtonStyle.Primary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val clickable = enabled && !loading
    // State-layer opacity follows the Material 3 ladder: press > focus > hover.
    val overlay by animateFloatAsState(
        targetValue = when {
            !clickable -> 0f
            pressed -> 0.12f
            focused -> 0.09f
            hovered -> 0.06f
            else -> 0f
        },
        label = "kicksButtonStateLayer",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && clickable) 0.97f else 1f,
        label = "kicksButtonPress",
    )

    val height = if (style == KicksButtonStyle.Danger) 48.dp else 56.dp
    val container = when {
        style == KicksButtonStyle.Danger -> Color.Transparent
        !enabled -> Color.White.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.20f)
    }
    val content = when {
        style == KicksButtonStyle.Danger -> KicksColors.Danger
        !enabled -> Color.White.copy(alpha = 0.40f)
        else -> Color.White
    }

    Box(
        modifier = modifier
            .scale(scale)
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .let {
                if (clickable) {
                    it.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    it
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // State-layer scrim on top of the container — animates across
        // hover / focus / pressed so the button always reacts visibly.
        if (overlay > 0.001f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = overlay)),
            )
        }
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = content,
            )
        } else {
            Text(
                text = label,
                color = content,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Interaction feedback for bespoke tappables that aren't full-width
 * [KicksButton]s — chips, icon buttons, list rows, text links. Wires its own
 * click and gives the element the full set of visible reactions:
 *  - **pressed** → scale-down + a stronger state-layer overlay,
 *  - **focused** (D-pad / keyboard) → a medium overlay,
 *  - **hovered** (mouse / stylus on large screens) → a soft overlay.
 *
 * The overlay opacities follow the Material 3 state-layer ladder
 * (press > focus > hover) so feedback reads as "designed", not ad hoc, and
 * animate in/out. Pass the element's [shape] so the overlay matches its
 * rounded corners. Apply EARLY in the chain so the whole element (background
 * included) scales and the overlay sits on top of it:
 *
 *   Modifier.kicksPressable(shape = RoundedCornerShape(10.dp)) { onTap() }
 *       .background(.., RoundedCornerShape(10.dp)).padding(..)
 */
@Composable
fun Modifier.kicksPressable(
    shape: Shape = RoundedCornerShape(12.dp),
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val overlayTarget = when {
        !enabled -> 0f
        pressed -> 0.14f
        focused -> 0.10f
        hovered -> 0.07f
        else -> 0f
    }
    val overlay by animateFloatAsState(targetValue = overlayTarget, label = "kicksStateLayer")
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        label = "kicksPressScale",
    )
    return this
        // HIG: guarantee a ≥48dp touch target even for small chips/links;
        // the visual stays compact and centered within the expanded target.
        .minimumInteractiveComponentSize()
        .scale(scale)
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (overlay > 0.001f) {
                drawRect(color = Color.White.copy(alpha = overlay))
            }
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
