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
    // Shooter ends its run-up at approximately (0, 0, -0.3); the ball sits
    // at z=0.2; the goal line is at z=9.5. A camera 8m behind the shooter,
    // at chest+ height, looking down the goal axis matches the penalty
    // broadcast framing.
    private static readonly Vector3 CameraPosition = new Vector3(0f, 2.2f, -8f);
    private static readonly Vector3 LookAtTarget   = new Vector3(0f, 1.4f, 9.5f);
    private const float FieldOfView = 55f;            // slightly narrower than default 60° — tighter framing

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
