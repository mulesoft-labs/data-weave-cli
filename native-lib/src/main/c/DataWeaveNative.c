#include <stdlib.h>
#include <stdio.h>
#include <time.h>

#include <graal_isolate.h>


/* C function that gets passed to Java as a function pointer. */
void c_print(int success, char* cstr) {
  printf("Compilation: %d %s\n",success , cstr);
}

int main(int argc, char** argv) {
//  compilation_result result;
  int i = 1;
//  char* expression = "attributes.headers.myParam";
  while (i)
  {
    graal_isolatethread_t* thread;
    if (graal_create_isolate(NULL, NULL, &thread)) {
      return -1;
    }
    printf("Start Compilation TEO!!!!\n");
//    compile(thread, expression,  &c_print);
//    printf("Done\n");

//    printf("Start Compilation\n");
//    compileSync(thread, expression,  &result);
//    free(&result)
//    freeCompilationResult(thread, &result);

    printf("Done\n");
    //

    // This frees all memory allocated by the GraalVM runtime
    if (graal_tear_down_isolate(thread) != 0) {
       fprintf(stderr, "shutdown error\n");
       return 1;
     }
//     free(thread);
   }
    return 0;
}