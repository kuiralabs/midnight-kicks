using UnityEngine;

/// <summary>
/// A flat rectangular crowd wall placed behind the goal. Single quad, single
/// material, double-sided triangles so winding can never make it invisible.
/// The texture tiles horizontally so each spectator is large per screen pixel.
///
/// Drop a tileable stadium-crowd texture at
/// <c>Assets/Resources/StadiumCrowd.(png|jpg)</c>. Falls back to a flat
/// tinted wall if absent.
///
/// Self-attaches at scene load. No scene edits required.
/// </summary>
public class CrowdBackdrop : MonoBehaviour
{
    private const string CrowdTextureResource = "StadiumCrowd";

    // ── Wall placement ──
    // Goal line is at z=9.5; wall sits ~8m behind that. From the camera at
    // (0, 4.5, -13) the wall is ~30.5m forward and ~56m wide at frame edges
    // (FOV 55° vertical, 16:9 → ~86° horizontal). Wall extends past that so
    // the green pitch never shows behind the goal at the frame edges.
    private const float WallZ = 17.5f;
    private const float WallWidth = 80f;
    private const float WallHeight = 30f;
    private const float WallBaseY = 0f;

    // ── Texture tiling ──
    // Lower tiling = bigger per-spectator pixels = more visible detail per
    // face. 1.5 keeps the image wider than the wall (so the wall doesn't
    // show repeating seams in the camera frame) while letting each
    // spectator render at ~2× the previous resolution.
    private const float TilingU = 1.5f;
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
        // Build a flat quad facing -Z (toward the camera/field). Geometry is
        // double-sided (two triangles per side) so it renders regardless of
        // shader culling settings — no more invisible-back-face surprises.
        float halfW = WallWidth * 0.5f;
        var mesh = new Mesh { name = "CrowdWall" };

        var vertices = new Vector3[]
        {
            new Vector3(-halfW, WallBaseY,              0f),   // 0: bottom-left
            new Vector3( halfW, WallBaseY,              0f),   // 1: bottom-right
            new Vector3( halfW, WallBaseY + WallHeight, 0f),   // 2: top-right
            new Vector3(-halfW, WallBaseY + WallHeight, 0f),   // 3: top-left
        };
        var uvs = new Vector2[]
        {
            new Vector2(0f,      0f),
            new Vector2(TilingU, 0f),
            new Vector2(TilingU, TilingV),
            new Vector2(0f,      TilingV),
        };
        // Two triangles facing -Z (front, toward camera) + two facing +Z (back).
        // Front: 0→2→1, 0→3→2
        // Back:  0→1→2, 0→2→3   (reverse winding)
        var triangles = new int[]
        {
            0, 2, 1,
            0, 3, 2,
            0, 1, 2,
            0, 2, 3,
        };

        mesh.vertices = vertices;
        mesh.uv = uvs;
        mesh.triangles = triangles;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdWall");
        child.transform.SetParent(transform, worldPositionStays: false);
        child.transform.position = new Vector3(0f, 0f, WallZ);
        // Quad lives in the XY plane by construction; it already faces -Z.

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
            // Trilinear smooths mip transitions; anisotropic filtering keeps
            // the texture sharp when sampled at oblique angles — and the
            // crowd wall IS at an oblique angle from the off-axis FC25 cam.
            // Without this, Bilinear blurs spectator faces toward the
            // edges of the frame.
            crowdTex.filterMode = FilterMode.Trilinear;
            crowdTex.anisoLevel = 8;
            if (mat.HasProperty("_BaseMap")) mat.SetTexture("_BaseMap", crowdTex);
            if (mat.HasProperty("_MainTex")) mat.SetTexture("_MainTex", crowdTex);
        }
        else
        {
            mat.color = FallbackTint;
        }
        mr.sharedMaterial = mat;
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = false;
    }
}
