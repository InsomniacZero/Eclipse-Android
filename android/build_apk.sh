#!/usr/bin/env bash
# ==============================================================================
# Eclipse Android Client - Local APK Build Script
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

C_CYAN='\033[36m'
C_GREEN='\033[32m'
C_YELLOW='\033[33m'
C_RED='\033[31m'
C_BOLD='\033[1m'
C_RESET='\033[0m'

echo -e "\n${C_BOLD}${C_CYAN}=== Eclipse Android Client APK Builder ===${C_RESET}\n"

# 1. Check Java JDK (AGP 8.4 and Gradle 8.7 require Java 17 or 21)
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/openjdk-21 /usr/lib/jvm/openjdk-17; do
        if [ -d "$candidate" ]; then
            export JAVA_HOME="$candidate"
            export PATH="$JAVA_HOME/bin:$PATH"
            break
        fi
    done
fi

if ! command -v java >/dev/null 2>&1; then
    echo -e "${C_RED}[!] Java is not installed or not in PATH.${C_RESET}"
    echo "    Please install OpenJDK 17: sudo apt install openjdk-17-jdk"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -n 1)
echo -e "${C_GREEN}[✓] Java detected:${C_RESET} ${JAVA_VER}"

# 2. Check Python 3
if ! command -v python3 >/dev/null 2>&1; then
    echo -e "${C_RED}[!] Python 3 is required for Chaquopy build toolchain.${C_RESET}"
    exit 1
fi
PY_VER=$(python3 --version 2>&1)
echo -e "${C_GREEN}[✓] Python detected:${C_RESET} ${PY_VER}"

# 3. Check Android SDK
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    for candidate in "$HOME/Android/Sdk" "$HOME/Android/sdk" "/usr/lib/android-sdk" "/opt/android-sdk" "/usr/local/share/android-sdk"; do
        if [ -d "$candidate" ]; then
            export ANDROID_HOME="$candidate"
            echo -e "${C_YELLOW}[!] ANDROID_HOME set automatically to:${C_RESET} ${ANDROID_HOME}"
            break
        fi
    done
    if [ -z "${ANDROID_HOME:-}" ]; then
        echo -e "${C_YELLOW}[!] Warning: ANDROID_HOME or ANDROID_SDK_ROOT not set.${C_RESET}"
        echo "    If the build fails, export ANDROID_HOME=/path/to/android/sdk"
    fi
else
    echo -e "${C_GREEN}[✓] Android SDK detected:${C_RESET} ${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
fi

# 4. Make gradlew executable
chmod +x ./gradlew

# 5. Build Debug APK
echo -e "\n${C_CYAN}➜ Running Gradle assembleDebug...${C_RESET}\n"
./gradlew assembleDebug "$@"

# 6. Verify Output
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo -e "\n${C_BOLD}${C_GREEN}[✓] APK Built Successfully!${C_RESET}"
    echo -e "    ${C_BOLD}Output:${C_RESET} ${SCRIPT_DIR}/${APK_PATH}"
    echo -e "    ${C_BOLD}Size:${C_RESET}   ${APK_SIZE}\n"
    echo -e "To install on connected device:"
    echo -e "    ${C_CYAN}adb install -r ${APK_PATH}${C_RESET}\n"
else
    echo -e "\n${C_RED}[!] Build finished, but ${APK_PATH} was not found.${C_RESET}\n"
    exit 1
fi
