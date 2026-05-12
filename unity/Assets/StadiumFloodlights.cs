using UnityEngine;

/// <summary>
/// Adds four corner floodlights aimed at the field center and tones the
/// existing Directional Light to a cool moonlit blue. Sells the "midnight"
/// aesthetic without requiring any asset imports.
///
/// Auto-attaches at scene load. Idempotent — re-running won't duplicate
/// fixtures.
/// </summary>
public class StadiumFloodlights : MonoBehaviour
{
    // Field center the floodlights aim at (matches camera LookAt target).
    private static readonly Vector3 FieldCenter = new Vector3(0f, 0f, 4f);

    // Four corner tower positions, set just outside the 50×40m playing surface
    // (GrassPitch enlarges Field to scale (5,1,4); pitch bounds X=±25, Z=-20..+20).
    // Y high enough to read as stadium-tower lights.
    private static readonly Vector3[] TowerPositions = new Vector3[]
    {
        new Vector3(-30f, 22f, -25f),
        new Vector3( 30f, 22f, -25f),
        new Vector3(-30f, 22f,  25f),
        new Vector3( 30f, 22f,  25f),
    };

    // Warm-white floodlight color, slightly desaturated.
    private static readonly Color FloodlightColor = new Color(1.0f, 0.96f, 0.86f, 1f);
    private const float FloodlightIntensity = 2.5f;
    private const float FloodlightRange = 60f;
    private const float FloodlightSpotAngle = 65f;

    // Moonlit override for the scene's existing Directional Light.
    private static readonly Color MoonlightColor = new Color(0.45f, 0.55f, 0.75f, 1f);
    private const float MoonlightIntensity = 0.35f;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<StadiumFloodlights>() != null) return;
        var go = new GameObject("StadiumFloodlights");
        go.AddComponent<StadiumFloodlights>();
        Debug.Log("[StadiumFloodlights] Auto-created");
    }

    void Start()
    {
        SpawnFloodlights();
        TintDirectionalLightToNight();
        SetAmbientToNight();
    }

    private void SpawnFloodlights()
    {
        for (int i = 0; i < TowerPositions.Length; i++)
        {
            var lightGO = new GameObject($"Floodlight_{i}");
            lightGO.transform.SetParent(transform, worldPositionStays: false);
            lightGO.transform.position = TowerPositions[i];
            lightGO.transform.LookAt(FieldCenter);

            var light = lightGO.AddComponent<Light>();
            light.type = LightType.Spot;
            light.color = FloodlightColor;
            light.intensity = FloodlightIntensity;
            light.range = FloodlightRange;
            light.spotAngle = FloodlightSpotAngle;
            light.shadows = LightShadows.Soft;
            light.shadowStrength = 0.6f;
        }
    }

    private void TintDirectionalLightToNight()
    {
        // The scene's existing key Directional Light is daytime-ish. Override
        // to a cool moonlight to match the floodlight pools.
        var directional = FindDirectionalLight();
        if (directional == null)
        {
            Debug.LogWarning("[StadiumFloodlights] No Directional Light found to tint");
            return;
        }
        directional.color = MoonlightColor;
        directional.intensity = MoonlightIntensity;
    }

    private static Light FindDirectionalLight()
    {
        var lights = FindObjectsByType<Light>(FindObjectsSortMode.None);
        foreach (var l in lights)
        {
            if (l.type == LightType.Directional) return l;
        }
        return null;
    }

    private static void SetAmbientToNight()
    {
        // Drop ambient so floodlight pools read against the dark surround.
        RenderSettings.ambientLight = new Color(0.06f, 0.08f, 0.12f, 1f);
    }
}
