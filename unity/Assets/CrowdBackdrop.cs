using UnityEngine;

/// <summary>
/// Procedurally generated stadium stands around the pitch.
///
/// Approach: build a 36-panel cylinder around the field and texture it with
/// a procedurally generated 1024×512 stadium texture that contains
/// terraced rows, section breaks, per-pixel crowd noise, and sparse phone
/// lights. That gives the eye real visual density — section/row/spectator
/// detail at every distance — instead of a flat dark wall.
///
/// No models, no scene edits. Self-attaches at scene load.
/// </summary>
public class CrowdBackdrop : MonoBehaviour
{
    // ── Ring geometry ──
    private const int PanelCount = 36;
    private const float Radius = 45f;
    private const float PanelHeight = 22f;
    private const float BaseY = 0f;
    private static readonly Vector3 RingCenter = new Vector3(0f, 0f, 4f);

    // ── Texture dimensions ──
    private const int TexWidth = 1024;     // wraps once around the ring
    private const int TexHeight = 512;     // bottom→top of stand
    private const int RowCount = 28;       // ≈ visible seat rows
    private const int SectionCount = 16;   // ≈ aisles separating sections
    private const float UpperDeckDarken = 0.80f;
    private const float RowGapDarken = 0.45f;
    private const float AisleDarken = 0.55f;
    private const float CrowdNoiseAmplitude = 0.20f;
    private const float PhoneLightProbability = 0.0020f;
    private const float SignProbability = 0.0001f;       // very rare, big bright lit panels

    // Section base colors — muted, slightly bluish (away-team merch + nondescript clothing
    // averages to drab in low light). One brighter highlight section per ring.
    private static readonly Color[] SectionColors = new[]
    {
        new Color(0.18f, 0.22f, 0.30f),
        new Color(0.22f, 0.24f, 0.32f),
        new Color(0.16f, 0.18f, 0.24f),
        new Color(0.20f, 0.22f, 0.28f),
        new Color(0.24f, 0.20f, 0.22f),  // hint of warmer tone (red merch)
        new Color(0.18f, 0.22f, 0.30f),
        new Color(0.20f, 0.24f, 0.30f),
        new Color(0.22f, 0.26f, 0.32f),
    };

