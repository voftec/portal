#!/usr/bin/env bash
# Instala Pantalla AirPlay en un Chromecast con Google TV por red (adb).
# Uso: ./scripts/install-tv.sh <IP_DE_LA_TV> [ruta/al.apk]
set -euo pipefail

IP="${1:?Uso: $0 <IP_DE_LA_TV> [ruta/al.apk]}"
cd "$(dirname "$0")/.."

if ! command -v adb >/dev/null 2>&1; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    else
        echo "No se encontró adb. Instalalo con:" >&2
        echo "  brew install android-platform-tools" >&2
        exit 1
    fi
else
    ADB=adb
fi

if [ -n "${2:-}" ]; then
    APK="$2"
elif [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK="app/build/outputs/apk/debug/app-debug.apk"
elif [ -f "$HOME/Downloads/pantalla-airplay.apk" ]; then
    APK="$HOME/Downloads/pantalla-airplay.apk"
else
    echo "No se encontró el APK. Pasalo como segundo argumento o bajalo" >&2
    echo "a ~/Downloads/pantalla-airplay.apk desde Releases del repo." >&2
    exit 1
fi
echo ">> APK: $APK"

echo ">> Conectando a $IP:5555"
"$ADB" connect "$IP:5555"
sleep 1

STATE=$("$ADB" -s "$IP:5555" get-state 2>/dev/null || true)
if [ "$STATE" = "unauthorized" ]; then
    echo "Aceptá «¿Permitir depuración?» en la TV (marcá «Permitir siempre») y volvé a correr el script" >&2
    exit 1
fi
if [ "$STATE" != "device" ]; then
    echo "La TV no respondió (estado: ${STATE:-sin conexión}). Revisá que esté" >&2
    echo "en la misma red y que la depuración USB esté activada." >&2
    exit 1
fi

echo ">> Instalando $APK"
"$ADB" -s "$IP:5555" install -r "$APK"

echo ">> Habilitando SYSTEM_ALERT_WINDOW (para traer la app al frente)"
"$ADB" -s "$IP:5555" shell appops set dev.voftec.airplaytv SYSTEM_ALERT_WINDOW allow || true

echo ">> Abriendo la app"
"$ADB" -s "$IP:5555" shell am start -n dev.voftec.airplaytv/.MirrorActivity

echo ">> Listo. En la Mac: Centro de control → Duplicar pantalla → «TV del cuarto»"
