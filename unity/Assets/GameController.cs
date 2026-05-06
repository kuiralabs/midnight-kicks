using UnityEngine;
using System.Collections;
using System.Collections.Generic;

/// <summary>
/// Bridge between Kotlin (UaaL host) and Unity.
///
/// Receives JSON messages from Kotlin via UnitySendMessage("GameController", "OnMessage", json).
/// Sends JSON back to Kotlin via AndroidJavaObject callback.
///
/// Message types received:
///   choicePhase — player picks 5 directions
///   replay — play back regulation results
///   suddenDeathReplay — play back sudden death results
///   status — show a status message
///
/// Message types sent:
///   choicesLocked — player confirmed 5 choices
///   replayComplete — replay animation finished
/// </summary>
public class GameController : MonoBehaviour
{
    // Auto-create on scene load — no need to add to scene manually.
    // UnitySendMessage requires a GameObject named "GameController".
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void AutoCreate()
    {
        if (FindFirstObjectByType<GameController>() != null) return;
        var go = new GameObject("GameController");
        go.AddComponent<GameController>();
        DontDestroyOnLoad(go);
        Debug.Log("[GameController] Auto-created");

        // Disable the old BallKicker so it doesn't interfere
        var oldKicker = FindFirstObjectByType<BallKicker>();
        if (oldKicker != null)
        {
            oldKicker.enabled = false;
            Debug.Log("[GameController] Disabled old BallKicker");
        }
    }

    // Player's 5 choices for the current round
    private int[] choices = new int[5];
    private int currentChoice = 0;
    private bool inChoicePhase = false;
    private bool waitingForMessage = true; // Start in waiting state
    private string currentRound = "regulation";
    private string playerRole = "shooter";

    // Replay state
    private bool inReplay = false;
    private List<RoundData> replayRounds = new List<RoundData>();
    private int replayIndex = 0;

    /// <summary>
    /// Entry point for messages from Kotlin.
    /// Called via UnitySendMessage("GameController", "OnMessage", jsonString).
    /// </summary>
    public void OnMessage(string jsonString)
    {
        Debug.Log($"[GameController] Received: {jsonString}");

        var json = JsonUtility.FromJson<BridgeMessage>(jsonString);

        switch (json.type)
        {
            case "choicePhase":
                StartChoicePhase(jsonString);
                break;
            case "replay":
                StartReplay(jsonString);
                break;
            case "suddenDeathReplay":
                StartSuddenDeathReplay(jsonString);
                break;
            case "status":
                var statusMsg = JsonUtility.FromJson<StatusMessage>(jsonString);
                Debug.Log($"[GameController] Status: {statusMsg.message}");
                break;
            default:
                Debug.LogWarning($"[GameController] Unknown message type: {json.type}");
                break;
        }
    }

    // ── Choice Phase ──

    private void StartChoicePhase(string json)
    {
        var msg = JsonUtility.FromJson<ChoicePhaseMessage>(json);
        currentRound = msg.round;
        playerRole = msg.playerRole;
        currentChoice = 0;
        choices = new int[5];
        inChoicePhase = true;
        waitingForMessage = false;

        Debug.Log($"[GameController] Choice phase: round={currentRound}, role={playerRole}");
    }

