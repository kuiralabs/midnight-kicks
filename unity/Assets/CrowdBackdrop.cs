using UnityEngine;

/// <summary>
/// Curved crowd backdrop placed behind the goal — not a full cylinder.
///
/// The full ring was wasteful: half of it sat behind the camera, and the
/// single-wrap texture had to stretch around the whole field which made
/// each spectator tiny. A curved wall behind the goal gives us:
///
///   - Tiled texture (3× horizontal) so each face / shirt is bigger
///   - One seamless region instead of a forced wrap seam
///   - 7 quads instead of 36, fewer draw calls
///   - All visible from the gameplay camera, no wasted geometry
///
/// Drop a tileable stadium-crowd texture at
/// <c>Assets/Resources/StadiumCrowd.(png|jpg)</c>. The script tiles it
/// horizontally across the curved wall.
///
/// Self-attaches at scene load. No scene edits required.
/// </summary>
public class CrowdBackdrop : MonoBehaviour
{
    private const string CrowdTextureResource = "StadiumCrowd";

    // ── Wall geometry ──
    // The wall is a gentle arc spanning ~120° centered behind the goal.
    // 7 panels gives enough curvature to feel wrapping without polygon waste.
    private const int PanelCount = 7;
    private const float ArcSpanDegrees = 120f;       // total horizontal sweep
    private const float Radius = 30f;                // distance from center to wall
    private const float WallHeight = 30f;            // generous so it fills FOV
    private const float BaseY = 0f;
    // Center of the arc — placed behind the goal at z=9.5 + ~20m, on x=0.
    // Camera at (-4, 1.7, -3) looking toward +Z sees this directly forward.
    private static readonly Vector3 ArcCenter = new Vector3(0f, 0f, 30f);

    // ── Texture tiling ──
    // Tile multiple times so the source image's detail is preserved per
    // spectator rather than stretched flat across a 60m wide wall.
    private const float TilingU = 3f;
    private const float TilingV = 1f;

    private static readonly Color FallbackTint = new Color(0.22f, 0.26f, 0.32f, 1f);

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
        var crowdTex = Resources.Load<Texture2D>(CrowdTextureResource);
        if (crowdTex == null)
        {
            Debug.LogWarning(
                $"[CrowdBackdrop] No Texture2D at Resources/{CrowdTextureResource}. " +
                "Drop a tileable stadium texture at unity/Assets/Resources/StadiumCrowd.png. " +
                "Falling back to flat tinted wall.");
        }
        BuildWall(crowdTex);
    }

    private void BuildWall(Texture2D crowdTex)
    {
        var mesh = new Mesh { name = "CrowdWall" };
        const int vertsPerPanel = 4;
        int vertCount = PanelCount * vertsPerPanel;
        var vertices = new Vector3[vertCount];
        var uvs = new Vector2[vertCount];
        var triangles = new int[PanelCount * 6];

        // The arc faces the field center (which sits at -Z direction from
        // ArcCenter — the goal and field are in -Z relative to it). We build
        // the arc around an axis pointing back toward the field.
        float arcSpan = ArcSpanDegrees * Mathf.Deg2Rad;
        float arcStart = Mathf.PI - arcSpan * 0.5f;   // centered on -Z direction

        for (int i = 0; i < PanelCount; i++)
        {
            float t0 = i / (float)PanelCount;
            float t1 = (i + 1) / (float)PanelCount;
            float a0 = arcStart + t0 * arcSpan;
            float a1 = arcStart + t1 * arcSpan;

            // Points on the arc relative to ArcCenter.
            Vector3 left  = new Vector3(Mathf.Cos(a0) * Radius, BaseY, Mathf.Sin(a0) * Radius);
            Vector3 right = new Vector3(Mathf.Cos(a1) * Radius, BaseY, Mathf.Sin(a1) * Radius);
            Vector3 up = Vector3.up * WallHeight;

            int v = i * vertsPerPanel;
            vertices[v + 0] = left;          uvs[v + 0] = new Vector2(t0 * TilingU, 0f);
            vertices[v + 1] = right;         uvs[v + 1] = new Vector2(t1 * TilingU, 0f);
            vertices[v + 2] = right + up;    uvs[v + 2] = new Vector2(t1 * TilingU, TilingV);
            vertices[v + 3] = left + up;     uvs[v + 3] = new Vector2(t0 * TilingU, TilingV);

            // Inside-facing winding (camera is on the -Z side of the wall,
            // looking +Z toward it).
            int tIdx = i * 6;
            triangles[tIdx + 0] = v + 0;
            triangles[tIdx + 1] = v + 2;
            triangles[tIdx + 2] = v + 1;
            triangles[tIdx + 3] = v + 0;
            triangles[tIdx + 4] = v + 3;
            triangles[tIdx + 5] = v + 2;
        }

        mesh.vertices = vertices;
        mesh.uv = uvs;
        mesh.triangles = triangles;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdWall");
        child.transform.SetParent(transform, worldPositionStays: false);
        child.transform.position = ArcCenter;

        var mf = child.AddComponent<MeshFilter>();
        mf.sharedMesh = mesh;

        var mr = child.AddComponent<MeshRenderer>();
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Texture")
                  ?? Shader.Find("Sprites/Default");
        var mat = new Material(shader) { name = "Crowd_Wall" };
        if (crowdTex != null)
        {
            crowdTex.wrapMode = TextureWrapMode.Repeat;
            crowdTex.filterMode = FilterMode.Bilinear;
            if (mat.HasProperty("_BaseMap")) mat.SetTexture("_BaseMap", crowdTex);
            if (mat.HasProperty("_MainTex")) mat.SetTexture("_MainTex", crowdTex);
        }
        else
        {
            mat.color = FallbackTint;
        }
        if (mat.HasProperty("_Cull")) mat.SetFloat("_Cull", 0f);
        mr.sharedMaterial = mat;
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = false;
    }
}
