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
  printf("%s\n", runDW(thread, "%dw 2.0 import Dependency from dw::deps::Deps \n @Dependency(artifactId = '142ebe1a-6e25-4670-9220-0cdce791f1b8:string-utils-weave-module:1.0.0-SNAPSHOT')\n import times from org::mule::commons::StringUtils\n output application/json --- times('Test', 2)"));
  end = clock();
  time_taken = ((double) (end - start)) / CLOCKS_PER_SEC;
  printf("fun() took %f seconds to execute \n", time_taken);
  return 0;
}