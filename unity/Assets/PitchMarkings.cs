using UnityEngine;

/// <summary>
/// Procedurally draws penalty-area markings on the field at scene load using
/// LineRenderers. No model imports, no textures. All measurements are in
/// meters and proportional to the goal at z=9.5.
///
/// Auto-attaches via RuntimeInitializeOnLoadMethod — no scene edits required.
/// Settings can still be tuned from the inspector after the GameObject is
/// created.
/// </summary>
public class PitchMarkings : MonoBehaviour
{
    // FIFA dimensions are 16.5m × 40.32m penalty box; our playable half is
    // only ~13m deep (shooter z=-3.5 → goal z=9.5) so we shrink proportionally.
    private const float PenaltyAreaWidth = 13f;
    private const float PenaltyAreaDepth = 8f;
    private const float GoalAreaWidth = 6f;
    private const float GoalAreaDepth = 3f;

    private const float PenaltySpotZ = 2f;            // distance from goal line toward shooter
    private const float PenaltyArcRadius = 4f;
    private const int PenaltyArcSegments = 24;

    private const float GoalLineZ = 9.5f;             // matches Keeper z position
    private const float LineHeight = 0.02f;           // above field to avoid z-fighting
    private const float LineWidth = 0.08f;

    private static readonly Color LineColor = new Color(0.95f, 0.95f, 0.95f, 1f);

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void AutoCreate()
    {
        if (FindFirstObjectByType<PitchMarkings>() != null) return;
        var go = new GameObject("PitchMarkings");
        go.AddComponent<PitchMarkings>();
        Debug.Log("[PitchMarkings] Auto-created");
    }

    private Material lineMaterial;

    void Start()
    {
        // Unlit shader so markings don't react to the floodlights — keeps them
        // crisp white even in the moonlit color grade. URP-first.
        var shader = Shader.Find("Universal Render Pipeline/Unlit")
                  ?? Shader.Find("Unlit/Color")
                  ?? Shader.Find("Sprites/Default");
        lineMaterial = new Material(shader);
        lineMaterial.color = LineColor;

        DrawPenaltyBox();
        DrawGoalArea();
        DrawPenaltySpot();
        DrawPenaltyArc();
        DrawGoalLine();
    }

    private void DrawPenaltyBox()
    {
        float halfW = PenaltyAreaWidth / 2f;
        float front = GoalLineZ - PenaltyAreaDepth;
        DrawLineLoop("PenaltyBox", new[]
        {
            new Vector3(-halfW, LineHeight, GoalLineZ),
            new Vector3(-halfW, LineHeight, front),
            new Vector3(halfW, LineHeight, front),
            new Vector3(halfW, LineHeight, GoalLineZ),
        }, closed: false);
    }

    private void DrawGoalArea()
    {
        float halfW = GoalAreaWidth / 2f;
        float front = GoalLineZ - GoalAreaDepth;
        DrawLineLoop("GoalArea", new[]
        {
            new Vector3(-halfW, LineHeight, GoalLineZ),
            new Vector3(-halfW, LineHeight, front),
            new Vector3(halfW, LineHeight, front),
            new Vector3(halfW, LineHeight, GoalLineZ),
        }, closed: false);
    }

    private void DrawPenaltySpot()
    {
        const int segments = 16;
        const float radius = 0.15f;
        var points = new Vector3[segments + 1];
        float spotZ = GoalLineZ - PenaltySpotZ;
        for (int i = 0; i <= segments; i++)
        {
            float angle = (i / (float)segments) * Mathf.PI * 2f;
            points[i] = new Vector3(Mathf.Cos(angle) * radius, LineHeight, spotZ + Mathf.Sin(angle) * radius);
        }
        DrawLineLoop("PenaltySpot", points, closed: false);
    }

    private void DrawPenaltyArc()
    {
        // Arc opens away from goal (toward shooter). Half-circle around the spot.
        var points = new Vector3[PenaltyArcSegments + 1];
        float spotZ = GoalLineZ - PenaltySpotZ;
        for (int i = 0; i <= PenaltyArcSegments; i++)
        {
            // Start at the left point of the arc, sweep down through center, to the right.
            float t = i / (float)PenaltyArcSegments;
            float angle = Mathf.PI + t * Mathf.PI;   // π → 2π
            points[i] = new Vector3(
                Mathf.Cos(angle) * PenaltyArcRadius,
                LineHeight,
                spotZ + Mathf.Sin(angle) * PenaltyArcRadius);
        }
        DrawLineLoop("PenaltyArc", points, closed: false);
    }

    private void DrawGoalLine()
    {
        // Visible portion of the goal line — out to the edges of the penalty box.
        float halfW = PenaltyAreaWidth / 2f;
        DrawLineLoop("GoalLine", new[]
        {
            new Vector3(-halfW, LineHeight, GoalLineZ),
            new Vector3(halfW, LineHeight, GoalLineZ),
        }, closed: false);
    }

    private void DrawLineLoop(string name, Vector3[] points, bool closed)
    {
        var child = new GameObject(name);
        child.transform.SetParent(transform, worldPositionStays: false);

        var lr = child.AddComponent<LineRenderer>();
        lr.material = lineMaterial;
        lr.startWidth = LineWidth;
        lr.endWidth = LineWidth;
        lr.useWorldSpace = true;
        lr.loop = closed;
        lr.positionCount = points.Length;
        lr.SetPositions(points);
        lr.startColor = LineColor;
        lr.endColor = LineColor;
    }
}
