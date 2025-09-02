# ==== PW-net audio streamer (no Gradle) ====

HOST ?= 192.168.1.131
PORT ?= 9999
MDNS_SRV_NAME ?= _pwnet._tcp.local.

#CAPTURE_APP_NAME ?= com.foobar2000.foobar2000
CAPTURE_APP_NAME ?= ""
#CAPTURE_APP_ID ?= 0
CAPTURE_APP_ID ?= null

APP_ID   := org.example.mininative
MIN_SDK  := 24
ABI      := arm64-v8a

PLAT ?= $(ANDROID_SDK_ROOT)/platforms/android-34/android.jar

KS_FILE  ?= mini.keystore
KS_ALIAS ?= mini
KS_PASS  ?= changeit

# tools (expect PATH from env.sh)
AAPT2    ?= aapt2
JAVAC    ?= javac
D8       ?= d8
ZIPALIGN ?= zipalign
APKSIGN  ?= apksigner
ADB      ?= adb
NDKB     ?= ndk-build

# --- deps (only edit DEPS_URLS) ---
DEPS_DIR  := deps
DEPS_URLS := \
https://repo1.maven.org/maven2/javax/jmdns/jmdns/3.5.9/jmdns-3.5.9.jar \
https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar \
https://repo1.maven.org/maven2/org/slf4j/slf4j-android/1.7.36/slf4j-android-1.7.36.jar

DEPS_LIB := $(addprefix $(DEPS_DIR)/,$(notdir $(DEPS_URLS)))

# --- COLON-JOIN, NO STRAY SPACES (shell-based, robust) ----------------------
# Produces something like: deps/a.jar:deps/b.jar:deps/c.jar  (or empty)
DEPS_CP := $(shell set -e; \
  list="$(DEPS_LIB)"; \
  list=$$(printf "%s\n" $$list | tr '\n' ' '); \
  list=$$(printf "%s" "$$list" | tr -s '[:space:]' ' ' | sed -e 's/^ *//' -e 's/ *$$//'); \
  [ -z "$$list" ] || printf "%s" "$$list" | sed 's/ /:/g' \
)

# Final classpath = platform + optional deps (NO extra spaces/colons)
CP_ALL := $(PLAT)$(if $(DEPS_CP),:$(DEPS_CP))

$(DEPS_DIR):
	@mkdir -p $(DEPS_DIR)

# download missing jars (idempotent)
$(DEPS_LIB): | $(DEPS_DIR)
	@bash -lc 'set -e; \
	for u in $(DEPS_URLS); do \
	  f="$(DEPS_DIR)/$${u##*/}"; \
	  if [ ! -f "$$f" ]; then echo "[*] Fetch $$f"; curl -L -o "$$f" "$$u"; fi; \
	done'

# debug (optional): print classpath exactly as used
print-cp:
	@echo 'CP_ALL=$(CP_ALL)'

# outputs
OUT          := build
OUT_GEN      := $(OUT)/generated          # aapt2 --java output dir (R.java in here)
OUT_CLASSES  := $(OUT)/classes
OUT_JAR      := $(OUT)/classes.jar
OUT_DEX      := $(OUT)/classes.dex
OUT_RESZIP   := $(OUT)/res.zip

APK_RAW  := $(OUT)/unsigned.apk
APK_LIB  := $(OUT)/unsigned_with_lib.apk
APK_ALN  := $(OUT)/aligned.apk
APK_REL  := $(OUT)/app-release.apk

# sources
CFG        := src/org/example/mininative/Config.java
SRC_JAVA   := $(shell find src -type f -name "*.java")
#SRC_RES    := $(shell find res -type f -name "*.xml")
SRC_RES    := $(shell find res -type f \( -name "*.xml" -o -name "*.png" -o -name "*.webp" \))

JNI_MAIN   := jni/main.c
JNI_MK     := jni/Android.mk

.PHONY: all clean distclean keystore keystore-recreate run install uninstall log re FORCE

all: $(APK_REL)

# --- 0) generate Config.java every build (fresh BUILD/GIT) ---
$(CFG): FORCE
	@mkdir -p $(dir $(CFG))
	@printf 'package org.example.mininative;\n' > $(CFG)
	@printf 'public final class Config {\n' >> $(CFG)
	@printf '  public static final String HOST="%s";\n' "$(HOST)" >> $(CFG)
	@printf '  public static final String CAPTURE_APP_NAME="%s";\n' "$(CAPTURE_APP_NAME)" >> $(CFG)
	@printf '  public static final int CAPTURE_APP_UID=%d;\n' "$(CAPTURE_APP_UID)" >> $(CFG)
	@printf '  public static final String MDNS_SRV_NAME="%s";\n' "$(MDNS_SRV_NAME)" >> $(CFG)
	@printf '  public static final int PORT=%s;\n' "$(PORT)" >> $(CFG)
	@printf '  public static final String BUILD="%s";\n' "$$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> $(CFG)
	@printf '  public static final String GIT="%s";\n' "$$(git rev-parse --short HEAD 2>/dev/null || echo nogit)" >> $(CFG)
	@printf '}\n' >> $(CFG)
	@cat $(CFG)

