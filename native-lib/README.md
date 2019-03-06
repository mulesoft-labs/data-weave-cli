
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

```bash
cd native-lib/src/main/c
clang -I<RepoClone Directory>/weave-native-library/native-lib/build/graal -L<RepoClone Directory>/weave-native-library/native-lib/build/graal -lDw ./DataWeaveNative.c -o DataWeaveNative
```