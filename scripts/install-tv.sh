#!/usr/bin/env bash
# Instala Pantalla AirPlay en un Chromecast con Google TV por red (adb).
# Uso: ./scripts/install-tv.sh <IP_DE_LA_TV>
set -euo pipefail

IP="${1:?Uso: $0 <IP_DE_LA_TV>}"
cd "$(dirname "$0")/.."

if ! command -v adb >/dev/null 2>&1; then
    if [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    else
        echo "No se encontró adb. Instalalo con:" >&2
        echo "  brew install android-platform-tools" >&2
        exit 1
    fi
else
    ADB=adb
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "No está el APK compilado. Ejecutá primero: ./gradlew assembleDebug" >&2
    exit 1
fi

echo ">> Conectando a $IP:5555"
"$ADB" connect "$IP:5555"
sleep 1

echo ">> Instalando $APK"
"$ADB" -s "$IP:5555" install -r "$APK"

echo ">> Habilitando SYSTEM_ALERT_WINDOW (para traer la app al frente)"
"$ADB" -s "$IP:5555" shell appops set dev.voftec.airplaytv SYSTEM_ALERT_WINDOW allow || true

echo ">> Abriendo la app"
"$ADB" -s "$IP:5555" shell am start -n dev.voftec.airplaytv/.MirrorActivity

echo ">> Listo. En la Mac: Centro de control → Duplicar pantalla → «TV del cuarto»"
