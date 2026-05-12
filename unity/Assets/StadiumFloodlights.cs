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
    private static readonly Color FloodlightColor = new Color(1.0f, 0.97f, 0.88f, 1f);
    private const float FloodlightIntensity = 8f;     // Stadium lights are bright — make them feel it
    private const float FloodlightRange = 90f;
    private const float FloodlightSpotAngle = 100f;   // wide enough that the four cones overlap on the pitch

    // Moonlit override for the scene's existing Directional Light. Keep a
    // gentle fill — at near-zero you lose all the model surface shading.
    private static readonly Color MoonlightColor = new Color(0.55f, 0.65f, 0.85f, 1f);
    private const float MoonlightIntensity = 0.8f;

    // Ambient: low enough to read as night, high enough that nothing is true
    // black. Skybox-gradient via RenderSettings.ambientSkyColor below.
    private static readonly Color AmbientSkyColor    = new Color(0.10f, 0.13f, 0.20f, 1f);
    private static readonly Color AmbientEquatorColor = new Color(0.08f, 0.10f, 0.14f, 1f);
    private static readonly Color AmbientGroundColor = new Color(0.04f, 0.06f, 0.05f, 1f);
    // Camera background — replace the default black with a dark blue so the
    // void around the crowd ring reads as deep night sky.
    private static readonly Color NightSkyColor = new Color(0.03f, 0.05f, 0.10f, 1f);

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
        TintCameraBackground();
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
        // Use a three-color gradient so the field surface and the crowd ring
        // pick up slightly different ambient tones (sky tint above, grass tint
        // below). This makes the void around the floodlight pools feel like
        // night atmosphere rather than dead black.
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Trilight;
        RenderSettings.ambientSkyColor = AmbientSkyColor;
        RenderSettings.ambientEquatorColor = AmbientEquatorColor;
        RenderSettings.ambientGroundColor = AmbientGroundColor;
        RenderSettings.ambientIntensity = 1f;
    }

    private static void TintCameraBackground()
    {
        // Camera defaults to clear-to-black on URP. Replace with deep night
        // blue so the area above the crowd ring reads as sky.
        var cam = Camera.main;
        if (cam == null) return;
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = NightSkyColor;
    }
}
