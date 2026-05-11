using UnityEngine;

public class GoalTrigger : MonoBehaviour
{
    public bool ballInside = false;

    void OnTriggerEnter(Collider other)
    {
        if (other.CompareTag("Ball") || other.name == "Ball")
        {
            ballInside = true;
            Debug.Log("GOAL DETECTED!");
        }
    }

    public void ResetGoal()
    {
        ballInside = false;
    }
}
