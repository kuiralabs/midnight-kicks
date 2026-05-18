package com.midnight.kicks

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON bridge between Kotlin and Unity (UaaL).
 *
 * Kotlin → Unity: via UnitySendMessage (static call to Unity's player)
 * Unity → Kotlin: via AndroidJavaObject callback (Unity calls our Java method)
 *
 * All messages are JSON strings with a "type" field for routing.
 */
object UnityBridge {

    private const val TAG = "UnityBridge"
    private const val GAME_OBJECT = "GameController"

    /** Callback from Unity — set by KicksViewModel to receive messages. */
    var onMessageFromUnity: ((String) -> Unit)? = null

    // ── Kotlin → Unity ──

    /**
     * Tell Unity to start the choice phase.
     *
     * @param round "regulation" (10 picks: 5 shoots + 5 keeps interleaved)
     *   or "suddenDeath" (2 picks: 1 shoot + 1 keep).
     * @param roles per-round role from THIS device's perspective. Each entry
     *   is "shoot" or "keep". The length defines how many picks Unity will
     *   ask for, so Kotlin and Unity agree on the count.
     *
     *   For V3 regulation:
     *     - P1 / PvAI: `[shoot,keep,shoot,keep,shoot,keep,shoot,keep,shoot,keep]`
     *       (P1 shoots the odd rounds 1,3,5,7,9)
     *     - P2:        `[keep,shoot,keep,shoot,keep,shoot,keep,shoot,keep,shoot]`
     *       (P2 shoots the even rounds 2,4,6,8,10)
     *
     *   For SD (single-pairing per round, same shape both sides):
     *     - Both:      `[shoot, keep]`
     *
     *   Unity labels each pick with `YOU SHOOT` / `YOU KEEP` off this array,
     *   so the player strategises per role. Kotlin uses the same array to
     *   bucket returned picks into shoots[5] + keeps[5] (regulation) or a
     *   single `{shoot, keep}` pair (SD).
     */
    fun sendChoicePhase(round: String, roles: List<String>) {
        require(roles.isNotEmpty()) { "roles must not be empty" }
        val json = JSONObject().apply {
            put("type", "choicePhase")
            put("round", round)
            put("roles", JSONArray().apply { roles.forEach { put(it) } })
        }
        sendToUnity("OnMessage", json.toString())
    }

    /** Tell Unity to play the regulation replay. */
    fun sendReplay(rounds: List<RoundResult>, p1Score: Int, p2Score: Int, winner: String?) {
        val json = JSONObject().apply {
            put("type", "replay")
            put("rounds", roundsToJson(rounds))
            put("finalScore", JSONObject().apply {
                put("p1", p1Score)
                put("p2", p2Score)
            })
            put("winner", winner ?: JSONObject.NULL)
        }
        sendToUnity("OnMessage", json.toString())
    }

    /** Tell Unity to play sudden death replay. */
    fun sendSuddenDeathReplay(rounds: List<RoundResult>, winner: String) {
        val json = JSONObject().apply {
            put("type", "suddenDeathReplay")
            put("rounds", roundsToJson(rounds))
            put("winner", winner)
        }
        sendToUnity("OnMessage", json.toString())
    }

    /** Tell Unity to show a status message (waiting, proving, etc.). */
    fun sendStatus(message: String) {
        val json = JSONObject().apply {
            put("type", "status")
            put("message", message)
        }
        sendToUnity("OnMessage", json.toString())
    }

    // ── Unity → Kotlin ──

    /**
     * Called by Unity's GameController via AndroidJavaObject.
     * This is the entry point for all messages FROM Unity.
     * Must be called on the Unity thread — posts to main thread via callback.
     */
    @JvmStatic
    fun receiveFromUnity(jsonString: String) {
        Log.d(TAG, "← Unity: $jsonString")
        onMessageFromUnity?.invoke(jsonString)
    }

    // ── Internal ──

    private fun sendToUnity(method: String, json: String) {
        Log.d(TAG, "→ Unity: $json")
        try {
            // UnitySendMessage is available when Unity player is running
            val unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer")
            val sendMethod = unityPlayerClass.getMethod(
                "UnitySendMessage", String::class.java, String::class.java, String::class.java
            )
            sendMethod.invoke(null, GAME_OBJECT, method, json)
        } catch (e: Exception) {
            Log.w(TAG, "Unity not available: ${e.message}")
        }
    }

    private fun roundsToJson(rounds: List<RoundResult>): JSONArray {
        return JSONArray().apply {
            rounds.forEach { round ->
                put(JSONObject().apply {
                    put("round", round.round)
                    put("shooter", round.shooter)
                    put("shootDir", round.shootDir)
                    put("keepDir", round.keepDir)
                    put("result", round.result)
                })
            }
        }
    }
}

/** A single round result for replay. */
data class RoundResult(
    val round: Int,
    val shooter: String, // "P1" or "P2"
    val shootDir: Int,   // 0=left, 1=center, 2=right
    val keepDir: Int,    // 0=left, 1=center, 2=right
    val result: String,  // "goal" or "save"
)
