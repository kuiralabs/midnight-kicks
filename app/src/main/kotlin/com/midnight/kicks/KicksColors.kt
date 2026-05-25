package com.midnight.kicks

import androidx.compose.ui.graphics.Color

/**
 * Named colour tokens for Kicks screens — the shared palette (mirrors BBoard's
 * `Colors` object). Every colour the app draws lives here; composables
 * reference these instead of inlining `Color(0x…)` hex.
 *
 * Values are the exact hexes previously inlined, so this is a pure rename — no
 * visual change. `dp`/`sp` dimensions are intentionally left inline: they're
 * self-evident (`16.dp`) and don't form a reusable scale, unlike these opaque
 * colour values.
 */
object KicksColors {
    // ── Surfaces ──
    /** App background — near-black. */
    val Background = Color(0xFF0A0A0A)
    /** Muted dark-grey surface element (e.g. replay round chips). */
    val SurfaceMuted = Color(0xFF222222)
    /** ~90%-opaque scrim behind the full-screen replay overlay. */
    val OverlayScrim = Color(0xE6050505)
    /** ~80%-opaque scrim behind the in-match HUD banner. */
    val BannerScrim = Color(0xCC0A0A0A)

    // ── Accents / status ──
    /** Primary blue — in-flight tx, P1, informational lines. */
    val Accent = Color(0xFF64B5F6)
    /** Brighter blue — P1 badge on the resume picker. */
    val AccentBright = Color(0xFF4FB7FF)
    /** Teal — "your turn to pick". */
    val Picking = Color(0xFFB2DFDB)
    /** Amber — waiting on the opponent / in-progress highlight. */
    val Pending = Color(0xFFFFB74D)
    /** Yellow — actionable warning on the dark background (e.g. "forge your sigil first"). */
    val Warning = Color(0xFFFFC107)

    // ── Outcomes ──
    /** Green — match complete / success. */
    val Success = Color(0xFF81C784)
    /** Bright green — "ready" headers / P2 badge on the resume picker. */
    val SuccessBright = Color(0xFF8CFF7B)
    /** Dark green — a goal scored in the replay. */
    val Goal = Color(0xFF2E7D32)
    /** Brown — a save (no goal) in the replay. */
    val Save = Color(0xFF4E342E)

    // ── Danger ──
    /** Red — errors, the opponent/P2 accent, and destructive actions. */
    val Danger = Color(0xFFE57373)
    /** [Danger] at ~20% alpha — fill behind an armed destructive button. */
    val DangerSurface = Color(0x33E57373)
}
