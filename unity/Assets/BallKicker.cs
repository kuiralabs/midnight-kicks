  using UnityEngine;                                                                                                                      
  using UnityEngine.InputSystem;                                                                                                        

  public class BallKicker : MonoBehaviour                                                                                                 
  {              
      public float kickForce = 12f;                                                                                                       
                                                                                                                                        
      private Rigidbody rb;
      private bool hasKicked = false;

      void Start()
      {
          rb = GetComponent<Rigidbody>();
      }
                                                                                                                                          
      void OnGUI()
      {                                                                                                                                   
          if (hasKicked) return;                                                                                                        

          float btnWidth = Screen.width / 4f;
          float btnHeight = 80f;
          float y = Screen.height - 120f;
                                                                                                                                          
          if (GUI.Button(new Rect(Screen.width/2 - btnWidth*1.5f, y, btnWidth, btnHeight), "LEFT"))
              Kick(new Vector3(-1.5f, 1.5f, 10f));                                                                                        
                                                                                                                                          
          if (GUI.Button(new Rect(Screen.width/2 - btnWidth/2, y, btnWidth, btnHeight), "CENTER"))
              Kick(new Vector3(0, 1.5f, 10f));                                                                                            
                                                                                                                                        
          if (GUI.Button(new Rect(Screen.width/2 + btnWidth/2, y, btnWidth, btnHeight), "RIGHT"))                                         
              Kick(new Vector3(1.5f, 1.5f, 10f));
      }                                                                                                                                   
                                                                                                                                        
      void Kick(Vector3 target)                                                                                                           
      {          
          Vector3 direction = (target - transform.position).normalized;                                                                   
          rb.AddForce(direction * kickForce, ForceMode.Impulse);                                                                        
          hasKicked = true;
          Invoke("Reset", 3f);
      }                                                                                                                                   
                 
      void Reset()                                                                                                                        
      {                                                                                                                                 
          rb.linearVelocity = Vector3.zero;
          rb.angularVelocity = Vector3.zero;
          transform.position = new Vector3(0, 0.5f, 0);
          hasKicked = false;                                                                                                              
      }          
  }      