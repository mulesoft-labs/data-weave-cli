
# How to compile your native library against our native api

## Generate native library

First compile the native library 

```
./gradlew native-lib:sharedLibrary

```

Once is being built you can see an example c code in 

`native-lib/src/main/c/DataWeaveNative.c`

Then to compile it in MacOs you need clang

## How to compile on mac os

First step open `native-lib/build/graal/libdw.h` and insert `#include <dw_compiler.h>`

Then compile and run

```bash
clang -I./native-lib/build/graal -I./native-lib/src/main/c/ -L./native-lib/build/graal  -ldw ./native-lib/src/main/c/DataWeaveNative.c -o DataWeaveNative && ./DataWeaveNative
```