    private static readonly Color[] PhoneLightPalette = new[]
    {
        new Color(1.00f, 0.95f, 0.70f, 1f),  // warm white
        new Color(0.90f, 0.95f, 1.00f, 1f),  // cool white
        new Color(0.50f, 0.75f, 1.00f, 1f),  // blue screen
        new Color(1.00f, 0.75f, 0.40f, 1f),  // amber safety light
    };

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
        var stadiumTex = GenerateStadiumTexture();
        BuildRing(stadiumTex);
    }

    /// <summary>
    /// Build the cylinder mesh with proper UVs and apply the generated
    /// stadium texture. Two stacked quads per panel so the texture gets
    /// some perspective foreshortening hint near the bottom (closer rows
    /// are slightly compressed in V).
    /// </summary>
    private void BuildRing(Texture2D tex)
    {
        var mesh = new Mesh { name = "CrowdRing" };
        const int vertsPerPanel = 6;
        int vertCount = PanelCount * vertsPerPanel;
        var vertices = new Vector3[vertCount];
        var uvs = new Vector2[vertCount];
        var triangles = new int[PanelCount * 12];

        for (int i = 0; i < PanelCount; i++)
        {
            float a0 = (i / (float)PanelCount) * Mathf.PI * 2f;
            float a1 = ((i + 1) / (float)PanelCount) * Mathf.PI * 2f;
            float u0 = i / (float)PanelCount;
            float u1 = (i + 1) / (float)PanelCount;

            Vector3 left = new Vector3(Mathf.Cos(a0) * Radius, BaseY, Mathf.Sin(a0) * Radius);
            Vector3 right = new Vector3(Mathf.Cos(a1) * Radius, BaseY, Mathf.Sin(a1) * Radius);
            Vector3 midOffset = Vector3.up * (PanelHeight * 0.5f);
            Vector3 topOffset = Vector3.up * PanelHeight;

            int v = i * vertsPerPanel;
            vertices[v + 0] = left;             uvs[v + 0] = new Vector2(u0, 0f);
            vertices[v + 1] = right;            uvs[v + 1] = new Vector2(u1, 0f);
            vertices[v + 2] = left + midOffset; uvs[v + 2] = new Vector2(u0, 0.5f);
            vertices[v + 3] = right + midOffset;uvs[v + 3] = new Vector2(u1, 0.5f);
            vertices[v + 4] = left + topOffset; uvs[v + 4] = new Vector2(u0, 1f);
            vertices[v + 5] = right + topOffset;uvs[v + 5] = new Vector2(u1, 1f);

            int t = i * 12;
            triangles[t + 0] = v + 0; triangles[t + 1] = v + 3; triangles[t + 2] = v + 1;
            triangles[t + 3] = v + 0; triangles[t + 4] = v + 2; triangles[t + 5] = v + 3;
            triangles[t + 6] = v + 2; triangles[t + 7] = v + 5; triangles[t + 8] = v + 3;
            triangles[t + 9] = v + 2; triangles[t + 10] = v + 4; triangles[t + 11] = v + 5;
        }

        mesh.vertices = vertices;
        mesh.uv = uvs;
        mesh.triangles = triangles;
        mesh.RecalculateNormals();
        mesh.RecalculateBounds();

        var child = new GameObject("CrowdRing");
        child.transform.SetParent(transform, worldPositionStays: false);
        child.transform.position = RingCenter;

        var mf = child.AddComponent<MeshFilter>();
        mf.sharedMesh = mesh;

        var mr = child.AddComponent<MeshRenderer>();
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Texture")
                  ?? Shader.Find("Sprites/Default");
        var mat = new Material(shader) { name = "Crowd_Stadium" };
        if (mat.HasProperty("_BaseMap")) mat.SetTexture("_BaseMap", tex);
        if (mat.HasProperty("_MainTex")) mat.SetTexture("_MainTex", tex);
        if (mat.HasProperty("_Cull")) mat.SetFloat("_Cull", 0f); // both sides
        mr.sharedMaterial = mat;
        mr.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
        mr.receiveShadows = false;
    }

    /// <summary>
    /// Build the stadium texture. Layout:
    ///   - 28 horizontal rows (seat-back lines, darker gaps between rows)
    ///   - 16 vertical sections (aisles between)
    ///   - Lower 60% of texture is "lower deck", upper 40% is "upper deck"
    ///   - Per-pixel crowd noise → individual spectators
    ///   - ~0.2% of pixels are phone lights (warm/cool/blue/amber)
    ///   - ~0.01% are large bright lit signs (one per ring on average)
    /// </summary>
    private Texture2D GenerateStadiumTexture()
    {
        var tex = new Texture2D(TexWidth, TexHeight, TextureFormat.RGB24, mipChain: true)
        {
            name = "StadiumTex",
            wrapMode = TextureWrapMode.Repeat,
            filterMode = FilterMode.Bilinear,
        };

        var pixels = new Color[TexWidth * TexHeight];
        int upperDeckStart = (int)(TexHeight * 0.60f);
        int rowPixelHeight = TexHeight / RowCount;
        int sectionPixelWidth = TexWidth / SectionCount;
        int aisleWidth = Mathf.Max(2, sectionPixelWidth / 8);
        int rowGap = Mathf.Max(1, rowPixelHeight / 6);

        // Use a deterministic but visually random seed so re-runs give same look.
        var rng = new System.Random(7383);
        System.Func<float> rand = () => (float)rng.NextDouble();

        // Choose one bright lit sign per ring. Position it in the upper deck.
        int signSectionX = rng.Next(0, SectionCount);
        int signCenterX = signSectionX * sectionPixelWidth + sectionPixelWidth / 2;
        int signCenterY = upperDeckStart + (TexHeight - upperDeckStart) / 2;
        const int SignHalfW = 50;
        const int SignHalfH = 14;

        for (int y = 0; y < TexHeight; y++)
        {
            int rowLocalY = y % rowPixelHeight;
            bool isRowGap = rowLocalY < rowGap;
            float deckMul = (y >= upperDeckStart) ? UpperDeckDarken : 1f;

            for (int x = 0; x < TexWidth; x++)
            {
                int sectionIdx = (x / sectionPixelWidth) % SectionCount;
                int sectionLocalX = x % sectionPixelWidth;
                bool isAisle = sectionLocalX < aisleWidth;

                // Lit sign region — a bright rectangular panel.
                bool inSign =
                    System.Math.Abs(x - signCenterX) < SignHalfW &&
                    System.Math.Abs(y - signCenterY) < SignHalfH;

                Color baseColor = SectionColors[sectionIdx % SectionColors.Length] * deckMul;

                Color final;
                if (inSign)
                {
                    // Sign body — warm yellow with subtle edge falloff so it
                    // doesn't look pasted on.
                    float edgeT = Mathf.Min(
                        (SignHalfW - System.Math.Abs(x - signCenterX)) / (float)SignHalfW,
                        (SignHalfH - System.Math.Abs(y - signCenterY)) / (float)SignHalfH);
                    float t = Mathf.SmoothStep(0f, 1f, edgeT * 2f);
                    final = Color.Lerp(baseColor, new Color(1f, 0.92f, 0.55f), t);
                }
                else if (isRowGap)
                {
                    final = baseColor * RowGapDarken;
                }
                else if (isAisle)
                {
                    final = baseColor * AisleDarken;
                }
                else if (rand() < PhoneLightProbability)
                {
                    // Bright phone speck — 2×2 cluster so it survives mipmap downsampling.
                    final = PhoneLightPalette[rng.Next(PhoneLightPalette.Length)];
                }
                else if (rand() < SignProbability)
                {
                    // Stray lit advert (very rare)
                    final = new Color(0.9f, 0.6f, 0.3f);
                }
                else
                {
                    // Per-pixel crowd noise — small brightness wobble suggests
                    // individual spectators against the section base color.
                    float n = ((float)rand() - 0.5f) * CrowdNoiseAmplitude;
                    final = new Color(
                        Mathf.Clamp01(baseColor.r + n),
                        Mathf.Clamp01(baseColor.g + n),
                        Mathf.Clamp01(baseColor.b + n));
                }

                pixels[y * TexWidth + x] = final;
            }
        }

        // Quick second pass: thicken phone-light pixels into 2×2 blocks so
        // mip levels don't filter them away at viewing distance.
        for (int y = 0; y < TexHeight - 1; y++)
        {
            for (int x = 0; x < TexWidth - 1; x++)
            {
                Color c = pixels[y * TexWidth + x];
                // Detect a phone light by high luminance.
                if (c.r + c.g + c.b > 2.2f)
                {
                    pixels[y * TexWidth + x + 1] = c;
                    pixels[(y + 1) * TexWidth + x] = c;
                    pixels[(y + 1) * TexWidth + x + 1] = c;
                }
            }
        }

        tex.SetPixels(pixels);
        tex.Apply(updateMipmaps: true);
        return tex;
    }
}
