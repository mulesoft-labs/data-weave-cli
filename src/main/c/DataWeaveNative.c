#include <stdlib.h>
#include <stdio.h>

#include "libDw.h"

int main(int argc, char** argv) {
  graal_create_isolate_params_t isolate_params = {};
  graal_isolate_t* isolate;
  graal_isolatethread_t* thread;
  if (graal_create_isolate(&isolate_params, &isolate, &thread)) {
    return -1;
  }
  printf("%s\n", runDW(thread, "output application/json --- 1 to 100 map $ + 1"));
  return 0;
}