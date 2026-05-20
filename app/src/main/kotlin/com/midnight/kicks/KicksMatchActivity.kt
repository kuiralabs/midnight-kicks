package com.midnight.kicks

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.unity3d.player.UnityPlayerGameActivity

/**
 * Kicks-specific Unity host. Extends [com.unity3d.player.UnityPlayerGameActivity]
 * and injects a [ComposeView] on top of Unity's surface so the in-match
 * status banner ([MatchHudOverlay]) stays visible during the long
 * blockchain waits.
 *
 * **Why subclass instead of embed-as-fragment:** Unity 6's
 * [com.unity3d.player.UnityPlayerGameActivity] extends
 * `GameActivity` (from `com.google.androidgamesdk`) which manages the
 * native window + GameActivity bridge thread. Subclassing keeps that
 * machinery untouched. Embedding Unity inside our own Activity would
 * mean fighting the Unity team's lifecycle assumptions for no
 * benefit — the overlay only needs to sit visually above the Unity
 * surface, not share a Compose tree with it.
 *
 * **How the overlay attaches:** `UnityPlayerGameActivity.onCreate` sets
 * the content view to a FrameLayout containing Unity's SurfaceView (via
 * `GameActivity.contentViewId`). We `addContentView` a transparent
 * `ComposeView` *after* `super.onCreate(...)`, which adds it as a
 * sibling of the Unity surface — same FrameLayout, but rendered above
 * it in z-order because it's added last.
 *
 * The ComposeView itself observes [MatchHud.state] via the overlay
 * Composable; no per-Activity wiring is needed. [MatchManager] is the
 * sole publisher.
 *
 * Manifest declares this activity with the same theme + flags as Unity's
 * default activity — see `app/src/main/AndroidManifest.xml`.
 */
class KicksMatchActivity : UnityPlayerGameActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Compose view that renders MatchHudOverlay. ViewCompositionStrategy
        // `DisposeOnViewTreeLifecycleDestroyed` ties the composition to
        // this Activity's lifecycle so flow collectors get cancelled when
        // the user backs out of the match — no leaked subscribers when
        // the user finishes a session and returns to the menu.
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Touch routing: leave the ComposeView clickable (default)
            // so the replay overlay's Continue button is reachable.
            // For regions that have NO Compose consumer (i.e. the HUD
            // banner Text and the empty transparent space when the
            // replay overlay is hidden), Compose's hit testing
            // returns false and Android forwards the touch to the
            // Unity surface beneath. Net behavior: Unity receives
            // gameplay touches as long as no Composable wants them.
            //
            // If a future Compose addition accidentally puts a
            // tap-eating modifier across the screen (e.g. a
            // `.clickable { }` on a full-screen Box), Unity input
            // dies silently — flag any such modifier in review.
            setContent {
                // Box stack: the full-screen replay overlay paints
                // first (when active it dims Unity and shows the
                // scoreboard), the HUD banner paints on top so its
                // status badge stays visible during transitions.
                // When the replay isn't showing, the Box has nothing
                // to draw beyond the HUD itself.
                Box(modifier = Modifier.fillMaxSize()) {
                    MatchReplayOverlay()
                    MatchHudOverlay()
                }
            }
        }

        // Attach as a sibling of Unity's surface. We size the
        // ComposeView MATCH_PARENT in both dimensions because the
        // replay overlay needs to occupy the full screen when active.
        // Touch routing is handled at the Compose level (see the
        // setContent block above) — by default ComposeView passes
        // touches through to Unity when no Composable consumes them.
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addContentView(composeView, params)
    }
}
