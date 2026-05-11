  using UnityEngine;                                                                                                                      
  using UnityEngine.InputSystem;                                                                                                        

  public class BallKicker : MonoBehaviour                                                                                                 
  {              
      public float kickForce = 18f;
      // Ball rests just in front of where the shooter's run-up ends so the
      // kick animation's foot extension naturally meets the ball. ShotManager
      // ends the run at z=-0.3; foot extends ~0.5m forward, so the ball
      // sits at z=0.2 to be where the foot lands.
      public Vector3 restPosition = new Vector3(0f, 0.11f, 0.2f);

      private Rigidbody rb;
      private bool hasKicked = false;

      void Awake()
      {
          rb = GetComponent<Rigidbody>();
          if (rb != null) rb.mass = 0.45f;
      }

      void Start()
      {
          if (rb == null) rb = GetComponent<Rigidbody>();
      }

      public void KickTo(int directionIndex)
      {
          if (rb == null) rb = GetComponent<Rigidbody>();
          // 0: Left, 1: Center, 2: Right
          float xOffset = (directionIndex - 1) * 2.5f;
          Vector3 target = new Vector3(xOffset, 1.8f, 10.5f);
          Kick(target);
      }

      public void ResetBall()
      {
          CancelInvoke("Reset");
          Reset();
      }

      void Kick(Vector3 target)                                                                                                           
      {          
          if (rb == null) rb = GetComponent<Rigidbody>();
          Vector3 direction = (target - transform.position).normalized;                                                                   
          rb.AddForce(direction * kickForce, ForceMode.Impulse);                                                                        
hasKicked = true;
          Invoke("Reset", 3f);
      }                                                                                                                                   
               
      void Reset()
      {
          if (rb == null) rb = GetComponent<Rigidbody>();
          rb.linearVelocity = Vector3.zero;
          rb.angularVelocity = Vector3.zero;
          transform.position = restPosition;
          hasKicked = false;
      }
  }      