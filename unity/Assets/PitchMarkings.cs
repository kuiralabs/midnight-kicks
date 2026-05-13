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
    // True FIFA dimensions. GrassPitch resizes the Field plane to 50×40m to
    // accommodate the 40.32m penalty-area width and the penalty arc that
    // extends 9.15m toward the shooter from the penalty spot.
    private const float PenaltyAreaWidth = 40.32f;
    private const float PenaltyAreaDepth = 16.5f;
    private const float GoalAreaWidth = 18.32f;
    private const float GoalAreaDepth = 5.5f;

    private const float PenaltyArcRadius = 9.15f;     // FIFA: 9.15m
    private const int PenaltyArcSegments = 48;
    private const float PenaltySpotMarkerRadius = 0.11f; // matches ball footprint
    private const float PenaltySpotLineWidth = 0.04f;    // thin painted ring, not a fat chalk band

    // The penalty spot sits directly under the ball at z=0 — real soccer
    // pitches paint the spot exactly where the ball rests for the kick.
    // Our ball is at (0, 0.11, 0); placing the spot here means the ball
    // covers it during idle and only the thin painted ring is visible.
    // This is a deliberate departure from FIFA's "11m from goal" distance
    // (our 50m-long field doesn't preserve full-scale FIFA distances anyway).
    private const float SpotZ = 0f;

    private const float GoalLineZ = 9.5f;             // matches Keeper z position
    private const float LineHeight = 0.02f;           // above field to avoid z-fighting
    private const float LineWidth = 0.12f;            // FIFA chalk lines are ~12cm

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
        const int segments = 20;
        var points = new Vector3[segments + 1];
        for (int i = 0; i <= segments; i++)
        {
            float angle = (i / (float)segments) * Mathf.PI * 2f;
            points[i] = new Vector3(
                Mathf.Cos(angle) * PenaltySpotMarkerRadius,
                LineHeight,
                SpotZ + Mathf.Sin(angle) * PenaltySpotMarkerRadius);
        }
        DrawLineLoop("PenaltySpot", points, closed: false, width: PenaltySpotLineWidth);
    }

    private void DrawPenaltyArc()
    {
        // The penalty arc is the portion of a 9.15m-radius circle (centered on
        // the penalty spot) that lies OUTSIDE the penalty area. With the spot
        // moved to the ball position (SpotZ), distance from spot to the
        // penalty-area front line is SpotZ - (GoalLineZ - PenaltyAreaDepth).
        // Half-angle where the circle exits the penalty area is
        // asin(sqrt(r² - d²) / r). θ runs from -halfAngle to +halfAngle
        // measured from the -Z axis (toward the shooter).
        float penaltyAreaFrontZ = GoalLineZ - PenaltyAreaDepth;
        float frontGap = SpotZ - penaltyAreaFrontZ;
        float halfWidthAtFront = Mathf.Sqrt(PenaltyArcRadius * PenaltyArcRadius - frontGap * frontGap);
        float halfAngle = Mathf.Asin(halfWidthAtFront / PenaltyArcRadius);

        var points = new Vector3[PenaltyArcSegments + 1];
        for (int i = 0; i <= PenaltyArcSegments; i++)
        {
            float t = i / (float)PenaltyArcSegments;
            float theta = -halfAngle + t * (halfAngle * 2f);
            points[i] = new Vector3(
                Mathf.Sin(theta) * PenaltyArcRadius,
                LineHeight,
                SpotZ - Mathf.Cos(theta) * PenaltyArcRadius);
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

    private void DrawLineLoop(string name, Vector3[] points, bool closed, float width = LineWidth)
    {
        var child = new GameObject(name);
        child.transform.SetParent(transform, worldPositionStays: false);

        var lr = child.AddComponent<LineRenderer>();
        lr.material = lineMaterial;
        lr.startWidth = width;
        lr.endWidth = width;
        lr.useWorldSpace = true;
        lr.loop = closed;
        lr.positionCount = points.Length;
        lr.SetPositions(points);
        lr.startColor = LineColor;
        lr.endColor = LineColor;
    }
}
