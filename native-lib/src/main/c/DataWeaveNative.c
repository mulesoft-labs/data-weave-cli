#include <stdlib.h>
#include <stdio.h>
#include <time.h>

#include "libDw.h"

int main(int argc, char** argv) {
  graal_create_isolate_params_t isolate_params = {};
  graal_isolate_t* isolate;
  graal_isolatethread_t* thread;
  if (graal_create_isolate(&isolate_params, &isolate, &thread)) {
    return -1;
  }
  clock_t start, end;
  double time_taken;
  start = clock();
  printf("%s\n", compilePeregrineExpression(thread, "payload.a.b"));
  end = clock();
  time_taken = ((double) (end - start)) / CLOCKS_PER_SEC;
  printf("fun() took %f seconds to execute \n", time_taken);
  return 0;
}