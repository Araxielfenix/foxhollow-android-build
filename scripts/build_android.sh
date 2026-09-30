#!/bin/bash
# Script de compilación para Foxhollow Android
# Uso: ./scripts/build_android.sh [debug|release]

set -e

VARIANTE=${1:-debug}
ABI="arm64-v8a"
PROYECTO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "============================================"
echo " Foxhollow Android Build"
echo " Variante: $VARIANTE | ABI: $ABI"
echo "============================================"

# Verificar NDK
if [ -z "$ANDROID_NDK" ] && [ -f "$PROYECTO_DIR/local.properties" ]; then
    ANDROID_NDK=$(grep "^ndk.dir=" "$PROYECTO_DIR/local.properties" | cut -d= -f2)
fi

if [ -z "$ANDROID_NDK" ]; then
    echo "ERROR: Define ANDROID_NDK o configura ndk.dir en local.properties"
    exit 1
fi

echo "NDK: $ANDROID_NDK"

# 1. Compilar Aurora para Android
echo ""
echo "[1/4] Compilando Aurora para Android..."
cd "$PROYECTO_DIR/extern/aurora"
mkdir -p "build-android-$ABI"
cd "build-android-$ABI"
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DAURORA_ENABLE_WEBGPU=ON \
    -DAURORA_ENABLE_SDL=ON
cmake --build . -j$(nproc 2>/dev/null || sysctl -n hw.ncpu)

# 2. Compilar Borealis para Android
echo ""
echo "[2/4] Compilando Borealis para Android..."
cd "$PROYECTO_DIR/extern/borealis"
mkdir -p "build-android-$ABI"
cd "build-android-$ABI"
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DAURORA_DIR="$PROYECTO_DIR/extern/aurora/build-android-$ABI"
cmake --build . -j$(nproc 2>/dev/null || sysctl -n hw.ncpu)

# 3. Compilar Foxhollow (aplicación Gradle)
echo ""
echo "[3/4] Compilando Foxhollow con Gradle..."
cd "$PROYECTO_DIR"
chmod +x gradlew 2>/dev/null || true

if [ -f "gradlew" ]; then
    if [ "$VARIANTE" = "release" ]; then
        ./gradlew assembleRelease
    else
        ./gradlew assembleDebug
    fi
else
    echo "WARN: gradlew no existe, ejecutando gradle..."
    if [ "$VARIANTE" = "release" ]; then
        gradle assembleRelease
    else
        gradle assembleDebug
    fi
fi

# 4. Localizar el APK
echo ""
echo "[4/4] Localizando APK..."
APK=$(find "$PROYECTO_DIR/app/build/outputs/apk" -name "*.apk" 2>/dev/null | head -1)

echo ""
echo "============================================"
echo " ¡COMPILACIÓN COMPLETADA!"
echo " APK: $APK"
echo "============================================"
echo ""
echo "Instalar en dispositivo:"
echo "  adb install \"$APK\""
echo ""
echo "NOTA: La ISO del juego no se incluye (licencias)."
echo "Deberás seleccionarla en el dispositivo al iniciar la app."
