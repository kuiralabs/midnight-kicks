using UnityEngine;

/// <summary>
/// Repositions the Main Camera to match the FC25-style penalty kick framing:
/// directly behind the shooter, slightly above, looking straight down the
/// play axis toward the goal.
///
/// Self-attaches at scene load. The script tweaks the camera once and then
/// stops touching it — other systems can move the camera afterward without
/// fighting this script.
/// </summary>
public class CameraSetup : MonoBehaviour
{
    // FC25-style penalty cam: 30° off-axis from the shooter (rotated around
    // his position) so the ball is visible past his shoulder — a straight-
    // behind cam hides the ball behind the shooter's body. High cherry-
    // picker framing so the whole penalty area, arc, ball, and goal are in
    // shot. Must match ShotManager.EstablishingCam exactly — otherwise this
    // sets the menu pose, then PlayRound snaps somewhere else on first kick.
    private static readonly Vector3 CameraPosition = new Vector3(7f, 7f, -15f);
    private static readonly Vector3 LookAtTarget   = new Vector3(0f, 0.5f,  4f);
    private const float FieldOfView = 60f;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<CameraSetup>() != null) return;
        var go = new GameObject("CameraSetup");
        go.AddComponent<CameraSetup>();
        Debug.Log("[CameraSetup] Auto-created");
    }

    void Start()
    {
        var cam = Camera.main;
        if (cam == null)
        {
            Debug.LogWarning("[CameraSetup] No Main Camera found");
            return;
        }

        cam.transform.position = CameraPosition;
        cam.transform.LookAt(LookAtTarget);
        cam.fieldOfView = FieldOfView;

        Debug.Log($"[CameraSetup] Camera positioned at {CameraPosition}, looking at {LookAtTarget}, FOV {FieldOfView}");
    }
}
