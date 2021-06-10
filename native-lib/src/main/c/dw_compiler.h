//#define DATA_ARRAY_LENGTH 4
/**
*   attributes.headers["tato"] =>
*  {expression: { headers: "Tato"}}
*/
typedef struct compilation_result_struct {
  /**
  * If the Compiler was able to compile it to Peregrine Expression Lanague
  */
  int f_success;

  /**
  * When the compiler was not able to produce a PEL equivalent. The reason is going to be set
  */
  char* f_error_message;

  /*
  * The Peregrine Expression that was a result from compiling the Weave Expression
  */
  char* f_pel;

} compilation_result;
