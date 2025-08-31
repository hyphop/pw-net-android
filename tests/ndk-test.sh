#!/bin/sh

. ~/android/env.sh

cat > ok.c <<'EOF'
#include <jni.h>
__attribute__((visibility("default")))
jint Java_com_example_ok_Add_sum(JNIEnv* e,jclass c,jint a,jint b){ return a+b; }
EOF

PREBUILT=$(ls -d "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/linux-* | head -1)
"$PREBUILT/bin/aarch64-linux-android21-clang" -shared -fPIC -O2 ok.c -o libok.so
file libok.so
