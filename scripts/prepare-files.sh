#!/bin/sh

set -x 
. ./env.sh

cp "$ANDROID_NDK_HOME"/sources/android/native_app_glue/android_native_app_glue.? jni

