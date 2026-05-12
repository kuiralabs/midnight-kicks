using UnityEngine;

/// <summary>
/// Builds a procedural low-poly crowd silhouette ring around the field at
/// scene load. A cylinder of dark teal-blue panels with a subtle vertical
/// gradient that reads as "stadium stand at night" from gameplay distance.
/// Zero asset imports.
///
/// Auto-attaches at scene load. Idempotent.
/// </summary>
public class CrowdBackdrop : MonoBehaviour
{
    // Geometry: dodecagon (12 panels) around the field center. Tall enough
    // to fill the horizon from the play camera at (-4, 1.7, -3).
    private const int PanelCount = 12;
    private const float Radius = 30f;
    private const float PanelHeight = 12f;
    private const float BaseY = 0f;
    private static readonly Vector3 RingCenter = new Vector3(0f, 0f, 4f);

    // Two-tone gradient (bottom darker, top slightly lighter) sells depth.
    private static readonly Color BottomColor = new Color(0.08f, 0.10f, 0.14f, 1f);
    private static readonly Color TopColor = new Color(0.16f, 0.20f, 0.26f, 1f);

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<CrowdBackdrop>() != null) return;
        var go = new GameObject("CrowdBackdrop");
        go.AddComponent<CrowdBackdrop>();
        Debug.Log("[CrowdBackdrop] Auto-created");
    }

    void Start()
    {
        BuildRing();
    }

    private void BuildRing()
    {
        var mesh = new Mesh { name = "CrowdRing" };
        int vertCount = PanelCount * 4;
        var vertices = new Vector3[vertCount];
        var triangles = new int[PanelCount * 6];
        var colors = new Color[vertCount];

        for (int i = 0; i < PanelCount; i++)
        {
            float a0 = (i / (float)PanelCount) * Mathf.PI * 2f;
            float a1 = ((i + 1) / (float)PanelCount) * Mathf.PI * 2f;

            Vector3 p0 = new Vector3(Mathf.Cos(a0) * Radius, BaseY, Mathf.Sin(a0) * Radius);
            Vector3 p1 = new Vector3(Mathf.Cos(a1) * Radius, BaseY, Mathf.Sin(a1) * Radius);
            Vector3 p2 = p1 + Vector3.up * PanelHeight;
            Vector3 p3 = p0 + Vector3.up * PanelHeight;

            int vBase = i * 4;
            vertices[vBase + 0] = p0;
            vertices[vBase + 1] = p1;
            vertices[vBase + 2] = p2;
            vertices[vBase + 3] = p3;

            colors[vBase + 0] = BottomColor;
            colors[vBase + 1] = BottomColor;
            colors[vBase + 2] = TopColor;
            colors[vBase + 3] = TopColor;

            // Wind triangles so the inside-facing face is visible from the
            // field. The ring center is the camera's working space.
            int tBase = i * 6;
            triangles[tBase + 0] = vBase + 0;
            triangles[tBase + 1] = vBase + 2;
            triangles[tBase + 2] = vBase + 1;
            triangles[tBase + 3] = vBase + 0;
            triangles[tBase + 4] = vBase + 3;
            triangles[tBase + 5] = vBase + 2;
        }

        mesh.vertices = vertices;
        mesh.triangles = triangles;
        mesh.colors = colors;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdRing");
        child.transform.SetParent(transform, worldPositionStays: false);
        child.transform.position = RingCenter;

        var mf = child.AddComponent<MeshFilter>();
        mf.sharedMesh = mesh;

        var mr = child.AddComponent<MeshRenderer>();
        // Vertex-color-aware unlit shader so the floodlights don't bleach the
        // stands and the gradient reads cleanly. URP-first, with built-in
        // fallbacks for editor previews on non-URP test projects.
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Color")
                  ?? Shader.Find("Sprites/Default");
        var mat = new Material(shader);
        mat.color = Color.white; // vertex colors drive look
        mr.sharedMaterial = mat;
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = false;
    }
}
