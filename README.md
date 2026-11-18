# Portal — Pantalla para Mac

Convertí tu **Chromecast con Google TV** en un receptor compatible con AirPlay: tu Mac lo
detecta como pantalla inalámbrica desde **Centro de control → Duplicar pantalla**,
sin instalar ninguna app en la Mac. Podés usarlo como **espejo** o como
**pantalla separada** (segundo monitor).

Es una app para Android TV compatible con AirPlay que corre en el Chromecast: la
parte de red y protocolo es un port del servidor C de [UxPlay](https://github.com/FDH2/UxPlay)
y el video/audio se decodifica con MediaCodec/AudioTrack de Android.

## Requisitos

- Un Chromecast con Google TV (probado en el modelo HD, Android TV 12).
- Una Mac en **la misma red Wi-Fi** (no la red de invitados; idealmente 5 GHz).
- `adb` en la Mac (`brew install android-platform-tools`).

## Instalación (sin compilar)

### En el Chromecast con Google TV

1. **Ajustes → Sistema → Acerca de** y tocá **«Compilación del SO de Android TV»**
   **7 veces** con el control. Aparece "Ahora sos desarrollador".
2. Volvé a **Ajustes → Sistema → Opciones para desarrolladores** y activá
   **Depuración USB**.
3. Anotá la IP en **Ajustes → Red e Internet → tu red Wi-Fi**
   (algo como `192.168.x.x`).

### En la Mac

```sh
brew install android-platform-tools
# bajá portal.apk desde Releases del repo (va a ~/Downloads)
./scripts/install-tv.sh 192.168.x.x
```

O a mano:

```sh
adb connect 192.168.x.x:5555
adb install -r ~/Downloads/portal.apk
adb shell am start -n dev.voftec.portal/.MirrorActivity
```

Si la TV pide confirmación, aceptá **«¿Permitir depuración?»** marcando
**«Permitir siempre»** y volvé a correr el script.

### Uso desde la Mac

1. **Centro de control** (barra de menú) → **Duplicar pantalla**.
2. Elegí **«TV del cuarto»** (el nombre se puede cambiar en la app con el control).
3. **Duplicar** para espejo o **«Usar como pantalla separada»** para usarla de
   segundo monitor.

Cuando empieza la duplicación, la TV pasa automáticamente del cartel de espera a
la pantalla espejo; cuando cortás, vuelve al cartel.

Si Portal está en segundo plano, intenta abrir la pantalla espejo. Android TV
puede bloquear esa apertura: tocá la notificación de Portal para verla, o abrí
la app desde el launcher. Permití las notificaciones cuando Android lo solicite;
si están desactivadas, abrí Portal manualmente antes de duplicar.

El receptor arranca solo al encender el Chromecast y queda visible aunque estés
en la pantalla principal de Google TV.

## Si no aparece o no conecta

- La Mac y el Chromecast tienen que estar en la **misma red Wi-Fi** — no en la
  red de invitados, y sin aislamiento de clientes en el router/AP.
- Abrí la app **una vez** después de instalarla (el servicio arranca con ella y
  después queda solo).
- Logs del receptor: `adb logcat -s AirPlayTV`.

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

Necesitás JDK 17, Android SDK (platform 34, build-tools 34, NDK 27.0.12077973, CMake 3.22.1).

```sh
./native/fetch-and-build-deps.sh   # baja y compila OpenSSL 3.0.22 + libplist 2.7.0
./gradlew assembleDebug            # APK en app/build/outputs/apk/debug/
./gradlew assembleRelease bundleRelease
```

Gradle ejecuta `fetch-and-build-deps.sh` automáticamente si faltan los artefactos,
así que alcanza con `./gradlew assembleDebug` desde un checkout limpio. Las
versiones de las dependencias nativas están clavadas con sha256 en
`native/fetch-and-build-deps.sh` y `native/build-openssl.sh`.

### Releases: venta directa y Google Play

El APK queda en `app/build/outputs/apk/release/` y el AAB para Play en
`app/build/outputs/bundle/release/`. Renombrá el APK firmado a `portal.apk`
para distribuirlo directamente. El APK y el AAB se generan sin firma si no
están configurados los cuatro valores siguientes:

| Variable de entorno | Clave en `keystore.properties` |
| --- | --- |
| `RELEASE_STORE_FILE` | `storeFile` |
| `RELEASE_STORE_PASSWORD` | `storePassword` |
| `RELEASE_KEY_ALIAS` | `keyAlias` |
| `RELEASE_KEY_PASSWORD` | `keyPassword` |

Las variables tienen prioridad. El archivo opcional `keystore.properties` va
en la raíz; `storeFile` es una ruta absoluta o relativa a esa raíz (en Windows,
usá barras `/`). No subas claves ni contraseñas al repo: el archivo y los
keystores están ignorados. Conservá tu clave para futuras actualizaciones.
Para Play, usá la clave de carga de Play App Signing.

CI mantiene debug y lint y publica también APK y AAB release. Para firmarlos,
configurá los secrets `RELEASE_KEYSTORE_BASE64` (contenido del keystore en
base64), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` y
`RELEASE_KEY_PASSWORD`. Si falta alguno, CI genera artefactos sin firma
(no instalables/publicables hasta firmarlos).

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