# --- 1) compile resources (entire res/ dir) ---
$(OUT_RESZIP): $(SRC_RES)
	@mkdir -p $(OUT)
	@rm -f $(OUT_RESZIP)
	$(AAPT2) compile --dir res -o $(OUT_RESZIP)

# --- 2) link base APK AND generate R.java directory ---
# aapt2 writes R.java into $(OUT_GEN)/org/example/mininative/R.java
$(APK_RAW): AndroidManifest.xml $(OUT_RESZIP)
	@mkdir -p $(OUT_GEN)
	$(AAPT2) link -o $(APK_RAW) \
	  -I $(PLAT) \
	  --manifest AndroidManifest.xml \
	  --rename-manifest-package $(APP_ID) \
	  -R $(OUT_RESZIP) \
	  --auto-add-overlay \
	  --java $(OUT_GEN)

# --- 3) Java -> classes.jar -> classes.dex (R.java picked via -sourcepath) ---
$(OUT_JAR): $(SRC_JAVA) $(CFG) $(APK_RAW) $(DEPS_LIB)
	@mkdir -p $(OUT_CLASSES)
	$(JAVAC) -source 1.8 -target 1.8 -encoding UTF-8 \
	  -bootclasspath $(PLAT) -classpath "$(CP_ALL)" \
	  -sourcepath src:$(OUT_GEN) -implicit:class \
	  -d $(OUT_CLASSES) $(SRC_JAVA)
	@cd $(OUT_CLASSES) && jar cf ../$(notdir $(OUT_JAR)) .

$(OUT_DEX): $(OUT_JAR) $(DEPS_LIB)
	$(D8) --release --min-api $(MIN_SDK) --lib $(PLAT) \
	  --output $(OUT) $(OUT_JAR) $(DEPS_LIB)

# --- 4) NDK .so ---
$(OUT)/libs/$(ABI)/libmain.so: $(JNI_MAIN) $(JNI_MK)
	@mkdir -p $(OUT)
	$(NDKB) -C jni APP_ABI=$(ABI) NDK_PROJECT_PATH=.. APP_PLATFORM=android-$(MIN_SDK)
	@mkdir -p $(OUT)/libs/$(ABI)
	cp -f libs/$(ABI)/libmain.so $(OUT)/libs/$(ABI)/

# --- 5) add classes.dex + .so to APK (do NOT touch resources.arsc) ---
$(APK_LIB): $(APK_RAW) $(OUT_DEX) $(OUT)/libs/$(ABI)/libmain.so
	@cp -f $(APK_RAW) $(APK_LIB)
	@mkdir -p $(OUT)/lib/$(ABI)
	cp -f $(OUT)/libs/$(ABI)/libmain.so $(OUT)/lib/$(ABI)/
	@(cd $(OUT) && zip -q -X $(notdir $(APK_LIB)) classes.dex lib/$(ABI)/libmain.so)

# --- 6) align + sign ---
$(APK_ALN): $(APK_LIB)
	$(ZIPALIGN) -p -f 4 $(APK_LIB) $(APK_ALN)

$(APK_REL): $(APK_ALN) $(KS_FILE)
	$(APKSIGN) sign --ks $(KS_FILE) --ks-pass pass:$(KS_PASS) --key-pass pass:$(KS_PASS) \
	  --out $(APK_REL) $(APK_ALN)

# --- install / run helpers ---
install: $(APK_REL)
	$(ADB) install --no-incremental -r $(APK_REL)

run: install
	$(ADB) shell am start -n $(APP_ID)/.MainActivity

uninstall:
	-$(ADB) uninstall $(APP_ID) || true

log:
	$(ADB) logcat -s MiniNativeStream MainActivity MDNS MediaWatchService

re:
	$(MAKE) -j && $(MAKE) install && $(MAKE) run

# --- keystore helpers ---
keystore:
	@keytool -list -keystore $(KS_FILE) -storepass $(KS_PASS) -alias $(KS_ALIAS) >/dev/null 2>&1 \
	 && echo "[KS] using existing $(KS_FILE) alias $(KS_ALIAS)" \
	 || keytool -genkeypair -v -keystore $(KS_FILE) -alias $(KS_ALIAS) \
	      -storepass $(KS_PASS) -keypass $(KS_PASS) \
	      -dname "CN=mini, OU=dev, O=mini, L=Earth, S=NA, C=ZZ" \
	      -keyalg RSA -keysize 2048 -validity 10000

keystore-recreate:
	@rm -f $(KS_FILE)
	@$(MAKE) keystore

# --- clean ---
clean:
	@rm -rf $(OUT) libs obj

distclean: clean
	@rm -f $(KS_FILE)

FORCE:
	@true
