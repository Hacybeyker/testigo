# Guía paso a paso

Esta guía lleva de cero a la primera corrida con evidencia: instalar el CLI, preparar la máquina, crear la suite, preparar la app, escribir el primer flow y ejecutarlo. Cada paso dice qué hacer, por qué y cómo comprobar que quedó bien. La referencia de cada opción y de cada clave de `testigo.yaml` está en la [guía de uso](USO.md).

**Contenido**

1. [Cómo encaja todo](#1-cómo-encaja-todo)
2. [Instalar el CLI](#2-instalar-el-cli)
3. [Preparar la máquina con `testigo setup`](#3-preparar-la-máquina-con-testigo-setup)
4. [Preparar el build de la app](#4-preparar-el-build-de-la-app)
5. [Preparar el dispositivo](#5-preparar-el-dispositivo)
6. [Crear la suite con `testigo init`](#6-crear-la-suite-con-testigo-init)
7. [Escribir los flows de Maestro](#7-escribir-los-flows-de-maestro)
8. [Revisar todo con `testigo doctor`](#8-revisar-todo-con-testigo-doctor)
9. [Ejecutar](#9-ejecutar)
10. [Leer y compartir la evidencia](#10-leer-y-compartir-la-evidencia)
11. [Uso diario y CI](#11-uso-diario-y-ci)
12. [Preguntas frecuentes](#12-preguntas-frecuentes)
13. [Problemas comunes](#13-problemas-comunes)

## 1. Cómo encaja todo

Hay tres cosas, cada una en su lugar:

```
~/.testigo/cli/                 el CLI: se instala una vez por máquina (con install.sh)
~/proyectos/MiApp/              tu app, como siempre: testigo no toca este repo
└── app/build/outputs/apk/…     el APK o AAB que generas con Gradle
~/proyectos/MiApp-e2e/          la suite: los tests y su configuración
├── testigo.yaml                dónde está el build, qué flows, qué capturar
├── .env.testigo                usuarios de prueba (no se sube al repo)
├── flows/                      los tests de Maestro, en YAML
└── evidence/                   lo que deja cada corrida (no se sube al repo)
```

Cuando ejecutas `testigo run` desde la carpeta de la suite:

1. Lee `testigo.yaml` y revisa que los flows cumplan la política local-only.
2. Instala el build en el dispositivo, solo si cambió.
3. Ejecuta cada flow con Maestro, uno por uno, mientras graba la pantalla y el logcat.
4. Al terminar cada flow, copia la base de Chucker de la app para sacar los requests y responses.
5. Escribe todo en `evidence/` y un `summary.md` para pegar en el PR o el ticket.

Nada de tu app sale de tu máquina: el runner solo habla con el dispositivo por adb y con `localhost`. Solo usan internet el instalador, para bajar `testigo` de sus releases en GitHub, y `testigo setup`, para descargar herramientas oficiales cuando lo confirmas.

## 2. Instalar el CLI

No hace falta tener nada instalado antes, ni siquiera Java: si falta, el [paso 3](#3-preparar-la-máquina-con-testigo-setup) lo resuelve.

### Con una línea (lo normal)

En macOS o Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | bash
```

```
Descargando testigo-0.3.0.tar.gz de github.com/Hacybeyker/testigo…
✔ testigo 0.3.0 en ~/.testigo/cli/0.3.0
✔ ~/.local/bin/testigo → ~/.testigo/cli/current/bin/testigo
✔ testigo version 0.3.0
  ~/.local/bin no está en tu PATH: testigo setup lo resuelve.

Siguiente: ~/.local/bin/testigo setup
  Revisa Java, Maestro, el Android SDK y el PATH, y te pregunta antes de cambiar algo.
```

El instalador baja la última versión de [Releases](https://github.com/Hacybeyker/testigo/releases) por HTTPS y verifica su SHA-256: si no coincide, no instala nada. Deja cada versión en su carpeta, `~/.testigo/cli/<versión>`, con `~/.testigo/cli/current` apuntando a la activa y el comando `testigo` en `~/.local/bin`. No usa sudo, no edita el perfil de tu terminal y no envía nada: el PATH lo configura `testigo setup`, en el paso 3. Necesita `curl` y `tar` (o `unzip`); si falta alguno, dice cómo instalarlo. Si quieres leerlo antes de correrlo, es [`install.sh`](../install.sh). Cada release trae además `SHA256SUMS` y la attestation de sus archivos, firmada por el workflow que los compiló: con [GitHub CLI](https://cli.github.com), `gh attestation verify testigo-0.3.0.tar.gz -R Hacybeyker/testigo` confirma que el archivo salió de este repositorio.

| Variable | Para qué |
| --- | --- |
| `TESTIGO_VERSION` | Una versión en particular, por ejemplo `0.2.0`. Por defecto, la última publicada. |
| `TESTIGO_ARCHIVE` | Instalar un `.tar.gz` o un `.zip` que ya tienes, con su `.sha256` al lado, sin descargar nada. |
| `TESTIGO_HOME` | Otra carpeta en lugar de `~/.testigo`. |
| `TESTIGO_BIN_DIR` | Otra carpeta para el enlace `testigo`, en lugar de `~/.local/bin`. |

La variable va antes de `bash`: `curl -fsSL … | TESTIGO_VERSION=0.2.0 bash`.

### Desde un archivo (sin internet, o el que comparte tu equipo)

Con el `.tar.gz` (o el `.zip`) y su `.sha256` de [Releases](https://github.com/Hacybeyker/testigo/releases), o los que generas al compilar, y una copia de [`install.sh`](../install.sh):

```bash
TESTIGO_ARCHIVE=testigo-0.3.0.tar.gz sh install.sh     # verifica el SHA-256 e instala como la línea de arriba
```

Si prefieres no usar el instalador, descomprímelo donde quieras y, en el paso 3, ejecuta su `bin/testigo setup` con la ruta completa:

```bash
shasum -a 256 -c testigo-0.3.0.zip.sha256              # el zip llegó entero
unzip testigo-0.3.0.zip -d ~/herramientas              # cualquier carpeta sirve
```

### Compilarlo desde el código

Para compilar sí hace falta Java 17 o más (`./gradlew` lo usa):

```bash
cd testigo
./gradlew build                                                            # tests, y el .tar.gz y el .zip en build/distributions/
TESTIGO_ARCHIVE=build/distributions/testigo-0.3.0-dev.tar.gz sh install.sh  # instálalo como uno publicado
```

### Actualizar el CLI

Con la misma línea de la instalación:

```bash
curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | bash
```

La versión nueva queda al lado de la anterior y `cli/current` pasa a apuntar a ella. Como `testigo setup` puso `~/.testigo/cli/current/bin` en el PATH, una terminal nueva ya usa la nueva sin hacer nada más, y el instalador termina con «Listo: tu terminal ya usa esta versión». No se toca nada más: tus suites, el Java que descargó `testigo setup`, Maestro, el Android SDK ni la configuración de la terminal.

Se conservan la versión activa y la anterior: para volver, `curl -fsSL … | TESTIGO_VERSION=<la anterior> bash`. En tu suite, `testigo doctor` confirma que todo sigue bien; si el equipo fijó otra versión de Maestro, lo dice. Si la versión nueva trae cambios en la skill de Claude Code, `testigo setup` ofrece actualizar tu copia ([Flows con IA](FLOWS-CON-IA.md#instalar)).

**Si instalaste un zip a mano** (por ejemplo, en `~/herramientas/testigo-0.2.0`), o una versión anterior a la 0.3.0 configuró tu terminal: después de la línea, el instalador avisa que tu terminal todavía usa la otra y pide un `testigo setup` más, con la ruta completa. Ese setup pasa la terminal a `~/.testigo/cli/current`; después borra la carpeta vieja.

### Desinstalar

```bash
curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | bash -s -- --uninstall
```

Quita `~/.testigo/cli` y el enlace `~/.local/bin/testigo`. No toca Java (`~/.testigo/jdk`), Maestro, el Android SDK ni tus suites. Para quitar también la configuración de la terminal, ver [¿Hace falta root o sudo?](#hace-falta-root-o-sudo).

## 3. Preparar la máquina con `testigo setup`

`testigo setup` revisa lo que `testigo` necesita en tu máquina, te muestra qué falta y, con tu confirmación, lo instala. La primera vez se ejecuta con el comando que te indicó el instalador, porque `testigo` todavía no está en el PATH (en Linux, donde `~/.local/bin` suele estarlo, basta `testigo setup`):

```bash
~/.local/bin/testigo setup
```

```
Esta máquina
  ✔ Java        21.0.12.1 · ~/.testigo/jdk/current · lo encontró testigo; tu terminal no tiene JAVA_HOME
  ✖ Maestro     no está instalado
  ✔ Android SDK ~/Library/Android/sdk
  ✔ adb         ~/Library/Android/sdk/platform-tools/adb
  ✖ aapt2       falta Android SDK Build-Tools: hace falta para leer APKs e instalar AABs
  ! Debug key   no existe ~/.android/debug.keystore: hace falta para probar un AAB
  ! Terminal    falta JAVA_HOME, MAESTRO_*, maestro en el PATH, testigo en el PATH

Qué hago en esta máquina
❯ ✓ Maestro       2.11.0 · descarga 315 MB de github.com a ~/.maestro
  ✓ Android SDK   build-tools 36.0.0 (aapt2) en ~/Library/Android/sdk, con el sdkmanager del SDK
  ✓ Debug key     crea ~/.android/debug.keystore para instalar AABs; local, no descarga nada
  ✓ Terminal      escribe ~/.testigo/env.sh y una línea en ~/.zshrc · JAVA_HOME, MAESTRO_*, …
```

1. **Elige** con **Espacio** qué hacer (todo viene marcado) y confirma con **Enter**.
2. **Revisa** el detalle: qué se descarga, de dónde, y qué líneas se escriben en cada archivo.
3. **Confirma** con *Sí, hacerlo*. Con *No* o con Ctrl+C no cambia nada.
4. **Abre una terminal nueva** al terminar. Desde ahí, `testigo`, `maestro` y `adb` funcionan desde cualquier carpeta.

Qué revisa y qué hace con cada cosa:

| Qué | Si falta |
| --- | --- |
| **Java 17 o más** | Usa el que encuentre: el de `JAVA_HOME`, el del PATH, uno registrado en macOS o el de Android Studio. Si no hay ninguno, `bin/testigo` ofrece descargar Java 21 (Eclipse Temurin, unos 200 MB) en `~/.testigo/jdk` antes de empezar. |
| **Maestro** | Descarga del release oficial de GitHub la versión del `testigo.yaml` de la carpeta actual (o la 2.11.0) y la deja en `~/.maestro`, igual que su instalador. Si ya tienes Maestro, solo lo cambia si `testigo.yaml` fija otra versión y fue `testigo` quien lo instaló. |
| **Android SDK**: Platform-Tools (`adb`) y Build-Tools (`aapt2`) | Si tienes el SDK de Android Studio, agrega solo lo que falta con su `sdkmanager`. Si no hay SDK, descarga las command-line tools de Google en `~/Library/Android/sdk` (en Linux, `~/Android/Sdk`) y con ellas instala lo demás. |
| **Debug key** (`~/.android/debug.keystore`) | La crea con `keytool`, igual que Android Studio. Con ella se firman los APKs que salen de un AAB. |
| **Terminal** | Escribe `~/.testigo/env.sh` con `JAVA_HOME`, `ANDROID_HOME`, las variables `MAESTRO_*` que dejan a Maestro sin analytics, y las carpetas de `testigo`, `maestro` y `adb` en el PATH (para `testigo`, `~/.testigo/cli/current/bin`, que sigue a cada actualización). Agrega al final de `~/.zshrc` una línea que lo carga, y antes guarda una copia del original en `~/.zshrc.testigo-backup`. |

**Licencia del Android SDK.** Antes de instalar, `sdkmanager` muestra la licencia de Google y pregunta si la aceptas: escribe `y`. `testigo` nunca la acepta por ti; si dices que no, no se instala nada.

**Lo que se descarga.** Cada archivo sale de su sitio oficial, por HTTPS, y se verifica con su SHA-256 antes de usarlo. Si no coincide, se borra sin usar.

| Herramienta | Desde | SHA-256 |
| --- | --- | --- |
| Java 21 (Eclipse Temurin 21.0.12.1) | `github.com/adoptium/temurin21-binaries` | Fijado en `bin/testigo`. |
| Maestro | `github.com/mobile-dev-inc/maestro/releases` | Fijado en `testigo` para la 2.11.0; para otra versión, el `checksums_sha256.txt` del mismo release. |
| Android SDK command-line tools | `dl.google.com/android/repository` | Fijado en `testigo`, el que publica developer.android.com. |
| Platform-Tools y Build-Tools | `dl.google.com`, con `sdkmanager` | Lo verifica `sdkmanager`. |

### ¿Hace falta root o sudo?

No. `testigo setup` nunca pide root ni usa `sudo`: todo queda dentro de tu carpeta de usuario, en archivos que ya son tuyos.

| Qué | Dónde queda |
| --- | --- |
| testigo (con `install.sh`) | `~/.testigo/cli` y el enlace `~/.local/bin/testigo` |
| Java | `~/.testigo/jdk` |
| Maestro | `~/.maestro` |
| Android SDK | `~/Library/Android/sdk` (macOS) o `~/Android/Sdk` (Linux) |
| Debug key | `~/.android/debug.keystore` |
| Variables de la terminal | `~/.testigo/env.sh` y una línea en tu `~/.zshrc` (o `~/.bash_profile`, `~/.bashrc` o `~/.config/fish/conf.d/testigo.fish`, según tu terminal) |

Para deshacerlo, borra el bloque entre `# >>> testigo >>>` y `# <<< testigo <<<` de tu `~/.zshrc`, y las carpetas de la tabla. testigo se quita con `install.sh --uninstall` ([sección 2](#desinstalar)).

### Si tu empresa restringe la máquina

- **No puedes editar `~/.zshrc`:** `testigo setup` lo avisa y deja igual `~/.testigo/env.sh`. Cárgalo en cada terminal con `. ~/.testigo/env.sh`, o agrega esa línea al archivo de inicio que sí puedas editar. Sin `~/.testigo`, `eval "$(testigo setup --print-env)"` hace lo mismo.
- **No te dejan instalar Java:** si tienes Android Studio, `testigo` usa el Java que trae (`jbr`) sin instalar nada ni definir `JAVA_HOME`; lo busca en `/Applications` y en `~/Applications`, también en las versiones Preview. Si Android Studio está en otra carpeta, apunta `JAVA_HOME` a su `jbr`, por ejemplo `export JAVA_HOME="/ruta/Android Studio.app/Contents/jbr/Contents/Home"` (en Linux, `…/android-studio/jbr`). `testigo setup` solo ofrece descargar Java si no encuentra ninguno, y nunca sin preguntar.
- **No quieres tocar nada de la terminal:** no hace falta. `testigo` encuentra Java (incluido el de Android Studio), Maestro y el Android SDK en sus carpetas por defecto aunque no estén en el PATH. Basta con ejecutarlo con su ruta completa, o con un alias.
- **No se puede descargar nada, o hay proxy:** las descargas usan `curl`, que respeta `HTTPS_PROXY`. Si están bloqueadas, instala Java, Maestro o Android Studio por el canal de tu empresa (Self Service, Homebrew o lo que usen) y vuelve a ejecutar `testigo setup`: lo detecta y solo configura lo que falte.
- **Solo quieres ver qué falta:** `testigo setup --check` no instala ni cambia nada y termina con código 2 si falta algo que `testigo` necesita.

### Instalar todo a mano

Si prefieres no usar `testigo setup`:

```bash
# Maestro, con su instalador oficial y la versión del equipo:
export MAESTRO_VERSION=2.11.0; curl -Ls "https://get.maestro.mobile.dev" | bash
```

El Android SDK, con Android Studio: en *Tools › SDK Manager › SDK Tools*, marca **Android SDK Platform-Tools** y **Android SDK Build-Tools**. Luego agrega esto al final de `~/.zshrc` y abre una terminal nueva:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # o tu JDK 17+
export ANDROID_HOME="$HOME/Library/Android/sdk"                                   # en Linux, ~/Android/Sdk
export PATH="$HOME/.testigo/cli/current/bin:$HOME/.maestro/bin:$ANDROID_HOME/platform-tools:$PATH"   # o el bin/ de donde descomprimiste testigo
# Maestro sin analytics ni consultas a sus servidores, también fuera de testigo:
export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_DISABLE_UPDATE_CHECK=true
export MAESTRO_API_URL=http://127.0.0.1:9
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true
```

`testigo` siempre ejecuta Maestro con esas variables. Hacen falta en la terminal solo si usas `maestro` directamente, por ejemplo `maestro hierarchy`.

**En Windows**, `testigo setup` todavía solo revisa. Instala Java 17 o más, Maestro y Android Studio a mano, descomprime el zip de [Releases](https://github.com/Hacybeyker/testigo/releases) (por ejemplo en `C:\herramientas`) y agrega `C:\herramientas\testigo-0.3.0\bin` a la variable `Path` en *Configuración › Sistema › Información › Configuración avanzada del sistema › Variables de entorno*. Windows todavía no se ha probado.

### Comprobar

En una terminal nueva:

```bash
command -v testigo       # ~/.testigo/cli/current/bin/testigo
testigo --version        # testigo version 0.3.0
testigo --help           # los comandos: setup, init, doctor, list y run
testigo setup --check    # todo en ✔
```

## 4. Preparar el build de la app

`testigo` prueba el build que tu equipo ya genera, sin cambiar el código de producción. Sirve cualquier build type o flavor (`debug`, `qa`, `staging`, `release`…): no se fija en el nombre, solo en el archivo. Lo que importa es cómo está configurado:

| Para | Qué necesita el build |
| --- | --- |
| Instalarlo y correr los flows | Un APK firmado, o un AAB: `testigo` lo convierte y lo firma con `app.signing` o con tu debug key. |
| Capturar la red | La librería de Chucker (no `library-no-op`) en el `OkHttpClient` de la app, y que el build sea *debuggable*, para leer su base con `run-as`. Sin *debuggable*, solo en un emulador sin Play Store y con `adb root`. |
| Que el video y las capturas no salgan en negro | Pantallas sin `FLAG_SECURE`: Android las oculta en cualquier grabación. El flow igual corre. |
| Seleccionar por `id` en Compose | `Modifier.semantics { testTagsAsResourceId = true }` en la raíz, para que Maestro vea los `testTag` como ids. |
| Correr en un emulador | Que la app no se cierre al detectar un emulador, root o la depuración. |
| Logs y crashes | Nada especial. |

Además, conviene que apunte al backend de QA, con usuarios de prueba ficticios y sin OTP, 2FA ni captcha (o con códigos fijos para ellos).

**Con un build `release`** (o cualquiera que no sea *debuggable*) funciona, pero normalmente se pierde la red: no es *debuggable* y suele llevar Chucker en `no-op`. Si tiene `FLAG_SECURE`, el video sale en negro. Si en el dispositivo ya hay una versión firmada con otra llave, `testigo` pregunta antes de desinstalarla, porque se pierden sus datos. La ofuscación de código (R8, ProGuard) no afecta a Maestro, que trabaja sobre la interfaz; sí afecta la que también renombra los recursos, porque los `id` dejan de coincidir. `testigo run` y `testigo doctor` avisan cuando el build no es *debuggable*.

### Agregar Chucker para capturar la red

Chucker registra cada request y response de OkHttp dentro de la app; el runner lo lee al terminar cada flow. Va con la librería completa en el build type que pruebas y con la versión vacía (`no-op`) en el que publicas:

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("com.github.chuckerteam.chucker:library:4.3.1")
    releaseImplementation("com.github.chuckerteam.chucker:library-no-op:4.3.1")
}
```

Si pruebas otro build type (por ejemplo `qa` o `staging`), usa también `qaImplementation(...)` o `stagingImplementation(...)` con la librería completa.

Luego agrega el interceptor al `OkHttpClient` compartido de la app, como interceptor global. Si algún módulo crea su propio cliente, agrégaselo también; si no, sus llamadas no aparecen:

```kotlin
val chucker = ChuckerInterceptor.Builder(context)
    .collector(ChuckerCollector(context))
    .maxContentLength(1_000_000L)          // bodies grandes completos, sin truncar
    .alwaysReadResponseBody(true)          // también los que la app no lee completos
    .build()

val client = OkHttpClient.Builder()
    .addInterceptor(authInterceptor)       // primero el que agrega Authorization…
    .addInterceptor(chucker)               // …así Chucker ve el request completo
    .build()
```

Con `addInterceptor`, Chucker ve el request tal como lo arma la app hasta ese punto: si va antes del interceptor de autenticación, el header `Authorization` no aparece. Con `addNetworkInterceptor` ve lo que realmente sale por la red.

Si la app no usa OkHttp (por ejemplo Flutter) o no puede llevar Chucker, igual tienes video, logs y el reporte de Maestro: pon `network.capture: none` en `testigo.yaml`.

### Generar el APK o el AAB

Con Gradle, como siempre. El nombre de la tarea depende de tus variantes:

```bash
cd ~/proyectos/MiApp
./gradlew assembleDebug      # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleDebug        # AAB → app/build/outputs/bundle/debug/app-debug.aab
./gradlew assembleQaDebug    # con un flavor qa → app/build/outputs/apk/qa/debug/app-qa-debug.apk
```

Sirve cualquiera de los dos formatos. Un AAB lo convierte el runner, en tu máquina, en los APKs que necesita el dispositivo.

## 5. Preparar el dispositivo

Sirve un teléfono o un emulador:

- **Teléfono:** activa *Opciones de desarrollador › Depuración USB* y acepta el aviso "¿Permitir depuración USB?" al conectarlo. En Xiaomi (MIUI o HyperOS) activa además *Instalar vía USB* y *Depuración USB (ajustes de seguridad)*.
- **Emulador:** en Android Studio, *Device Manager › Create Virtual Device*, con una imagen **Google APIs**. Esa imagen permite `adb root`, que sirve si algún día el build no es debuggable.

Comprueba que se ve:

```bash
adb devices              # una línea con el serial y "device"
```

La primera vez, Maestro instala en el dispositivo su driver (`dev.mobile.maestro`); en Xiaomi hay que confirmar esa instalación en el teléfono.

## 6. Crear la suite con `testigo init`

La suite vive fuera del repo de la app, por ejemplo en una carpeta hermana. Créala y ejecuta `testigo init` dentro:

```bash
mkdir -p ~/proyectos/MiApp-e2e
cd ~/proyectos/MiApp-e2e
testigo init
```

`testigo init` busca los builds de Gradle que hay cerca (en esta carpeta y en las carpetas de al lado, como `../MiApp/app/build/outputs`) y pregunta cuál vas a probar. También puedes escribir otra ruta, o dejarla pendiente: `testigo run` la pide después. Del build lee el package de la app y si trae Chucker.

```
App que vas a probar (APK o AAB) · builds encontrados cerca de esta carpeta
❯ ../MiApp/app/build/outputs/apk/debug/app-debug.apk         APK · hace 2 h · 18 MB
  ../MiApp/app/build/outputs/bundle/qaDebug/app-qa-debug.aab  AAB · hace 1 d · 15 MB
  Otra ruta: la escribo
  Todavía no tengo el build: testigo run la pide

✔ App         ../MiApp/app/build/outputs/apk/debug/app-debug.apk · com.miempresa.app.qa · 3.4.0 (340) · APK
✔ Red         con Chucker: lo encontré en el build

  ✔ testigo.yaml
  ✔ flows/demo/abrir_app.yaml
  ✔ .env.testigo
  ✔ .gitignore

Siguiente: testigo doctor
```

Crea cuatro cosas, y nunca pisa un archivo que ya existe (puedes volver a ejecutarlo para completar lo que falte):

| Archivo | Qué es |
| --- | --- |
| `testigo.yaml` | La configuración de la suite, con todos los valores por defecto y un comentario en cada línea. |
| `flows/demo/abrir_app.yaml` | Un flow de demo que abre la app y espera a que cargue. Pasa con cualquier app: sirve para comprobar que todo funciona. Después cámbialo o bórralo. |
| `.env.testigo` | Para los usuarios de prueba, todavía vacío ([abajo](#usuarios-de-prueba-envtestigo)). |
| `.gitignore` | Con `.env.testigo` y `evidence/`, para que no terminen en un commit. |

El `testigo.yaml` queda así:

```yaml
version: 1

app:
  artifact: ../MiApp/app/build/outputs/apk/debug/app-debug.apk   # el APK o AAB que se prueba
  package: auto                   # auto: se lee del build

flows: flows/                     # la carpeta de los flows de Maestro

maestro:
  version: "2.11.0"               # la del equipo: testigo doctor avisa si la instalada es otra

defaults:
  profile: dev                    # qué capturar sin flags: smoke, dev o evidencia

network:
  capture: chucker                # la red sale de Chucker; none si la app no lo trae
  format: txt                     # txt, har o ambos
  hosts: []                       # tu backend de QA, por ejemplo [api.qa.miempresa.com]

video:
  bitrate_mbps: 4                 # de 4 a 8: más alto, más nítido y más pesado

redact:
  enabled: false                  # true: enmascara tokens y datos personales y corre el leak scan

evidence:
  dir: evidence/                  # no se sube al repo: testigo le crea su propio .gitignore
```

Lo único que conviene completar a mano es `network.hosts`, con el dominio de tu backend de QA: sus llamadas lentas o con error entran a las alertas del `summary.md`. Sin terminal (en un script), `testigo init --app <ruta> --network chucker` hace lo mismo sin preguntar, y `--dir` crea la suite en otra carpeta.

**Cómo se indican las rutas.** Las de `testigo.yaml` son relativas a la carpeta del propio `testigo.yaml`, o absolutas. Por eso `../MiApp/...` llega al repo de la app que está al lado. Las de los flags (`--app`, `--flows`) son relativas a la carpeta donde ejecutas el comando.

| Qué | Dónde se indica | Ejemplo |
| --- | --- | --- |
| El APK o AAB | `app.artifact`, o `--app` en una corrida | `../MiApp/app/build/outputs/bundle/qaDebug/app-qa-debug.aab` |
| La carpeta de los flows | `flows`, o `--flows` (se puede repetir) | `flows/`, o un solo archivo: `flows/login.yaml` |
| La carpeta de la evidencia | `evidence.dir`, o `--out` | `evidence/` (por defecto) |

Sin `app.artifact`, el modo interactivo te pide la ruta (y te ofrece guardarla) y, con flags, hay que pasar `--app`. Con `app.artifact`, el modo interactivo además muestra los otros builds que encuentra en el mismo `build/outputs` de Gradle, del más nuevo al más viejo, para elegir otra variante sin escribir la ruta.

`testigo.yaml` se edita a mano cuando quieras. La lista completa de claves, con sus valores por defecto, está en la [guía de uso](USO.md#configuración-del-proyecto-testigoyaml).

### Usuarios de prueba: `.env.testigo`

Las credenciales nunca van en los flows (el runner bloquea una corrida que las tenga escritas). Van en `.env.testigo`, junto a `testigo.yaml`; `testigo init` lo crea con un ejemplo comentado:

```bash
USER=qa_usuario_01
PASSWORD=ClaveDePrueba123
```

Y llegan a los flows como `${USER}` y `${PASSWORD}`.

### No subir datos al repo

`testigo init` deja un `.gitignore` con estas dos líneas (si ya había uno, las agrega al final):

```
.env.testigo
evidence/
```

El runner igual le crea a `evidence/` su propio `.gitignore`, y `testigo doctor` avisa si `.env.testigo` no está ignorado.

## 7. Escribir los flows de Maestro

**Sí, los tests los escribes tú** (el de demo que deja `testigo init` solo abre la app), en el formato YAML de Maestro: una lista de pasos como "toca", "escribe" y "verifica que se ve". No se compilan ni van en el repo de la app. Cada archivo es un flow, es decir, un caso de usuario.

Crea `flows/login.yaml`:

```yaml
appId: com.miempresa.app.qa     # el package de tu build
name: login                     # nombre del flow en la evidencia
tags: [login, smoke]            # para elegir qué correr con --tags
---
- launchApp:
    clearState: true            # empieza siempre desde cero
- tapOn:
    id: "input_usuario"         # resource-id de la vista
- inputText: ${USER}            # viene de .env.testigo
- tapOn:
    id: "input_clave"
- inputText: ${PASSWORD}
- tapOn: "Ingresar"             # o por texto visible
- assertVisible: "Hola"         # si no aparece, el flow falla
```

Reglas que conviene seguir:

- **Un flow, un caso**, de menos de 3 minutos.
- **Header completo:** `appId`, `name` y `tags`, con la feature y `smoke` o `regresion`.
- **Selectores por `id`** (el resource-id de la vista). Usa `text` solo para textos que no cambian, y nunca coordenadas.
- **Login reutilizable:** ponlo en `flows/subflows/login.yaml` y llámalo con `runFlow: ../subflows/login.yaml`. Lo que está en `subflows/` no se ejecuta solo, pero Maestro le exige lo mismo que a un flow: empieza con `appId: …` y `---`, y debajo van los comandos. Si falta, `testigo run --dry-run` y `testigo doctor` te dicen qué archivo y qué agregar.
- **Prohibido** (la corrida termina con código 3): los comandos de IA (`assertWithAI` y parecidos), `http` hacia hosts externos en JavaScript, y contraseñas o tokens escritos en el YAML.

**Para encontrar los IDs**, abre el *Layout Inspector* de Android Studio con la app corriendo: muestra el resource-id de cada vista. `maestro hierarchy` también los lista (en una terminal configurada por `testigo setup`, o con las variables de [Instalar todo a mano](#instalar-todo-a-mano)). Para apps con datos sensibles, no uses Maestro Studio: no está claro qué envía.

La referencia de los comandos de Maestro está en su [documentación](https://docs.maestro.dev).

**Con Claude Code**, la skill `testigo-flows` que viene con testigo escribe los flows por ti y los valida en el dispositivo, también en apps sin ids. `testigo setup` ofrece copiarla; qué pedirle está en [Flows con IA](FLOWS-CON-IA.md).

## 8. Revisar todo con `testigo doctor`

**Es el primer comando** una vez que tienes la suite. Revisa todo lo que `testigo run` va a necesitar y no instala ni cambia nada:

```bash
cd ~/proyectos/MiApp-e2e
testigo doctor
```

```
Herramientas
  ✔ testigo     0.3.0 · instalado con install.sh · ~/.testigo/cli/0.3.0
  ✔ Java        21.0.8 · JAVA_HOME · …
  ✔ Maestro     2.11.0 · ~/.maestro/bin/maestro
  ✔ Fijada      2.11.0, la de testigo.yaml
  ✔ adb         36.0.0 · ~/Library/Android/sdk/platform-tools/adb
  ✔ aapt2       build-tools 36.1.0 · …
  ✔ bundletool  1.18.3 · ~/.testigo/cli/0.3.0/tools/…

Dispositivo
  ✔ Conectado   8a7b6c5d · Pixel 8 · Android 14 (API 34)

Proyecto · testigo.yaml
  ✔ Flows       1 flow en flows/ · cumplen la política local-only
  ✔ Build       com.miempresa.app.qa · 3.4.0 (340) · APK · debuggable
  ! Instalada   com.miempresa.app.qa todavía no está en el dispositivo
                → testigo run la instala; luego vuelve a correr doctor para revisar run-as y Chucker.
  – run-as      sin revisar: la app no está instalada
  – Chucker     sin revisar: hace falta run-as

Listo para testigo run, con 1 aviso.
```

- **✔** está bien, **!** es un aviso que no impide correr, **✖** es un problema que hay que arreglar, y **–** quedó sin revisar porque falló algo de lo que depende.
- Debajo de cada aviso o problema, la línea **→** dice qué hacer.
- Termina con código 0 si no hay problemas y con 2 si hay alguno.

## 9. Ejecutar

### Ver qué hay

```bash
testigo list                 # los flows, su nombre y sus tags
testigo run --dry-run        # el plan completo, sin tocar el dispositivo
```

### Modo interactivo

Desde la carpeta de la suite, sin flags:

```bash
testigo run
```

Pregunta tres cosas:

1. **Qué capturar:** video, red y logs, ya marcados según el perfil por defecto.
2. **Qué flows:** todos marcados; desmarca los que no quieras.
3. **Qué app:** la de `app.artifact` primero, y luego los otros builds de `build/outputs`. Si `testigo.yaml` todavía no tiene una, te pide la ruta.

Si elegiste otra app, o una captura que es la de otro perfil, al final pregunta si la guarda en `testigo.yaml` para la próxima vez. Cambia solo esas líneas (`app.artifact` y `defaults.profile`) y conserva los comentarios y el resto del archivo. Con flags, `--save` hace lo mismo con lo que pasaste: `testigo run --app ../MiApp/app-qa.apk --profile evidencia --save`.

Las teclas: **Espacio** marca o desmarca, **↑ ↓** mueven, **Enter** confirma, **/** busca en listas largas y **Ctrl+C** cancela sin ejecutar nada.

Antes de ejecutar muestra el plan y el **comando equivalente**: el mismo comando con flags, para repetir esa corrida sin preguntas o copiarla a un script.

### Con flags

```bash
testigo run --profile evidencia                 # video, red y logs de todos los flows
testigo run --tags smoke                        # solo los flows con el tag smoke
testigo run --flows flows/login.yaml --record   # un flow, con video
testigo run --app ../MiApp/app/build/outputs/bundle/qaDebug/app-qa-debug.aab
```

Los perfiles son atajos de qué capturar:

| Perfil | Video | Red | Logs | Para qué |
| --- | --- | --- | --- | --- |
| `smoke` | no | no | no | Revisar rápido antes de un commit. |
| `dev` (por defecto) | no | sí | sí | Depurar un flow mientras lo escribes. |
| `evidencia` | sí | sí | sí | La evidencia completa para el PR o el ticket. |

Un flag explícito gana sobre el perfil: `--profile evidencia --no-record` es evidencia sin video.

### ¿Desde qué carpeta?

`testigo` busca el `testigo.yaml` desde la carpeta actual hacia arriba, como git busca `.git`. Puedes ejecutarlo desde la suite o desde cualquier subcarpeta suya. Desde cualquier otro lado, indícale cuál usar:

```bash
testigo run --config ~/proyectos/MiApp-e2e/testigo.yaml
```

## 10. Leer y compartir la evidencia

Cada corrida deja una carpeta con la fecha, el ticket (sacado del nombre del branch de la app) y el commit:

```
evidence/20261016-143012_PROJ-123_a1b2c3d/
├── summary.md            # para pegar en el PR o el ticket
├── metadata.json         # app, dispositivo, versiones, opciones y resultados
└── login/                # una carpeta por flow
    ├── report.html       # reporte de Maestro, paso a paso, con capturas
    ├── video.mp4         # la pantalla, también si el flow falló
    ├── network.txt       # cada request y response, legible
    ├── logcat.txt        # los logs de la app
    └── crash.txt         # solo si la app se cerró
```

- **`summary.md`** dice qué pasó, qué falló y por qué, y qué llamadas al backend dieron error o fueron lentas.
- **`network.txt`** trae cada llamada con método, status, tiempo, headers y bodies, con el JSON formateado. Con `--network-format har` sale un HAR en su lugar, para Chrome DevTools, Charles o Proxyman, y con `ambos`, los dos.
- **Datos exactos por defecto:** la evidencia trae los tokens y bodies tal cual, para poder replicar un request en el backend.
- **Para compartirla fuera del equipo,** corre con `--redact`: enmascara tokens, claves y datos personales, y si algo se escapa, la corrida termina con código 4 y la evidencia queda marcada como no apta.
- **El video y las capturas no se pueden enmascarar:** usa siempre usuarios ficticios de QA.

## 11. Uso diario y CI

```bash
testigo run                      # el día a día: pregunta y ejecuta
testigo run --profile smoke      # antes de un commit
testigo run --profile evidencia  # para el PR
testigo run --redact             # evidencia para compartir
testigo run --flows flows/login --repeat 3   # antes de sumar un flow nuevo: ¿pasa siempre?
```

Un flow que pasa una vez no prueba que sea confiable: con `--repeat 3` corre tres rondas y el resultado dice de cada flow si es estable, intermitente o falla siempre, con el motivo de cada corrida que falló.

En un script o en CI, `--ci` no pregunta nada y además genera `junit.xml`:

```bash
testigo doctor && testigo run --ci --profile smoke
```

| Código | Significado |
| --- | --- |
| 0 | Todos los flows pasaron. |
| 1 | Al menos un flow falló, también si con `--repeat` falló una sola vez. |
| 2 | Entorno o configuración: sin dispositivo, falta una herramienta, un `testigo.yaml` o un flag con errores. |
| 3 | La política local-only bloqueó la corrida antes de ejecutar Maestro. |
| 4 | Con `--redact`, quedaron datos sin enmascarar. |
| 130 | Se canceló (Ctrl+C). |

## 12. Preguntas frecuentes

**¿Cómo instalo el CLI?** Con la línea de `install.sh` y luego `testigo setup` ([secciones 2 y 3](#2-instalar-el-cli)). No necesitas tener Java antes.

**¿Cómo actualizo el CLI?** Con la misma línea: la terminal pasa sola a la versión nueva y la anterior queda para volver ([sección 2](#actualizar-el-cli)). Lo demás se conserva.

**¿Cómo lo desinstalo?** Con la misma línea, cambiando el `bash` del final por `bash -s -- --uninstall` ([sección 2](#desinstalar)). No toca Java, Maestro, el Android SDK ni tus suites.

**¿Cuál es el primer comando?** `testigo setup`, para preparar la máquina. Luego `testigo init` en la carpeta de tu suite, y `testigo doctor`.

**Si no tengo Maestro, ¿el CLI lo instala?** Sí, con `testigo setup` y tu confirmación: descarga la versión de tu equipo del release oficial ([sección 3](#3-preparar-la-máquina-con-testigo-setup)). Ningún otro comando instala ni descarga nada.

**¿Se necesita root o sudo?** No. Todo queda en tu carpeta de usuario y las variables van en tu perfil de la terminal. Si tu empresa no deja editar ese perfil, hay alternativas sin root ([sección 3](#si-tu-empresa-restringe-la-máquina)).

**Mi app está en otra carpeta: ¿tengo que generar un archivo?** Sí, el `testigo.yaml` de la suite, pero lo crea `testigo init` ([sección 6](#6-crear-la-suite-con-testigo-init)). En él, `app.artifact` apunta al APK o AAB que genera Gradle en la carpeta de tu app.

**¿Tengo que escribir los tests de Maestro?** Sí, en YAML; un flow por caso de usuario ([sección 7](#7-escribir-los-flows-de-maestro)).

**¿Cómo le indico dónde están los flows y el APK o AAB?** En `testigo.yaml`, con `flows` y `app.artifact`, relativos a la carpeta del `testigo.yaml`. Para una sola corrida, con `--flows` y `--app`.

**¿Se puede ejecutar desde cualquier carpeta?** Sí, una vez que `testigo setup` lo dejó en el PATH (o lo agregaste tú). Necesita encontrar un `testigo.yaml`: lo busca desde la carpeta actual hacia arriba, o se lo indicas con `--config`.

**¿Puedo probar varias apps?** Sí: una suite, con su `testigo.yaml`, por app. El mismo CLI sirve para todas.

**¿Funciona sin Chucker?** Sí, con `network.capture: none`: tienes video, logs y el reporte de Maestro, pero no los requests.

**¿Se pierde algo si un flow falla?** No. La evidencia se guarda igual, y los demás flows siguen corriendo.

**¿`testigo run` cambia mi `testigo.yaml`?** Solo si se lo pides: en el modo interactivo pregunta antes de guardar la app o el perfil, y con flags solo con `--save`. Cambia esas líneas y nada más.

**¿Y si corto con Ctrl+C a la mitad?** El runner borra lo temporal (el flow auxiliar que usa para el video, los videos que quedaban en el dispositivo y los datos crudos) y termina con código 130.

## 13. Problemas comunes

| Mensaje | Qué hacer |
| --- | --- |
| `testigo necesita Java 17 o más` | Ejecuta `testigo setup` en una terminal: encuentra o descarga uno. O apunta `JAVA_HOME` a un JDK 17 o más nuevo. |
| `tu terminal todavía usa otro testigo` (instalador), o `En el PATH` en `testigo setup` | Otra instalación va antes en el PATH, por ejemplo un zip descomprimido a mano. Ejecuta `testigo setup` de la nueva con la ruta que indica el instalador: pone la suya primero. Después borra la otra. |
| `Aviso: JAVA_HOME apunta a …; testigo usa el Java de …` | Tu `JAVA_HOME` es de un Java viejo o no existe, y `testigo` usó otro. `testigo setup` deja el bueno en la terminal. |
| `No hay dispositivos conectados` | Conecta el teléfono con depuración USB o inicia el emulador; revisa con `adb devices`. |
| `no autorizó la depuración` | Acepta el aviso "¿Permitir depuración USB?" en el teléfono. |
| `Hay 2 dispositivos conectados` | Elige uno con `--device <serial>` (el serial sale en `adb devices`). |
| `No se encontró maestro` | Instálalo con `testigo setup` ([sección 3](#3-preparar-la-máquina-con-testigo-setup)). |
| `No se encontró adb` o `aapt2` | Instálalos con `testigo setup`, o en el SDK Manager de Android Studio. |
| `sdkmanager terminó con código …` | No se aceptó la licencia del Android SDK, o falló la descarga. Vuelve a ejecutar `testigo setup` y acepta la licencia con `y`. |
| `No pude descargar …` en `testigo setup` | Revisa la conexión o el proxy (`curl` usa `HTTPS_PROXY`). Si tu red bloquea las descargas, instala esa herramienta por el canal de tu empresa y vuelve a ejecutar `testigo setup`. |
| `No pude modificar ~/.zshrc` | Carga el entorno a mano con `. ~/.testigo/env.sh` ([sección 3](#si-tu-empresa-restringe-la-máquina)). |
| `La app no existe` | Genera el build con Gradle o corrige `app.artifact`. |
| `run-as … not debuggable` | Usa un build *debuggable*, de cualquier build type. En un emulador sin Play Store también sirve `adb root`. |
| `la app no tiene base de Chucker` | Revisa que el build traiga Chucker y que la app haya hecho alguna llamada. |
| `tiene otra firma` | La app instalada se firmó con otra llave: confirma la desinstalación (borra sus datos), o define `app.signing` con la llave del equipo. |
| `No hay con qué firmar los APKs … AAB` | Crea la debug key con `testigo setup`, o define `app.signing`. |
| `la política local-only bloqueó la corrida` | `testigo run --dry-run` dice qué flow, en qué línea y qué regla. |
| `Maestro no aceptaría un archivo de flows` o `Config Section Required` | A ese archivo (casi siempre un subflow) le falta la configuración. Agrega al inicio `appId: <el de tus flows>` y una línea con `---`; `testigo run --dry-run` dice cuál. |
| `INSTALL_FAILED_USER_RESTRICTED`, o en Xiaomi Maestro no instala su driver | El teléfono pidió confirmar la instalación y nadie tocó *Instalar*. Activa *Instalar vía USB* en las opciones de desarrollador; `testigo doctor` dice si está activado. |
| «Android App Compatibility» tapa la app y los flows fallan a veces | El dispositivo usa páginas de 16 KB y el build trae libs nativas sin alinear; `testigo doctor` dice cuáles. Usa un dispositivo o emulador de 4 KB, o alinea el build (AGP 8.5.1+, NDK r28+). |

Si el problema no está aquí, `testigo doctor` casi siempre dice qué falta.
