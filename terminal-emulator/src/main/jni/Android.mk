LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_LDFLAGS += -Wl,--build-id=sha1
LOCAL_CFLAGS += -ffile-prefix-map=$(LOCAL_PATH)=. -fdebug-prefix-map=$(LOCAL_PATH)=. -fdebug-compilation-dir=.
LOCAL_CPPFLAGS += -ffile-prefix-map=$(LOCAL_PATH)=. -fdebug-prefix-map=$(LOCAL_PATH)=. -fdebug-compilation-dir=.
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
ifneq ($(TERMUX_GHOSTTY_SOURCE),)
ghostty_native := $(TERMUX_GHOSTTY_SOURCE)/native/ghostty-vt
ghostty_jni := $(TERMUX_GHOSTTY_SOURCE)/app/src/main/cpp

include $(CLEAR_VARS)
LOCAL_MODULE := ghostty-vt
LOCAL_SRC_FILES := $(ghostty_native)/prebuilt/$(TARGET_ARCH_ABI)/libghostty-vt.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := termux-ghostty
# The upstream PNG-only stb_image build leaves JPEG helpers unused.
LOCAL_CFLAGS := -Wno-unused-function
LOCAL_SRC_FILES := ghostty_bridge.c $(ghostty_jni)/png_decode.c $(ghostty_jni)/kitty_unicode.c
LOCAL_C_INCLUDES := $(ghostty_native)/include $(ghostty_jni)
LOCAL_STATIC_LIBRARIES := ghostty-vt
LOCAL_LDLIBS := -lm -llog -latomic
include $(BUILD_SHARED_LIBRARY)
endif
endif
