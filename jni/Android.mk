# jni/Android.mk
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE     := main
LOCAL_SRC_FILES  := main.c android_native_app_glue.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_LDLIBS     := -llog -landroid -lm     # <- math lib for sin()
# APP_PLATFORM comes from your top-level make (NDK_PLATFORM), ok.
include $(BUILD_SHARED_LIBRARY)
