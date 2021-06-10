#include <stdlib.h>
#include <stdio.h>
#include <time.h>

#include "libdw.h"


int main(int argc, char** argv) {
  compilation_result result;
  int i = 1;
  while (i <= 5)
  {
    graal_isolatethread_t* thread;
    if (graal_create_isolate(NULL, NULL, &thread)) {
      return -1;
    }
    printf("Start\n");
    compile(thread, "attributes.headers.myParam", &result);
//    printf("%d\n", result.f_success);
    printf("%s\n", result.f_pel);
    printf("Done\n");
    freeJavaObject(thread, &result);
//     if ( graal_detach_thread(thread) != 0){
//       fprintf(stderr, "ditach error\n");
//       return 1;
//     }
    if (graal_tear_down_isolate(thread) != 0) {
       fprintf(stderr, "shutdown error\n");
       return 1;
     }
   }
  return 0;
}