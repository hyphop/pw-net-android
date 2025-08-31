# load env

. ~/android/env.sh

# sanity
echo "$JAVA_HOME"
javac -version
sdkmanager --list | head -n 20
adb version
