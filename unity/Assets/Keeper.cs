using UnityEngine;

public class Keeper : MonoBehaviour
{
    public float diveSpeed = 5f;
    public AnimationCurve diveCurve = new AnimationCurve(
        new Keyframe(0, 0, 0, 2),
        new Keyframe(1, 1, 0, 0)
    );

    // Skip the first ~30% of the dive clip — Mixamo's goalkeeper dives
    // start with several setup steps before the actual leap, which looks
    // like the keeper is walking instead of diving. Starting partway
    // through removes the prep and keeps just one push-off frame before
    // the body launches sideways. Tune in 0.05 increments if needed.
    [SerializeField] private float diveAnimationStart = 0.30f;

    // Begin blending to FallenIdle when the position lerp is 35% done,
    // with a 0.35s blend duration. Without this, the dive clip holds the
    // mid-air leap pose until the lerp completes and then snaps to the
    // laying-down state — the keeper looks suspended. Early aggressive
    // trigger + long blend = the body is already descending before it
    // looks like it's hanging.
    [SerializeField] private float fallenCrossfadeAt = 0.35f;
    [SerializeField] private float fallenCrossfadeBlend = 0.35f;
    private bool fallenCrossfadeStarted = false;

    // Play the dive clip faster than its authored speed so the body moves
    // through the leap-and-fall portion within the dive duration instead
    // of being frozen on the leap frame. 1.5× is a snappy real-keeper
    // tempo; 2.0× starts feeling cartoony.
    [SerializeField] private float diveSpeedMultiplier = 1.5f;

    // Parabolic vertical arc layered on top of the horizontal lerp — gives
    // the dive real projectile motion (launch up, peak mid-flight, fall
    // back to ground) instead of a flat lateral slide. 0.5m matches a
    // real keeper's hip-lift on a saved penalty dive; tune up for more
    // dramatic / down for more conservative.
    [SerializeField] private float divePeakHeight = 0.5f;

    private Vector3 initialPosition;
    private Quaternion initialRotation;
    private Vector3 targetPosition;
    private bool isDiving = false;
    private float diveTimer = 0f;
    private float currentDiveDuration = 1f;
    private Animator animator;

    /// Wired in ShotManager to the ball's parry; invoked by [OnSaveContact].
    public System.Action saveContact;

    /// Animation Event target: place an event on the contact frame of each dive
    /// clip (GoalkeeperDiveLeft/Right/JumpCenter) that calls this — fires the
    /// parry exactly when the keeper's hands meet the ball.
    public void OnSaveContact() => saveContact?.Invoke();

    void Start()
    {
        initialPosition = transform.position;
        initialRotation = transform.rotation;
        targetPosition = initialPosition;
        animator = GetComponent<Animator>();
        Debug.Log($"[Keeper] Start gameObject={gameObject.name} active={gameObject.activeInHierarchy} pos={initialPosition} animator={(animator!=null)} hasController={(animator!=null && animator.runtimeAnimatorController!=null)}");
    }

    void Update()
    {
        if (!isDiving) return;

        diveTimer += Time.deltaTime;
        float t = Mathf.Clamp01(diveTimer / currentDiveDuration);
        float curveT = diveCurve.Evaluate(t);

        // Horizontal: ease-out lerp from start → target (existing behavior).
        Vector3 pos = Vector3.Lerp(initialPosition, targetPosition, curveT);
        // Vertical: parabolic arc 4t(1−t) — zero at t=0, peaks at t=0.5,
        // zero again at t=1. Gives the dive real projectile motion so the
        // body launches up, hangs at the apex, then arcs back to ground
        // instead of sliding flat then suddenly falling.
        pos.y = initialPosition.y + divePeakHeight * 4f * t * (1f - t);
        transform.position = pos;

        // Start blending to FallenIdle early so the body eases down toward
        // the ground instead of holding the mid-air leap pose.
        if (!fallenCrossfadeStarted && t >= fallenCrossfadeAt)
        {
            fallenCrossfadeStarted = true;
            if (animator != null) animator.CrossFade("FallenIdle", fallenCrossfadeBlend);
        }

        if (t >= 1f)
        {
            isDiving = false;
        }
    }

    public void Dive(int direction, float duration = 1.0f)
    {
        float xOffset = (direction - 1) * 2.5f;
        targetPosition = new Vector3(initialPosition.x + xOffset, initialPosition.y, initialPosition.z);

        currentDiveDuration = duration;
        diveTimer = 0f;
        isDiving = true;
        fallenCrossfadeStarted = false;

        Debug.Log($"[Keeper] Dive direction={direction} from={initialPosition} → target={targetPosition} duration={duration}");

        if (animator != null)
        {
            // The keeper faces the shooter (transform rotated Y=180°), so the
            // character's local left is the world's +X and vice versa. The
            // 'direction' arg is from the SHOOTER'S perspective (0=left, 2=
            // right), which puts the ball at world −X / +X respectively. To
            // make the body lean the same way the transform slides, we play
            // the OPPOSITE-named animation: shooter-left (world −X) → keeper
            // leans to its OWN right → play 'DiveRight'.
            string state = direction == 0 ? "DiveRight"
                         : direction == 1 ? "JumpCenter"
                         :                  "DiveLeft";
            // normalizedTime arg skips the prep frames (setup steps) and
            // jumps straight to the leap portion of the clip.
            animator.Play(state, 0, diveAnimationStart);
            // Speed up clip playback so the body moves through leap → fall
            // within the dive window instead of holding the mid-air pose.
            animator.speed = diveSpeedMultiplier;
            Debug.Log($"[Keeper] Animator.Play('{state}', startAt={diveAnimationStart:F2}, speed={diveSpeedMultiplier:F2}) — applyRootMotion={animator.applyRootMotion}");
        }
        else
        {
            Debug.LogWarning("[Keeper] Dive: animator is null — no animation will play");
        }
    }

    public void Reset()
    {
        transform.position = initialPosition;
        transform.rotation = initialRotation;
        targetPosition = initialPosition;
        isDiving = false;
        Debug.Log($"[Keeper] Reset → {initialPosition}");
        if (animator != null)
        {
            // Restore default speed so Idle isn't sped up.
            animator.speed = 1f;
            animator.Play("Idle");
        }
    }
}
