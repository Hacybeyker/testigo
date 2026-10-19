# testigo

**Pruebas E2E de apps Android con evidencia, en tu máquina.** `testigo` ejecuta los flows de [Maestro](https://maestro.dev) sobre el build de cualquier app Android (APK o AAB, de cualquier build type) y, por cada flow, guarda el video de la pantalla, cada request y response al backend, los logs de la app y un resumen listo para pegar en el PR o en el ticket. Nada de tu app sale de tu máquina: el único comando que usa internet es `testigo setup`, para descargar herramientas oficiales, y solo si lo confirmas.

## Qué resuelve

Los unit tests pasan, pero la app falla en el dispositivo: un request mal armado, un response del backend que no se validó, un crash en un módulo compartido. `testigo` prueba la app como la usa una persona, contra el backend de QA, y deja la prueba de lo que pasó.

- **Evidencia completa por flow:** video, requests y responses (desde [Chucker](https://github.com/ChuckerTeam/chucker)), logcat, crashes y el reporte paso a paso de Maestro, también cuando el flow falla.
- **Sin tocar la app:** los tests viven fuera del repo de la app y prueban el binario tal como se entrega, con Views o Compose. Sirve para cualquier app: lo propio de cada una está en su `testigo.yaml` y sus flows, no en el runner.
- **Replicable en el backend:** por defecto guarda los requests exactos, con tokens y bodies. Para compartir la evidencia, `--redact` enmascara tokens, claves y datos personales, y revisa que no quede ninguno.
- **Local-only:** Maestro corre sin analytics ni servicios en la nube, y una corrida se bloquea si un flow usa comandos de IA, `http` hacia afuera o credenciales escritas.
- **Repetible y para el equipo:** un zip, `testigo setup` para preparar la máquina sin root, `testigo init` para crear la suite, la versión de Maestro fijada, `testigo doctor` para revisar todo, un comando equivalente para repetir cada corrida y `junit.xml` para CI.

## Cómo funciona

```
 tu suite (testigo.yaml + flows)    tu app (APK o AAB de Gradle)
              │                                 │
              └────────────► testigo run ◄──────────┘
                               │  instala si cambió, un maestro test por flow,
                               │  graba la pantalla, logcat y la base de Chucker
                               ▼
                 dispositivo o emulador (adb, local)
                               │
                               ▼
      evidence/<fecha>_<ticket>_<commit>/  summary.md · video · network · logs
```

## Requisitos

- **Sistema:** macOS (Apple Silicon o Intel) o Linux. En Windows está sin probar y `testigo setup` solo revisa.
- **Dispositivo:** un teléfono Android con depuración USB o un emulador.
- **La app:** un APK o AAB de cualquier build type o flavor (`debug`, `qa`, `staging`, `release`…), de preferencia *debuggable*. Con [Chucker](https://github.com/ChuckerTeam/chucker), si quieres capturar la red. Detalle en [Requisitos de la app](#requisitos-de-la-app).
- **Herramientas:** Java 17 o más, Maestro y el Android SDK. Si faltan, `testigo setup` las instala. Para compilar el CLI basta Java 17 o más.

### Requisitos de la app

`testigo` no se fija en el nombre del build type ni del flavor, solo en el archivo (`app.artifact` o `--app`). Lo que importa es cómo está configurado ese build:

| Para | Qué necesita el build |
| --- | --- |
| Instalarlo y correr los flows | Un APK firmado, o un AAB: `testigo` lo convierte y lo firma con `app.signing` o con tu debug key. |
| Capturar la red | La librería de Chucker (no `library-no-op`) en el `OkHttpClient` de la app, y que el build sea *debuggable*, para leer su base con `run-as`. Sin *debuggable*, solo en un emulador sin Play Store y con `adb root`. |
| Que el video y las capturas no salgan en negro | Pantallas sin `FLAG_SECURE`: Android las oculta en cualquier grabación. El flow igual corre. |
| Seleccionar por `id` en Compose | `Modifier.semantics { testTagsAsResourceId = true }` en la raíz, para que Maestro vea los `testTag` como ids. |
| Correr en un emulador | Que la app no se cierre al detectar un emulador, root o la depuración. |
| Logs y crashes | Nada especial. |

**Con un build `release`** (o cualquiera que no sea *debuggable*) funciona, pero normalmente se pierde la red: no es *debuggable* y suele llevar Chucker en `no-op`. Si tiene `FLAG_SECURE`, el video sale en negro. Si en el dispositivo ya hay una versión firmada con otra llave, `testigo` pregunta antes de desinstalarla, porque se pierden sus datos. La ofuscación de código (R8, ProGuard) no afecta a Maestro, que trabaja sobre la interfaz; sí afecta la que también renombra los recursos, porque los `id` dejan de coincidir. `testigo run` y `testigo doctor` avisan cuando el build no es *debuggable*.

## Instalar y configurar

En cinco pasos. El detalle de cada uno, con ejemplos, está en la **[guía paso a paso](docs/GUIA.md)**.

**1. Generar el zip y descomprimirlo** donde quieras dejar el CLI. Desde la carpeta de este repositorio:

```bash
./gradlew build                                              # tests y build/distributions/testigo-0.2.0.zip
unzip build/distributions/testigo-0.2.0.zip -d ~/herramientas
```

Si alguien de tu equipo ya te pasó el zip, o lo bajaste con su `.sha256` de [Releases](https://github.com/Hacybeyker/testigo/releases), verifícalo con `shasum -a 256 -c testigo-0.2.0.zip.sha256` y descomprímelo igual. Para actualizar a una versión nueva, descomprime su zip al lado del anterior, ejecuta una vez su `bin/testigo setup` y borra la carpeta vieja; lo demás se conserva ([guía](docs/GUIA.md#actualizar-el-cli)).

**2. Preparar la máquina.** `testigo setup` revisa lo que hace falta y, con tu confirmación, instala lo que elijas. No usa root ni sudo: todo queda en tu carpeta de usuario.

```bash
~/herramientas/testigo-0.2.0/bin/testigo setup
```

| Necesitas | Qué hace `testigo setup` si falta |
| --- | --- |
| Java 17 o más | Usa el que encuentre (el de Android Studio, por ejemplo) o descarga Java 21 (Eclipse Temurin) en `~/.testigo/jdk`. |
| [Maestro](https://docs.maestro.dev), en la versión del equipo | Lo descarga del release oficial y lo deja en `~/.maestro`. |
| Android SDK Platform-Tools y Build-Tools (`adb`, `aapt2`) | Los instala con `sdkmanager`, de Google, en `~/Library/Android/sdk` (macOS) o `~/Android/Sdk` (Linux). La licencia del SDK la aceptas tú. |
| La debug key de Android (para probar un AAB) | La crea en `~/.android/debug.keystore`, como Android Studio. |
| `testigo`, `maestro` y `adb` en cualquier terminal | Escribe `~/.testigo/env.sh` y agrega una línea que lo carga al perfil de tu terminal (`~/.zshrc`, `~/.bashrc` o el que use tu shell). |

Cada descarga sale del sitio oficial y se verifica con su SHA-256 antes de usarla. Si prefieres instalar todo a mano, o tu empresa no deja editar el perfil de la terminal, la [guía](docs/GUIA.md#3-preparar-la-máquina-con-testigo-setup) explica cómo. `bundletool`, para los AAB, ya viene en el zip.

Al terminar, abre una terminal nueva: `testigo`, `maestro` y `adb` ya funcionan desde cualquier carpeta.

**3. Crear la suite** en una carpeta nueva, fuera del repo de la app. `testigo init` crea `testigo.yaml`, un flow de demo, `.env.testigo` para los usuarios de prueba y el `.gitignore`, y te pregunta qué build vas a probar:

```bash
mkdir ~/proyectos/MiApp-e2e && cd ~/proyectos/MiApp-e2e
testigo init
```

**4. Revisar todo** con `testigo doctor`: Java, Maestro, adb, el dispositivo, el build, run-as y Chucker.

**5. Ejecutar.** `testigo run` pregunta qué capturar, qué flows y con qué app, y te ofrece guardar lo elegido en `testigo.yaml`. Después, escribe tus flows de Maestro en `flows/`:

```yaml
# MiApp-e2e/flows/login.yaml
appId: com.miempresa.app.qa
name: login
tags: [login, smoke]
---
- launchApp:
    clearState: true
- tapOn:
    id: "input_usuario"
- inputText: ${USER}              # los usuarios de prueba van en .env.testigo
- tapOn: "Ingresar"
- assertVisible: "Hola"
```

## Comandos

| Comando | Qué hace |
| --- | --- |
| `testigo setup` | Revisa la máquina e instala lo que falte, con tu confirmación y sin root. Si usas Claude Code, ofrece copiar la skill para escribir flows. `--check` solo revisa; `--print-env` muestra las variables para cargarlas a mano. |
| `testigo init` | Crea la suite en la carpeta actual: `testigo.yaml`, `flows/` con un flow de demo, `.env.testigo` y `.gitignore`. No pisa nada que exista. |
| `testigo doctor` | Revisa que todo esté listo para `testigo run` y dice cómo arreglar lo que falta, también lo que vuelve intermitentes los flows: un Xiaomi sin «Instalar vía USB» o un dispositivo de 16 KB con un build sin alinear. No instala ni cambia nada. |
| `testigo run` | Ejecuta los flows y genera la evidencia. Sin flags, en una terminal, pregunta qué ejecutar y ofrece guardarlo en `testigo.yaml`. |
| `testigo list` | Lista los flows de la suite, con su nombre y sus tags. |
| `testigo --version` | La versión del runner. |
| `testigo <comando> --help` | Las opciones del comando, con sus valores por defecto. |

`testigo` busca el `testigo.yaml` desde la carpeta actual hacia arriba, como git busca `.git`. Desde otra carpeta, se indica con `--config`.

## Parámetros de `testigo run`

Cada valor sale, en este orden, del flag, del perfil, de `testigo.yaml` o del valor por defecto. Las rutas de los flags son relativas a la carpeta actual.

| Parámetro | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--config` | ruta | el `testigo.yaml` más cercano | Usa otro `testigo.yaml`. |
| `--profile` | `smoke`, `dev`, `evidencia` | `defaults.profile`; si no, `dev` | Atajo de qué capturar (ver [Perfiles](#perfiles)). |
| `--app` | ruta a `.apk` o `.aab` | `app.artifact` | El build a probar. Se instala solo si cambió; un AAB se convierte en tu máquina. |
| `--flows` | archivo o carpeta, repetible | `flows`; si no, `flows/` | Qué flows ejecutar. Una carpeta se recorre entera, salvo `subflows/`. |
| `--tags` | lista separada por comas | todos | Solo los flows con alguno de esos tags. |
| `--exclude-tags` | lista separada por comas | ninguno | Quita los flows con alguno de esos tags. |
| `--record` / `--no-record` | — | según el perfil | Graba un video por flow, también si falla. |
| `--video-bitrate` | 4 a 8 (Mbps) | `video.bitrate_mbps`; si no, 4 | Calidad del video. |
| `--network` | `chucker`, `none` | según el perfil y `network.capture` | Captura los requests y responses desde Chucker. |
| `--network-format` | `txt`, `har`, `ambos` | `network.format`; si no, `txt` | `network.txt` legible, `network.har` para Chrome DevTools, o los dos. |
| `--logs` / `--no-logs` | — | según el perfil | Guarda el logcat de la app y detecta crashes. |
| `--device` | serial de adb | el único conectado | El dispositivo. Obligatorio si hay más de uno. |
| `--env` | `CLAVE=VALOR`, repetible | — | Una variable para los flows (`${CLAVE}`). Gana sobre `.env.testigo`. |
| `--env-file` | ruta | `.env.testigo`, si existe | El archivo de variables, como los usuarios de prueba. |
| `--out` | carpeta | `evidence.dir`; si no, `evidence/` | Dónde se guarda la evidencia. |
| `--redact` / `--no-redact` | — | `redact.enabled`; si no, apagado | Enmascara tokens, claves y datos personales, y revisa que no quede ninguno. |
| `--keep-raw` | — | `evidence.keep_raw`; si no, apagado | Conserva los datos sin procesar en `raw/`. Solo para depurar. |
| `--repeat` | 1 a 20 | 1 | Corre los flows esas veces, en rondas, y dice cuáles son estables y cuáles fallan solo a veces. |
| `--dry-run` | — | apagado | Muestra el plan sin tocar el dispositivo ni ejecutar nada. |
| `--ci` | — | apagado | No pregunta nada y genera `junit.xml`. |
| `--save` | — | apagado | Guarda en `testigo.yaml` lo que pasaste con `--app`, `--profile`, `--flows` (uno), `--video-bitrate`, `--network-format`, `--redact` u `--out`, sin tocar sus comentarios. |

`testigo doctor` acepta `--config` y `--device`, y `testigo list` acepta `--config`, `--flows`, `--tags` y `--exclude-tags`, con los mismos valores. Las claves de `testigo.yaml`, una por una, están en la [guía de uso](docs/USO.md#configuración-del-proyecto-testigoyaml).

### Perfiles

| Perfil | Video | Red | Logs | Para qué |
| --- | --- | --- | --- | --- |
| `smoke` | no | no | no | Revisar rápido antes de un commit. |
| `dev` (por defecto) | no | sí | sí | Depurar un flow mientras se escribe. |
| `evidencia` | sí | sí | sí | La evidencia completa para el PR o el ticket. |

Un flag explícito gana sobre el perfil: `--profile evidencia --no-record` es evidencia sin video.

### Códigos de salida

| Código | Significado |
| --- | --- |
| 0 | Todos los flows pasaron. |
| 1 | Al menos un flow falló, también si con `--repeat` falló una sola vez. |
| 2 | Entorno o configuración: sin dispositivo, falta una herramienta, o un `testigo.yaml` o un flag con errores. |
| 3 | La política local-only bloqueó la corrida antes de ejecutar Maestro. |
| 4 | Con `--redact`, quedaron datos sin enmascarar: la evidencia no es apta para compartir. |
| 130 | Se canceló (Ctrl+C). |

## Qué deja cada corrida

```
evidence/20261016-143012_PROJ-123_a1b2c3d/    fecha, ticket del branch y commit
├── summary.md         para pegar en el PR: resultados, fallos y alertas de red
├── metadata.json      app, dispositivo, versiones, opciones y cada flow
├── junit.xml          solo con --ci
└── login/             una carpeta por flow
    ├── report.html    el reporte de Maestro, paso a paso, sin recursos externos
    ├── video.mp4      la pantalla, también si el flow falló
    ├── network.txt    cada request y response, con el JSON formateado
    ├── logcat.txt     los logs de la app
    └── crash.txt      solo si la app se cerró
```

Con `--repeat 3`, cada flow tiene una carpeta por ronda (`login/1/`, `login/2/`, `login/3/`), y `summary.md` dice cuántas veces pasó cada uno.

El runner le crea a `evidence/` su propio `.gitignore`, para que nunca termine en un commit.

## Versión de Maestro

Cada suite fija su versión en `maestro.version` de `testigo.yaml`, y así todo el equipo usa la misma. Para cambiarla, edita ese valor y ejecuta `testigo setup` en la carpeta de la suite: descarga esa versión del release oficial y la verifica con el `checksums_sha256.txt` del mismo release. No hace falta cambiar ni recompilar `testigo`. Hasta que la instales, `testigo doctor` y `testigo run` avisan que la instalada es otra.

De Maestro, `testigo` solo usa `maestro test` y `maestro --version`. Si una versión futura cambiara las opciones de `maestro test` que usa, habría que actualizar `testigo`.

## Escribir flows con IA

El zip trae en `skills/testigo-flows/` una skill de [Claude Code](https://code.claude.com/docs) que escribe los flows por ti: lee el código de la app (Views XML o Compose, con ids o sin ellos), confirma los selectores en el dispositivo y corre cada flow con `testigo` hasta que pasa tres veces seguidas. En una app grande, empieza por el inventario: deja el mapa de la app, lo que le falta para ser fácil de probar y un backlog de casos por prioridad.

```bash
testigo setup                                   # ofrece copiar la skill a ~/.claude/skills
cd ~/proyectos/MiApp-e2e && claude --add-dir ~/proyectos/MiApp
# en Claude Code: /testigo-flows escribe los flows de login
```

`testigo` y Maestro siguen sin salir de tu máquina, pero el código y la pantalla que la skill lee sí van al proveedor del modelo. La [guía de la skill](docs/FLOWS-CON-IA.md) explica cómo limitar lo que ve, los alcances que acepta y cuánto cuesta.

## Limitaciones

- **Solo Android.** Las apps de iOS no están soportadas.
- **OTP, 2FA y captcha.** Maestro usa la app como una persona: no lee por su cuenta un SMS ni un correo, y un captcha existe justamente para frenar la automatización. Además, la política local-only bloquea `http` hacia afuera, así que un flow no puede pedirle el código a un servicio externo. Antes de escribir los flows, coordina con el equipo del backend usuarios de prueba de QA sin esos pasos o con códigos fijos.
- **La red sale de Chucker.** Solo se captura lo que pasa por el `OkHttpClient` que tiene el `ChuckerInterceptor`; lo normal es agregarlo una vez al cliente compartido de la app. Leer su base requiere un build *debuggable* ([Requisitos de la app](#requisitos-de-la-app)).
- **Un dispositivo por corrida.** Con más de uno conectado, se elige con `--device`.
- **Windows** está sin probar.

## Documentación

| Documento | Para qué |
| --- | --- |
| [Guía paso a paso](docs/GUIA.md) | Instalar, preparar la app, escribir el primer flow y ejecutarlo, con preguntas frecuentes y problemas comunes. |
| [Guía de uso](docs/USO.md) | La referencia: cada opción, cada clave de `testigo.yaml`, la evidencia, la sanitización y la política local-only. |
| [Flows con IA](docs/FLOWS-CON-IA.md) | La skill `testigo-flows` de Claude Code: instalarla, pedirle flows, qué ve y cuánto cuesta. |
| [Cambios](CHANGELOG.md) | Qué trae cada versión. |
| [Avisos de terceros](THIRD_PARTY_NOTICES.md) | Las licencias de cada librería y herramienta. |

## Licencias de terceros

`testigo` usa librerías de código abierto. Las que viajan en el zip permiten usarlas y redistribuirlas, también dentro de una empresa, conservando sus avisos de licencia; el zip los lleva en `THIRD_PARTY_NOTICES.md` y `licenses/`. Ninguna obliga a publicar el código de `testigo` ni el de la app que se prueba.

| Librería o herramienta | Licencia | Para qué |
| --- | --- | --- |
| [Clikt](https://github.com/ajalt/clikt) 5.1.0 | Apache 2.0 | Comandos y flags. |
| [Mordant](https://github.com/ajalt/mordant) 3.1.0 | Apache 2.0 | La terminal y las listas del modo interactivo. |
| [Colormath](https://github.com/ajalt/colormath) 3.7.0 | MIT | Colores de la terminal (dependencia de Mordant). |
| [JNA](https://github.com/java-native-access/jna) 5.19.1 | Apache 2.0 o LGPL 2.1+ (se usa bajo Apache 2.0) | Acceso a la terminal (dependencia de Mordant). |
| [kaml](https://github.com/charleskorn/kaml) 0.104.0, con [SnakeYAML Engine KMP](https://github.com/krzema12/snakeyaml-engine-kmp), [Okio](https://github.com/square/okio) y [urlencoder](https://github.com/ethauvin/urlencoder) | Apache 2.0 | Leer `testigo.yaml` y los flows. |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) 1.11.0 | Apache 2.0 | Los modelos de configuración. |
| [Kotlin](https://github.com/JetBrains/kotlin) 2.4.21 y JetBrains Annotations | Apache 2.0 | El lenguaje y su runtime. |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) 3.53.4.0 | Apache 2.0, con una parte BSD de 2 cláusulas | Leer la base de Chucker. |
| [bundletool](https://github.com/google/bundletool) 1.18.3 | Apache 2.0 | Convertir un AAB en APKs. |
| [Maestro](https://github.com/mobile-dev-inc/maestro) | Apache 2.0 | Ejecutar los flows. No viaja en el zip: lo descarga `testigo setup` o lo instalas tú. |
| [Chucker](https://github.com/ChuckerTeam/chucker) | Apache 2.0 | Registrar la red dentro de la app. Va en la app, no en `testigo`. |
| Android SDK (`adb`, `aapt2`, `sdkmanager`) | Android SDK License Agreement | Hablar con el dispositivo. No viaja en el zip: lo instala `sdkmanager` (con `testigo setup`, que te muestra la licencia) o Android Studio. |
| [Eclipse Temurin](https://adoptium.net) 21 | GPLv2 con Classpath Exception | Java, solo si la máquina no tiene uno 17 o más. No viaja en el zip: lo descarga `testigo setup` si lo confirmas. |
| [JUnit](https://github.com/junit-team/junit-framework) 6.1.3 | EPL 2.0 | Solo los tests del proyecto; no va en el zip. |

El detalle, con versiones y textos completos, está en [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Licencia

Copyright 2026 Carlos Osorio. `testigo` se distribuye bajo la [Licencia Apache 2.0](LICENSE): se puede usar, modificar y redistribuir, también con fines comerciales o dentro de una empresa, conservando los avisos de [`LICENSE`](LICENSE) y [`NOTICE`](NOTICE). Las licencias de las dependencias están en [Licencias de terceros](#licencias-de-terceros).

## Desarrollo

Está escrito en Kotlin, con Gradle. Se necesita Java 17 o más. La primera compilación descarga Gradle, las dependencias y `bundletool` (verificado con su SHA-256); después, `testigo` no necesita internet salvo para `testigo setup`.

```bash
./gradlew test           # solo los tests
./gradlew build          # tests y build/distributions/testigo-0.2.0.zip con su .sha256
./gradlew installDist    # el CLI en build/install/testigo/bin/testigo, sin zip
```

La versión de `testigo` está solo en `version` de `build.gradle.kts`: `testigo --version` y el nombre del zip salen de ahí. Cómo está organizado el código y las versiones de cada dependencia: [guía de uso › Desarrollo](docs/USO.md#desarrollo).
