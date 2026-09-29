# Pantalla AirPlay

Convertí tu **Chromecast con Google TV** en un receptor AirPlay nativo: tu Mac lo
detecta como pantalla inalámbrica desde **Centro de control → Duplicar pantalla**,
sin instalar ninguna app en la Mac. Podés usarlo como **espejo** o como
**pantalla separada** (segundo monitor).

Es una app para Android TV que corre un servidor AirPlay en el Chromecast: la
parte de red y protocolo es un port del servidor C de [UxPlay](https://github.com/FDH2/UxPlay)
y el video/audio se decodifica con MediaCodec/AudioTrack de Android.

## Requisitos

- Un Chromecast con Google TV (probado en el modelo HD, Android TV 12).
- Una Mac en **la misma red Wi-Fi** (no la red de invitados; idealmente 5 GHz).
- `adb` en la Mac (`brew install android-platform-tools`).

## Paso 1: habilitar opciones de desarrollador en el Chromecast

1. En la TV: **Configuración → Sistema → Información**.
2. Bajá hasta **"Compilación del SO de Android TV"** y apretá **OK 7 veces**
   con el control. Aparece "Ahora sos desarrollador".
3. Volvé a **Configuración → Sistema → Opciones para desarrolladores** y
   activá **Depuración USB** (habilita adb por red en el puerto 5555).

## Paso 2: encontrar la IP de la TV

**Configuración → Red e Internet → (tu Wi-Fi)** muestra la IP. Suele ser algo
como `192.168.x.x`.

## Paso 3: instalar la app

```sh
./gradlew assembleDebug        # compila el APK (ver "Compilar desde cero")
./scripts/install-tv.sh 192.168.x.x
```

El script conecta por adb, instala el APK, habilita `SYSTEM_ALERT_WINDOW` (para
que la app pueda mostrarse al frente cuando empieza la duplicación) y abre la app.

## Uso desde la Mac

1. Abrí **Centro de control** (barra de menú) → **Duplicar pantalla**.
2. Elegí **«TV del cuarto»** (el nombre se puede cambiar en la app con el control).
3. Elegí **Duplicar** o **Usar como pantalla separada**.

Cuando empieza la duplicación, la TV pasa automáticamente del cartel de espera a
la pantalla espejo; cuando cortás, vuelve al cartel.

El receptor arranca solo al encender el Chromecast y queda visible aunque estés
en la pantalla principal de Google TV.

## Consejos

- La Mac y el Chromecast tienen que estar en la **misma red Wi-Fi** — no en la
  red de invitados, y con el aislamiento de clientes AP desactivado.
- Preferí Wi-Fi **5 GHz**: el espejo 1080p es muy sensible a la congestión.
- Si usás la TV como segundo monitor, un mouse/teclado Bluetooth emparejados a
  la Mac ayudan a llevar el puntero a esa pantalla.
- El nombre del dispositivo y la dirección MAC (aleatoria, administrada
  localmente) se guardan entre reinicios; la identidad de emparejamiento AirPlay
  también (`airplay.pem`), así que la Mac no te vuelve a pedir el PIN.

## Compilar desde cero

Necesitás JDK 17, Android SDK (platform 34, build-tools 34, NDK 26.3, CMake 3.22).

```sh
./native/fetch-and-build-deps.sh   # baja y compila OpenSSL 3.0.22 + libplist 2.7.0
./gradlew assembleDebug            # APK en app/build/outputs/apk/debug/
```

Gradle ejecuta `fetch-and-build-deps.sh` automáticamente si faltan los artefactos,
así que alcanza con `./gradlew assembleDebug` desde un checkout limpio. Las
versiones de las dependencias nativas están clavadas con sha256 en
`native/fetch-and-build-deps.sh` y `native/build-openssl.sh`.

## Créditos

- **[UxPlay](https://github.com/FDH2/UxPlay)** (GPL-3.0): el servidor AirPlay
  (`lib/`, vendored en `app/src/main/cpp/uxplay/`, commit `b3202df`) que hace
  todo el trabajo de protocolo, mDNS (mdnsd interno), FairPlay vía `playfair` y
  parsing HTTP vía `llhttp`.
- **[RPiPlay](https://github.com/FD-/RPiPlay)** y **[dsafa22](https://github.com/dsafa22)**
  (antepasados del protocolo de espejo).
- **[playfair](https://github.com/EstebanKubata/playfair)** ( handshake FairPlay
  de Apple, incluido en UxPlay).
- **[shairplay](https://github.com/juhovh/shairplay)** (base original del
  servidor RAOP/AirTunes).

Licencia: **GPL-3.0** (ver `LICENSE`), por ser derivado de UxPlay.
