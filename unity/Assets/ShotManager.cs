using UnityEngine;
using UnityEngine.InputSystem;
using System.Collections;
using System.Collections.Generic;

public class ShotManager : MonoBehaviour
{
    public BallKicker ballKicker;
    public Keeper keeper;
    
    public Vector3 shooterStartPos = new Vector3(-1.2f, 0.5f, -3.2f);
    public Vector3 shooterKickPos = new Vector3(-0.3f, 0.5f, -0.2f);
    
    private GameObject shooter;
    private Animator shooterAnim;
    private Quaternion shooterInitialRotation;
    private bool isPlaying = false;
    private bool showResult = false;
    private string resultMessage = "";
    private string currentFeedback = "";
    private string currentScore = "P1: 0 - P2: 0";
    private Color feedbackColor = Color.white;

    private static readonly Vector3 GoalLookTarget = new Vector3(0, 1.4f, 11f);
    private static readonly Vector3 IntroStartCam = new Vector3(-6f, 2.5f, -1f);
    private static readonly Vector3 PlayCamPos = new Vector3(-4f, 1.7f, -3f);

    void Update()
    {
        // Debug: press T to test a single goal replay in Editor
        if (Keyboard.current != null && Keyboard.current.tKey.wasPressedThisFrame && !isPlaying)
        {
            List<RoundData> testRounds = new List<RoundData> {
                new RoundData { round = 1, shooter = "P1", shootDir = 1, keepDir = 0, result = "goal" }
            };
            StartCoroutine(PlayReplay(testRounds, null));
        }
    }

    private void CacheShooter()
    {
        if (shooter == null)
        {
            shooter = GameObject.Find("Shooter");
            if (shooter != null)
            {
                shooterAnim = shooter.GetComponent<Animator>();
                shooterInitialRotation = shooter.transform.rotation;
            }
        }

        if (ballKicker == null) ballKicker = FindAnyObjectByType<BallKicker>();
        if (keeper == null) keeper = FindAnyObjectByType<Keeper>();

        Debug.Log($"[ShotManager] CacheShooter: shooter={(shooter != null)} " +
                  $"shooterAnim={(shooterAnim != null)} " +
                  $"ballKicker={(ballKicker != null)} " +
                  $"keeper={(keeper != null)}");
    }

    public IEnumerator PlayReplay(List<RoundData> rounds, System.Action onComplete)
    {
        Debug.Log($"[ShotManager] PlayReplay START rounds={rounds.Count}");
        float startTime = Time.realtimeSinceStartup;

        isPlaying = true;
        showResult = false;
        CacheShooter();

        yield return StartCoroutine(PlayIntro());

        int p1Score = 0;
        int p2Score = 0;
        currentScore = "P1: 0 - P2: 0";

        for (int i = 0; i < rounds.Count; i++)
        {
            var round = rounds[i];
            Debug.Log($"[ShotManager] Round {i + 1}/{rounds.Count} shooter={round.shooter} " +
                      $"shootDir={round.shootDir} keepDir={round.keepDir} result={round.result}");
            yield return StartCoroutine(PlayRound(round));

            if (round.result == "goal")
            {
                if (round.shooter == "P1") p1Score++;
                else p2Score++;
            }

            currentScore = $"P1: {p1Score} - P2: {p2Score}";
            yield return new WaitForSeconds(1f);
        }

        if (p1Score > p2Score) resultMessage = "PLAYER 1 WINS!";
        else if (p2Score > p1Score) resultMessage = "PLAYER 2 WINS!";
        else resultMessage = "DRAW!";

        showResult = true;
        yield return new WaitForSeconds(4f);
        showResult = false;

        isPlaying = false;
        float duration = Time.realtimeSinceStartup - startTime;
        Debug.Log($"[ShotManager] PlayReplay END after {duration:F1}s, final={resultMessage}");
        onComplete?.Invoke();
    }

    private IEnumerator PlayIntro()
    {
        currentFeedback = "GET READY...";
        float duration = 2.2f;
        float elapsed = 0f;

        while (elapsed < duration)
        {
            elapsed += Time.deltaTime;
            float t = Mathf.SmoothStep(0f, 1f, elapsed / duration);
            Camera.main.transform.position = Vector3.Lerp(IntroStartCam, PlayCamPos, t);
            Camera.main.transform.LookAt(GoalLookTarget);
            yield return null;
        }

        Camera.main.transform.position = PlayCamPos;
        Camera.main.transform.LookAt(GoalLookTarget);
        currentFeedback = "";
    }