    void OnGUI()
    {
        // Waiting for Kotlin to send a message
        if (!inChoicePhase && !inReplay)
        {
            var style = new GUIStyle(GUI.skin.label);
            style.fontSize = 24;
            style.alignment = TextAnchor.MiddleCenter;
            style.normal.textColor = Color.white;
            GUI.Label(new Rect(0, Screen.height / 2 - 20, Screen.width, 40),
                waitingForMessage ? "Waiting for match..." : "Ready", style);
            return;
        }

        if (!inChoicePhase) return;

        float btnWidth = Screen.width / 4f;
        float btnHeight = 80f;
        float y = Screen.height - 200f;

        // Show current round number
        var labelStyle = new GUIStyle(GUI.skin.label);
        labelStyle.fontSize = 20;
        labelStyle.alignment = TextAnchor.MiddleCenter;
        labelStyle.normal.textColor = Color.white;
        GUI.Label(
            new Rect(Screen.width / 2 - 150, y - 80, 300, 40),
            $"Round {currentChoice + 1} / 5  ({playerRole})",
            labelStyle
        );

        if (GUI.Button(new Rect(Screen.width / 2 - btnWidth * 1.5f, y, btnWidth, btnHeight), "LEFT"))
            MakeChoice(0);

        if (GUI.Button(new Rect(Screen.width / 2 - btnWidth / 2, y, btnWidth, btnHeight), "CENTER"))
            MakeChoice(1);

        if (GUI.Button(new Rect(Screen.width / 2 + btnWidth / 2, y, btnWidth, btnHeight), "RIGHT"))
            MakeChoice(2);

        // Show choices made so far
        string choicesSoFar = "";
        for (int i = 0; i < currentChoice; i++)
        {
            choicesSoFar += choices[i] == 0 ? "L " : choices[i] == 1 ? "C " : "R ";
        }
        GUI.Label(
            new Rect(Screen.width / 2 - 100, y + btnHeight + 10, 200, 40),
            choicesSoFar
        );
    }

    private void MakeChoice(int direction)
    {
        choices[currentChoice] = direction;
        currentChoice++;

        Debug.Log($"[GameController] Choice {currentChoice}: {direction}");

        if (currentChoice >= 5)
        {
            inChoicePhase = false;
            SendChoicesToKotlin();
        }
    }

    private void SendChoicesToKotlin()
    {
        string json = $"{{\"type\":\"choicesLocked\",\"choices\":[{choices[0]},{choices[1]},{choices[2]},{choices[3]},{choices[4]}]}}";
        Debug.Log($"[GameController] Sending choices: {json}");
        SendToKotlin(json);
    }

    // ── Replay ──

    private void StartReplay(string json)
    {
        // Parse rounds manually (JsonUtility doesn't handle nested arrays well)
        Debug.Log($"[GameController] Starting replay");
        inReplay = true;
        // TODO: parse rounds from JSON, animate each round
        // For now, just send replayComplete after a delay
        StartCoroutine(SimulateReplay());
    }

    private void StartSuddenDeathReplay(string json)
    {
        Debug.Log($"[GameController] Starting sudden death replay");
        inReplay = true;
        StartCoroutine(SimulateReplay());
    }

    private IEnumerator SimulateReplay()
    {
        // Placeholder: wait 3 seconds then signal complete
        // TODO: animate each round with ball physics
        yield return new WaitForSeconds(3f);
        inReplay = false;

        string json = "{\"type\":\"replayComplete\"}";
        SendToKotlin(json);
    }

    // ── Kotlin Communication ──

    private void SendToKotlin(string json)
    {
        Debug.Log($"[GameController] → Kotlin: {json}");

#if UNITY_ANDROID && !UNITY_EDITOR
        try
        {
            using (var bridge = new AndroidJavaClass("com.midnight.kicks.UnityBridge"))
            {
                bridge.CallStatic("receiveFromUnity", json);
            }
        }
        catch (System.Exception e)
        {
            Debug.LogError($"[GameController] Failed to send to Kotlin: {e.Message}");
        }
#else
        Debug.Log($"[GameController] (Editor mode) Would send to Kotlin: {json}");
#endif
    }
}

// ── JSON message types ──

[System.Serializable]
public class BridgeMessage
{
    public string type;
}

[System.Serializable]
public class ChoicePhaseMessage
{
    public string type;
    public string round;
    public string playerRole;
}

[System.Serializable]
public class StatusMessage
{
    public string type;
    public string message;
}

[System.Serializable]
public class RoundData
{
    public int round;
    public string shooter;
    public int shootDir;
    public int keepDir;
    public string result;
}
