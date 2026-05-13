using UnityEngine;

public class Keeper : MonoBehaviour
{
    public float diveSpeed = 5f;
    public AnimationCurve diveCurve = new AnimationCurve(
        new Keyframe(0, 0, 0, 2),
        new Keyframe(1, 1, 0, 0)
    );

    private Vector3 initialPosition;
    private Quaternion initialRotation;
    private Vector3 targetPosition;
    private bool isDiving = false;
    private float diveTimer = 0f;
    private float currentDiveDuration = 1f;
    private Animator animator;

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
        transform.position = Vector3.Lerp(initialPosition, targetPosition, curveT);

        if (t >= 1f)
        {
            isDiving = false;
            if (animator != null) animator.CrossFade("FallenIdle", 0.15f);
        }
    }

    public void Dive(int direction, float duration = 1.0f)
    {
        float xOffset = (direction - 1) * 2.5f;
        targetPosition = new Vector3(initialPosition.x + xOffset, initialPosition.y, initialPosition.z);

        currentDiveDuration = duration;
        diveTimer = 0f;
        isDiving = true;

        Debug.Log($"[Keeper] Dive direction={direction} from={initialPosition} → target={targetPosition} duration={duration}");

        if (animator != null)
        {
            string state = direction == 0 ? "DiveLeft" : direction == 1 ? "JumpCenter" : "DiveRight";
            animator.Play(state);
            Debug.Log($"[Keeper] Animator.Play('{state}') — applyRootMotion={animator.applyRootMotion}");
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
        if (animator != null) animator.Play("Idle");
    }
}