    private IEnumerator PlayRound(RoundData round)
    {
        if (ballKicker != null) ballKicker.ResetBall();
        if (keeper != null) keeper.Reset();
        if (shooter != null)
        {
            shooter.transform.position = shooterStartPos;
            shooter.transform.rotation = shooterInitialRotation;
            if (shooterAnim != null) shooterAnim.Play("Idle");
        }

        currentFeedback = "";
        yield return new WaitForSeconds(1.2f);

        if (shooterAnim != null) shooterAnim.Play("Run");

        float runTime = 0.9f;
        float elapsed = 0f;
        while (elapsed < runTime)
        {
            elapsed += Time.deltaTime;
            float t = elapsed / runTime;
            if (shooter != null)
                shooter.transform.position = Vector3.Lerp(shooterStartPos, shooterKickPos, t);
            yield return null;
        }
        if (shooter != null)
            shooter.transform.position = shooterKickPos;

        if (shooterAnim != null) shooterAnim.Play("Kick");
        yield return new WaitForSeconds(0.75f);

        if (ballKicker != null) ballKicker.KickTo(round.shootDir);
        yield return new WaitForSeconds(0.05f);
        if (keeper != null) keeper.Dive(round.keepDir, 0.8f);

        yield return new WaitForSeconds(1.2f);

        currentFeedback = round.result.ToUpper() + "!";
        feedbackColor = round.result == "goal" ? Color.green : Color.yellow;

        // Post-kick reactions. With only Idle / Run / Kick on the shooter and
        // Idle / Dive* / FallenIdle on the keeper, we layer procedural motion
        // on top of the existing states rather than introducing new clips.
        yield return StartCoroutine(PlayReaction(round.result));
    }

    /// <summary>
    /// Procedural reaction after the ball lands. Uses transform offsets on top
    /// of whatever animator state is currently playing so we don't need new
    /// animation clips. Runs for ~1.8s, matching the previous feedback hold.
    /// </summary>
    private IEnumerator PlayReaction(string result)
    {
        const float duration = 1.8f;
        float elapsed = 0f;

        Vector3 shooterBase = shooter != null ? shooter.transform.position : Vector3.zero;
        Quaternion shooterBaseRot = shooter != null ? shooter.transform.rotation : Quaternion.identity;
        bool isGoal = result == "goal";

        if (isGoal)
        {
            // Shooter celebrates: small jump + arms-up via Idle restart with
            // a vertical bob. Keeper stays in FallenIdle (already set by Keeper.cs).
            if (shooterAnim != null) shooterAnim.Play("Idle");
        }
        else
        {
            // Shooter hangs head: stay in Kick follow-through, lean forward.
            // Keeper holds save pose (FallenIdle).
        }

        while (elapsed < duration)
        {
            elapsed += Time.deltaTime;
            float t = elapsed / duration;

            if (shooter != null)
            {
                if (isGoal)
                {
                    // Two small celebratory hops in the 1.8s window.
                    float hop = Mathf.Abs(Mathf.Sin(t * Mathf.PI * 2f)) * 0.35f;
                    shooter.transform.position = shooterBase + new Vector3(0, hop, 0);
                }
                else
                {
                    // Gradual lean forward (head-down) up to ~20°.
                    float lean = Mathf.SmoothStep(0f, 20f, t);
                    shooter.transform.rotation = shooterBaseRot * Quaternion.Euler(lean, 0, 0);
                }
            }
            yield return null;
        }

        // Restore baseline so the next round starts clean.
        if (shooter != null)
        {
            shooter.transform.position = shooterBase;
            shooter.transform.rotation = shooterBaseRot;
        }
    }

    void OnGUI()
    {
        if (!isPlaying) return;

        var scoreStyle = new GUIStyle(GUI.skin.label);
        scoreStyle.fontSize = 32;
        scoreStyle.alignment = TextAnchor.UpperCenter;
        scoreStyle.normal.textColor = Color.white;
        GUI.Label(new Rect(0, 20, Screen.width, 50), currentScore, scoreStyle);

        if (showResult)
        {
            var resultStyle = new GUIStyle(GUI.skin.label);
            resultStyle.fontSize = 72;
            resultStyle.fontStyle = FontStyle.Bold;
            resultStyle.alignment = TextAnchor.MiddleCenter;
            resultStyle.normal.textColor = Color.white;
            GUI.Label(new Rect(0, 0, Screen.width, Screen.height), resultMessage, resultStyle);
        }
        else if (!string.IsNullOrEmpty(currentFeedback))
        {
            var feedbackStyle = new GUIStyle(GUI.skin.label);
            feedbackStyle.fontSize = 64;
            feedbackStyle.fontStyle = FontStyle.Bold;
            feedbackStyle.alignment = TextAnchor.MiddleCenter;
            feedbackStyle.normal.textColor = feedbackColor;
            GUI.Label(new Rect(0, Screen.height / 2 - 100, Screen.width, 100), currentFeedback, feedbackStyle);
        }
    }
}
