using UnityEngine;

/// <summary>
/// Brightens every material on the football_goal GameObject so the posts,
/// crossbar, and net read as bright white instead of the dingy off-grey
/// the imported FBX shipped with. No texture replacement needed.
///
/// Self-attaches at scene load. Idempotent.
/// </summary>
public class GoalEnhancer : MonoBehaviour
{
    // The GameObject name in SampleScene.unity. Note: the modeler named it
    // "footbal_goal" (one L) — matching their spelling.
    private const string GoalObjectName = "footbal_goal";

    // Bright white — exactly what FIFA broadcast goal posts read as under
    // floodlights or daylight.
    private static readonly Color GoalWhite = new Color(1f, 1f, 1f, 1f);

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<GoalEnhancer>() != null) return;
        var go = new GameObject("GoalEnhancer");
        go.AddComponent<GoalEnhancer>();
        Debug.Log("[GoalEnhancer] Auto-created");
    }

    void Start()
    {
        var goal = GameObject.Find(GoalObjectName);
        if (goal == null)
        {
            Debug.LogWarning($"[GoalEnhancer] No GameObject named '{GoalObjectName}' found");
            return;
        }

        var renderers = goal.GetComponentsInChildren<MeshRenderer>(includeInactive: true);
        int materialCount = 0;
        foreach (var r in renderers)
        {
            // Use .materials (not .sharedMaterial) so we get an instanced copy
            // and don't mutate the original FBX asset.
            foreach (var m in r.materials)
            {
                if (m == null) continue;
                if (m.HasProperty("_BaseColor")) m.SetColor("_BaseColor", GoalWhite); // URP/Lit
                if (m.HasProperty("_Color"))     m.SetColor("_Color", GoalWhite);     // built-in Standard
                // Drop metallic and lower smoothness — goal posts are matte painted steel.
                if (m.HasProperty("_Metallic"))   m.SetFloat("_Metallic", 0f);
                if (m.HasProperty("_Smoothness")) m.SetFloat("_Smoothness", 0.2f);
                if (m.HasProperty("_Glossiness")) m.SetFloat("_Glossiness", 0.2f);
                materialCount++;
            }
        }
        Debug.Log($"[GoalEnhancer] Whitened {materialCount} material(s) across {renderers.Length} renderer(s) on '{GoalObjectName}'");
    }
}
