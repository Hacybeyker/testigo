# testigo · guía de uso

Referencia completa del CLI: cada comando y opción con su valor por defecto, el `testigo.yaml`, la evidencia, la sanitización y la política local-only. Para empezar desde cero, la [guía paso a paso](GUIA.md).

## Comandos

| Comando | Qué hace |
| --- | --- |
| [`testigo setup`](#testigo-setup) | Revisa la máquina e instala lo que falte (Java, Maestro, Android SDK, debug key, variables de la terminal), con tu confirmación y sin root. |
| [`testigo init`](#testigo-init) | Crea la suite en la carpeta actual: `testigo.yaml`, `flows/` con un flow de demo, `.env.testigo` y `.gitignore`. |
| [`testigo doctor`](#testigo-doctor) | Revisa que todo esté listo para `testigo run` y dice cómo arreglar lo que falta. No instala ni cambia nada. |
| `testigo run` | Ejecuta flows de Maestro y genera la evidencia de cada uno. |
| [`testigo list`](#testigo-list) | Lista los flows de la suite con su nombre y sus tags. |
| `testigo --version` | Versión del runner. |

Cada comando tiene su ayuda con todas las opciones y sus valores por defecto: `testigo setup --help`, `testigo init --help`, `testigo run --help`, `testigo doctor --help`, `testigo list --help`.

## Cómo se usa

`testigo run` tiene dos modos sobre las mismas opciones:

- **Interactivo**, para el día a día: sin `--profile`, `--flows`, `--tags` ni `--app`, y en una terminal, pregunta lo que falta con listas de selección.
- **Con flags**, para CI, scripts o repetir una corrida exacta: con cualquiera de esos flags, con `--ci` o sin terminal (un pipe), no pregunta nada.

Los dos terminan igual: muestran el plan con el **comando equivalente**, el que repite esa misma corrida sin preguntas, y ejecutan.

**De dónde sale cada valor.** Flag explícito > perfil > `testigo.yaml` > valor por defecto. El plan muestra al lado de cada valor de dónde salió: `[flag]`, `[perfil dev]`, `[testigo.yaml]` o `[por defecto]`. Las rutas de los flags son relativas a la carpeta actual; las de `testigo.yaml`, a la carpeta del `testigo.yaml`.

### Modo interactivo

Pregunta tres cosas, en este orden:

1. **Qué capturar en cada flow:** video, red y logs. Vienen marcados según el perfil de `defaults.profile` (si no hay, `dev`: red y logs). Si `network.capture` es `none`, la red no aparece.
2. **Flows a ejecutar:** los `.yaml` de la carpeta `flows` de `testigo.yaml`, todos marcados y agrupados por tag. Si los dejas todos, se usa la carpeta; si eliges algunos, van como `--flows`.
3. **App (APK o AAB):** primero la de `app.artifact` de `testigo.yaml`; después, los demás APK y AAB que encuentra solos en el mismo `build/outputs` de Gradle (otras variantes o un build más nuevo), con antigüedad y tamaño. No se cargan a mano. Sin `app.artifact`, pide la ruta.

| Tecla | Qué hace |
| --- | --- |
| ↑ ↓ | Moverse |
| Espacio | Marcar o desmarcar (listas de varias opciones) |
| Enter | Elegir o confirmar |
| `/` | Buscar, en listas de más de 7 opciones; Esc limpia la búsqueda |
| Ctrl+C | Cancelar: termina con código 130 sin ejecutar nada |

Las teclas también aparecen bajo cada pregunta. Los colores son negrita y los colores básicos de tu terminal, así que se leen con fondo claro u oscuro.

Lo que tiene una sola opción no se pregunta. Si la terminal no permite listas de selección, avisa y sigue con lo de `testigo.yaml`.

**Guardar lo elegido.** Si elegiste una app distinta de `app.artifact`, o una captura que es exactamente la de otro perfil, al final pregunta si la guarda en `testigo.yaml`. Escribe `app.artifact` (relativa a `testigo.yaml`) y `defaults.profile`, y nada más: conserva los comentarios, el orden y el resto del archivo. Antes de escribir, vuelve a leer el resultado con el mismo parser de `testigo run`; si no queda como se pidió, no cambia nada y dice qué editar a mano. Los flows elegidos no se guardan: `flows` es una carpeta, no una lista. Con `--save`, guarda sin preguntar.

## Opciones de `testigo run`

**Qué ejecutar**

| Opción | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--config` | ruta | el `testigo.yaml` más cercano, desde la carpeta actual hacia arriba | Usa otro `testigo.yaml`, por ejemplo una variante de la suite. |
| `--profile` | `smoke`, `dev`, `evidencia` | `defaults.profile` de `testigo.yaml`; si no, `dev` | Activa de una vez video, red y logs según el perfil (ver [Perfiles](#perfiles)). |
| `--app` | ruta a `.apk` o `.aab` | `app.artifact` de `testigo.yaml` | App a probar. Se instala solo si cambió. Un AAB se convierte localmente con `bundletool`. |
| `--flows` | archivo o carpeta, repetible | `flows` de `testigo.yaml`; si no, `flows/` | Flows a ejecutar. Una carpeta se recorre entera, salvo `subflows/`, `config.yaml` y lo oculto. |
| `--tags` | lista separada por comas | todos los flows | Solo los flows con alguno de estos tags (los `tags` del header del flow). |
| `--exclude-tags` | lista separada por comas | ninguno | Quita los flows con alguno de estos tags. |
| `--device` | serial de adb | el único dispositivo conectado | Dispositivo o emulador. Con más de uno conectado es obligatorio. |

**Qué capturar**

| Opción | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--record` / `--no-record` | — | según el perfil | Graba un video por flow con `screenrecord`, desde que empieza el flow hasta que termina, también si falla. |
| `--video-bitrate` | 4 a 8 (Mbps) | `video.bitrate_mbps`; si no, 4 | Calidad del video. A 4 se lee bien; 8 es más nítido y pesa el doble. |
| `--network` | `chucker`, `none` | según el perfil y `network.capture` | Captura las llamadas al backend leyendo la base de Chucker de la app. `none` si la app no trae Chucker. |
| `--network-format` | `txt`, `har`, `ambos` | `network.format`; si no, `txt` | `txt`: `network.txt`, legible con cualquier editor. `har`: `network.har` para Chrome DevTools, Charles o Proxyman. |
| `--logs` / `--no-logs` | — | según el perfil | Guarda el logcat de la app y detecta crashes. Un crash hace fallar el flow. |

**Datos de la evidencia**

| Opción | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--env` | `KEY=VALOR`, repetible | — | Variable para los flows (`${KEY}` en el YAML). Gana sobre el mismo nombre en `.env.testigo`. No aparece en el comando equivalente. |
| `--env-file` | ruta | `.env.testigo` junto a `testigo.yaml`, si existe | Archivo de variables `KEY=VALOR`, por ejemplo los usuarios de prueba. |
| `--out` | carpeta | `evidence.dir`; si no, `evidence/` junto a `testigo.yaml` | Dónde se escribe la evidencia. |
| `--redact` / `--no-redact` | — | `redact.enabled`; si no, apagado | Enmascara tokens, claves y datos personales, y corre el leak scan (ver [Sanitización](#sanitización-y-leak-scan-opcional)). Apagado, la evidencia trae los datos exactos para replicar los requests en el backend. |
| `--keep-raw` | — | `evidence.keep_raw`; si no, apagado | Deja en `raw/` de cada flow la base de Chucker, el logcat y la salida de Maestro sin procesar. Solo para depurar el runner. |

**Ejecución**

| Opción | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--repeat` | 1 a 20 | 1 | Corre la lista de flows esas veces, en rondas (todos, y otra vez), y dice de cada flow si es estable, intermitente o falla siempre. Sirve para saber si un flow nuevo es confiable antes de sumarlo a la suite (ver [Repeticiones](#repeticiones)). |
| `--dry-run` | — | apagado | Muestra el plan y revisa la política local-only, sin tocar el dispositivo ni ejecutar nada. |
| `--ci` | — | apagado | No pregunta nada (ni modo interactivo ni confirmaciones) y genera `junit.xml`. |
| `--save` | — | apagado | Guarda en `testigo.yaml` lo que pasaste con `--app`, `--profile`, `--flows` (uno solo), `--video-bitrate`, `--network-format`, `--redact` / `--no-redact` u `--out`, con las mismas reglas que el modo interactivo. Con `--dry-run` no guarda nada. |

`testigo --version` muestra la versión y `testigo run --help`, esta lista en la terminal.

### Perfiles

Un perfil es un atajo para `--record`, `--network` y `--logs`. Sirve en scripts y CI, para no repetir los tres flags: por ejemplo, `smoke` en un hook antes del commit y `evidencia` para el PR. En el modo interactivo no se pregunta: se elige directamente qué capturar. Un flag explícito siempre gana sobre el perfil.

| Perfil | Video | Red | Logs | Para qué |
| --- | --- | --- | --- | --- |
| `smoke` | no | no | no | Verificación rápida antes de un commit. |
| `dev` (por defecto) | no | sí | sí | Depurar un flow mientras se desarrolla. |
| `evidencia` | sí | sí | sí | Evidencia completa para el PR o el ticket. |

### Códigos de salida

| Código | Significado |
| --- | --- |
| 0 | Todos los flows pasaron. |
| 1 | Al menos un flow falló (también por un crash de la app). Con `--repeat`, basta con una corrida que falle. |
| 2 | Error de entorno o configuración: sin dispositivo, artefacto inválido, falta una herramienta, un `testigo.yaml` con errores, un flow que Maestro no acepta o un flag mal escrito. |
| 3 | La política local-only bloqueó la corrida. Maestro no se ejecutó. |
| 4 | Con `--redact`, el leak scan encontró datos sin enmascarar: la evidencia no es apta para compartir. |
| 130 | Se canceló una pregunta del modo interactivo. |

Las alertas de red (status ≥ 400, sin respuesta, lentas) aparecen en la consola y en `summary.md`, pero no cambian el código.

## `testigo setup`

Prepara la máquina para `testigo`. Revisa cada cosa, muestra qué falta y, con tu confirmación, hace lo que marques. Nunca usa root ni `sudo`: todo queda en tu carpeta de usuario.

```bash
testigo setup           # revisa, te deja elegir qué hacer y pide confirmación
testigo setup --check   # solo revisa; código 2 si falta algo que testigo necesita
testigo setup --yes     # hace todo lo pendiente sin preguntar (la licencia del SDK la pide igual sdkmanager)
testigo setup --print-env  # muestra las variables, sin cambiar nada: eval "$(testigo setup --print-env)"
```

| Qué | ✔ | Si falta, `testigo setup`… | Dónde queda |
| --- | --- | --- | --- |
| Java | 17 o más | Usa el que encuentra `bin/testigo`. Si no hay ninguno, `bin/testigo setup` ofrece descargar Eclipse Temurin 21 antes de arrancar la JVM. | `~/.testigo/jdk/current` |
| Maestro | instalado; con `testigo.yaml`, en su versión | Descarga `maestro.zip` del release oficial y deja `bin/` y `lib/`, como su instalador. Un Maestro instalado con otra herramienta (Homebrew) no se toca. | `~/.maestro` (o `MAESTRO_DIR`) |
| Android SDK | carpeta con sus herramientas | Si falta `sdkmanager`, descarga las command-line tools de Google. | `ANDROID_HOME`, o `~/Library/Android/sdk` (macOS) y `~/Android/Sdk` (Linux) |
| adb, aapt2 | `platform-tools`, `build-tools` | `sdkmanager --sdk_root=<sdk> platform-tools "build-tools;36.0.0"`, solo lo que falta. Antes muestra la licencia del SDK: la aceptas tú. | dentro del SDK |
| Debug key | existe | La crea con `keytool`, igual que Android Studio. Solo hace falta para un AAB. | `~/.android/debug.keystore` |
| Terminal | las variables ya están | Escribe el entorno y una línea que lo carga en el perfil (ver abajo). | `~/.testigo/env.sh` y el perfil |
| En el PATH | el `testigo` de la terminal es este | Avisa si otro va antes, como un zip descomprimido a mano. La configuración de la terminal pone este primero. | — |
| Claude Code (opcional) | la skill `testigo-flows` al día | Solo si existe `~/.claude` (o `CLAUDE_CONFIG_DIR`): copia la skill de esta instalación de testigo. Instalarla es opcional y no viene marcada; `--yes` no la instala. Si ya hay una copia distinta, la actualiza y deja la anterior en `~/.testigo/backups/`. Un enlace no lo toca. Ver [Flows con IA](FLOWS-CON-IA.md). | `~/.claude/skills/testigo-flows` |

**Java antes de la JVM.** `bin/testigo` busca un Java 17 o más antes de arrancar, en este orden: `JAVA_HOME`, el `java` del PATH, `~/.testigo/jdk/current`, `/usr/libexec/java_home -v 17+` en macOS, el `jbr` de Android Studio (en `/Applications` o `~/Applications`, también las versiones Preview; en Linux, `/opt/android-studio`, `~/android-studio`, `/usr/local/android-studio` o el snap) y `/usr/lib/jvm/*`. Si `JAVA_HOME` apunta a uno más viejo, avisa y usa el que encontró. Sin ninguno, cualquier comando termina con código 2 y pide `testigo setup`; `testigo setup`, en una terminal, ofrece descargar Temurin 21.0.12.1 (SHA-256 fijado en el script, unos 200 MB) y solo con un sí.

**Descargas.** Siempre con `curl` por HTTPS (respeta `HTTPS_PROXY`), y con el SHA-256 verificado antes de usar el archivo:

| Archivo | Desde | SHA-256 |
| --- | --- | --- |
| JDK Temurin 21 | `github.com/adoptium/temurin21-binaries` | Fijado en `bin/testigo`, por sistema y arquitectura. |
| `maestro.zip` | `github.com/mobile-dev-inc/maestro/releases/download/cli-<versión>/` | Fijado para la 2.11.0; otra versión, el `checksums_sha256.txt` del mismo release. Sin él, no instala. |
| Command-line tools | `dl.google.com/android/repository/` (build 15859902) | Fijado, el que publica developer.android.com. |
| Platform-Tools, Build-Tools | `dl.google.com`, con `sdkmanager` | Lo verifica `sdkmanager`. |

Lo que se descarga queda primero en una carpeta temporal junto a su destino; si la descarga falla, el SHA-256 no coincide o se corta con Ctrl+C, se borra.

**Terminal.** Para zsh, bash y sh, `testigo setup` escribe `~/.testigo/env.sh` y agrega al perfil un bloque entre `# >>> testigo >>>` y `# <<< testigo <<<` que lo carga:

| Terminal | Perfil |
| --- | --- |
| zsh | `${ZDOTDIR:-~}/.zshrc` |
| bash en macOS | `~/.bash_profile`, o el `~/.bash_login` o `~/.profile` que ya uses |
| bash en Linux | `~/.bashrc` |
| fish | ninguno: el entorno va en `~/.config/fish/conf.d/testigo.fish`, que fish carga solo |
| otra | `~/.profile` |

`env.sh` define solo lo que falta en tu terminal: `JAVA_HOME` si `bin/testigo` tuvo que buscar Java, `ANDROID_HOME` si no estaba, las cuatro variables `MAESTRO_*` de la [política local-only](#política-local-only), y en el PATH las carpetas de `maestro`, `adb` y `testigo` que falten, sin repetirlas. Para `testigo`, si es la versión activa de `install.sh`, la carpeta es `~/.testigo/cli/current/bin`, que sigue a cada actualización sin otro `testigo setup`; si no, la `bin/` de esa instalación. Al volver a ejecutarse, conserva lo que ya manejaba y actualiza las rutas. La primera vez que cambia el perfil guarda una copia en `<perfil>.testigo-backup`. Para quitarlo todo, borra el bloque del perfil y `~/.testigo/env.sh`.

**Sin poder editar el perfil.** Si el perfil no se puede escribir, `testigo setup` lo dice y deja `~/.testigo/env.sh`: se carga con `. ~/.testigo/env.sh` en cada terminal o desde otro archivo de inicio. `testigo` también funciona sin nada de eso: encuentra Java, Maestro y el SDK en sus carpetas por defecto aunque no estén en el PATH.

En Windows, `testigo setup` solo revisa.

## `testigo init`

Crea la estructura de una suite en la carpeta actual (o en `--dir`) y nunca pisa un archivo que ya existe: se puede volver a ejecutar para completar lo que falte.

```bash
testigo init                                      # pregunta qué build vas a probar
testigo init --app ../MiApp/app/build/outputs/apk/debug/app-debug.apk --network chucker
```

| Opción | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--dir` | carpeta | la actual | Dónde crear la suite. Se crea si no existe. |
| `--app` | ruta a `.apk` o `.aab` | lo pregunta; sin terminal, queda pendiente | El build que va en `app.artifact`, relativo a `testigo.yaml`. |
| `--network` | `chucker`, `none` | se detecta en el build | `network.capture`. Sin build, lo pregunta; sin terminal, `none`. |

| Archivo | Contenido |
| --- | --- |
| `testigo.yaml` | Todas las claves con su valor por defecto y un comentario por línea; `app.artifact`, `network.capture` y `maestro.version` (la instalada o la 2.11.0) según lo que encontró. |
| `flows/demo/abrir_app.yaml` | `launchApp` y `waitForAnimationToEnd`, con el `appId` del build. Solo si `flows/` no tiene flows. |
| `.env.testigo` | Un ejemplo comentado, sin variables. |
| `.gitignore` | `.env.testigo` y `evidence/`, agregados al final si ya había uno. |

**Qué builds ofrece.** Los APK y AAB de cada `build/outputs` de Gradle dentro de la carpeta y de sus carpetas hermanas (hasta 4 niveles), del más nuevo al más viejo, sin los de tests ni los sin firmar. El package sale de `aapt2` o `bundletool`, como en `testigo run`; si no se puede leer (por ejemplo, sin `aapt2` todavía), lo pregunta para el flow de demo. Que la app trae Chucker se sabe por sus clases internas en el dex: la variante `library-no-op` no cuenta.

## `testigo doctor`

Revisa lo que `testigo run` va a necesitar y, para cada problema, dice qué hacer. Solo lee: no instala, no desinstala ni cambia nada en la máquina ni en el dispositivo. Lo único que toca es adb (`devices`, `getprop`, `getconf PAGE_SIZE`, `dumpsys package`, `run-as`) y una copia temporal de la base de Chucker que se borra al terminar.

| Opción | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--config` | ruta | el `testigo.yaml` más cercano, desde la carpeta actual hacia arriba | Revisa el proyecto de otro `testigo.yaml`. Sin `testigo.yaml`, solo revisa las herramientas y el dispositivo. |
| `--device` | serial de adb | el único dispositivo conectado | Dispositivo a revisar. Con más de uno conectado es obligatorio. |

Qué revisa, y cuándo es un problema (✖) o un aviso (!):

| Sección | Revisión | Problema si… | Aviso si… |
| --- | --- | --- | --- |
| Herramientas | testigo | — | —: solo dice la versión y cómo se instaló (con `install.sh`, a mano o desde el código) |
| | Java | es menor a 17, `JAVA_HOME` no tiene `bin/java` o no hay Java | no se pudo leer la versión |
| | Maestro | no está instalado | no se pudo leer la versión |
| | Fijada | — | `testigo.yaml` no fija `maestro.version`, o la instalada es otra |
| | adb | no está (ni en `ANDROID_HOME` ni en el PATH) | — |
| | aapt2 | no hay Android SDK Build-Tools | — |
| | bundletool | falta en la instalación del runner | — |
| Dispositivo | Conectado | no hay, no autorizó la depuración, está offline o hay varios sin `--device` | — |
| | Xiaomi (solo MIUI o HyperOS) | — | «Instalar vía USB» está apagado o no se pudo leer: el teléfono pide confirmar cada instalación, también la del driver de Maestro en cada corrida, y sin confirmar falla con `INSTALL_FAILED_USER_RESTRICTED` |
| Proyecto | Flows | no existe la carpeta de `flows`, un flow viola la política local-only o un archivo no tiene la forma que Maestro exige | no hay flows o un header no se puede leer |
| | Build | `app.artifact` no existe o no se puede leer, o el package no coincide con `app.package` | `testigo.yaml` no define `app.artifact`, o el build no es debuggable y se usa la red |
| | Firma (solo AAB) | no hay `app.signing` ni `~/.android/debug.keystore`, o no existe el keystore de `app.signing` | — |
| | 16 KB (solo si el dispositivo usa páginas de 16 KB) | — | alguna lib nativa (`.so`) de la ABI del dispositivo no está alineada a 16 KB: Android muestra «Android App Compatibility» sobre la app y `clearState` lo vuelve a mostrar en cada flow. Revisa los segmentos `LOAD` del ELF y, en un APK con la lib sin comprimir, dónde empieza dentro del zip |
| | Instalada | — | la app todavía no está en el dispositivo (`testigo run` la instala) |
| | run-as | falla, adbd no corre como root y la red usa Chucker | falla con `network.capture: none` |
| | Chucker | no se puede copiar, o la tabla `transactions` no tiene el esquema de Chucker 4.x | todavía no hay base: la app no hizo ninguna llamada |
| | .gitignore | — | la suite está en git y `.env.testigo` no está ignorado |

Lo que depende de algo que falló queda sin revisar (`–`): sin dispositivo no se revisa la app instalada, y sin `run-as` no se revisa Chucker.

```
testigo doctor · revisa la máquina, el dispositivo y la app sin instalar ni cambiar nada

Herramientas
  ✔ testigo     0.3.0 · instalado con install.sh · ~/.testigo/cli/0.3.0
  ✔ Java        21.0.8 · JAVA_HOME · /Applications/Android Studio.app/Contents/jbr/Contents/Home
  ✔ Maestro     2.11.0 · ~/.maestro/bin/maestro
  ! Fijada      testigo.yaml fija 2.10.0, pero tienes 2.11.0
                → Instala esa versión con testigo setup desde esta carpeta o, si el equipo ya cambió de versión, actualiza maestro.version.
  ✔ adb         36.0.0 · ~/Library/Android/sdk/platform-tools/adb
  ✔ aapt2       build-tools 36.1.0 · ~/Library/Android/sdk/build-tools/36.1.0/aapt2
  ✔ bundletool  1.18.3 · ~/.testigo/cli/0.3.0/tools/bundletool-all-1.18.3.jar

Dispositivo
  ✔ Conectado   8a7b6c5d · 2201117TG · Android 13 (API 33)
  ✔ Xiaomi      MIUI V140 · «Instalar vía USB» activado

Proyecto · testigo.yaml
  ✔ Flows       5 flows en flows/ · cumplen la política local-only
  ✔ Build       com.empresa.app.qa · 7.0.0-qa (70) · APK · debuggable
  ✔ Instalada   com.empresa.app.qa 7.0.0-qa (70)
  ✔ run-as      funciona con com.empresa.app.qa
  ✔ Chucker     chucker.db · 42 transacciones · esquema compatible

Listo para testigo run, con 1 aviso.
```

Termina con **0** si no hay problemas (los avisos no cuentan) y con **2** si hay alguno, así que sirve como paso previo en un script o en CI.

**Java antes de arrancar.** Con un Java menor a 17, la JVM ni siquiera puede cargar el runner. Por eso `bin/testigo` busca uno 17 o más antes de arrancar (ver [`testigo setup`](#testigo-setup)): si el de `JAVA_HOME` es más viejo, avisa y usa otro; si no hay ninguno, termina con código 2 y pide `testigo setup`. Doctor muestra `lo encontró testigo` cuando el Java no vino de la terminal. `bin/testigo.bat` solo revisa `JAVA_HOME` y termina con código 2 si es menor a 17. `bin/testigo` también fija la salida en UTF-8: sin un locale configurado (contenedores, SSH, CI), Java escribiría `?` en lugar de acentos y símbolos.

## `testigo list`

Lista los flows de la suite: el archivo, el nombre (el `name` del header o, si no hay, el nombre del archivo; es el que lleva la carpeta del flow en la evidencia) y los tags, más cuántos flows tiene cada tag. Con `--tags` y `--exclude-tags` muestra exactamente los que ejecutaría `testigo run` con esos filtros. No toca el dispositivo.

| Opción | Valores | Por defecto | Qué hace |
| --- | --- | --- | --- |
| `--config` | ruta | el `testigo.yaml` más cercano | Lista la suite de otro `testigo.yaml`. |
| `--flows` | archivo o carpeta, repetible | `flows` de `testigo.yaml`; si no, `flows/` | Qué listar. Una carpeta se recorre entera, salvo `subflows/`, `config.yaml` y lo oculto, igual que en `testigo run`. |
| `--tags` | lista separada por comas | todos los flows | Solo los flows con alguno de estos tags. |
| `--exclude-tags` | lista separada por comas | ninguno | Quita los flows con alguno de estos tags. |

```
Flows · flows/ (flows en testigo.yaml) · 3

  home.yaml                      home               [smoke]
  perfil/actualizar_datos.yaml   Actualizar datos   [perfil, regresion]
  perfil/cerrar_sesion.yaml      cerrar_sesion      [perfil]

Tags  perfil 2 · regresion 1 · smoke 1
```

Con filtros, la primera línea dice cuántos quedan: `Flows · flows/ (flows en testigo.yaml) · 1 de 3 (tags: perfil · sin: regresion)`. Termina con código 2 si una ruta de `--flows` no existe.

## Configuración del proyecto (`testigo.yaml`)

Todo lo que cambia entre apps vive aquí; el runner no conoce ningún negocio. Una clave desconocida (un typo) es un error, no se ignora.

```yaml
version: 1                       # obligatorio

app:
  artifact: ../app/build/outputs/apk/qa/app-qa.apk   # .apk o .aab, relativo a este archivo
  package: auto                  # auto: se lee del artefacto
  signing:                       # opcional, solo para AAB; sin esto se firma con la debug key
    keystore: path/al/keystore.jks
    alias: mi-alias
    password_env: TESTIGO_KS_PASS  # variable de entorno con la clave; nunca la clave en el YAML

flows: flows/                    # carpeta o archivo de flows

maestro:
  version: "2.11.0"              # versión fijada del equipo; el preflight avisa si la instalada difiere

defaults:
  profile: dev                   # perfil cuando no se pasa --profile

video:
  bitrate_mbps: 4                # de 4 a 8

network:
  capture: chucker               # chucker | none (la app no trae Chucker)
  format: txt                    # txt | har | ambos
  chucker_db: auto               # auto: se detecta; o el nombre del archivo, como chucker.db
  hosts:                         # dominios del backend que entran al resumen y a las alertas
    - api.qa.example.com

alerts:
  slow_ms: 3000                  # llamada lenta desde este tiempo (ms)

redact:
  enabled: false                 # true: como --redact en cada corrida
  presets: [base, pii, payments]
  headers: [X-Session-Token]     # se suman a los de los presets
  json_keys: [policyNumber]      # claves propias del dominio

evidence:
  dir: evidence/
  ticket_pattern: '[A-Z]+-\d+'   # el ticket se saca del nombre del branch
  keep_raw: false
```

| Clave | Por defecto | Para qué |
| --- | --- | --- |
| `version` | `1` | Versión del formato de `testigo.yaml`. |
| `app.artifact` | — | APK o AAB a probar. Sin esto, hay que pasar `--app` o el modo interactivo pide la ruta. |
| `app.package` | `auto` | Package esperado; si no coincide con el del artefacto, es un error. |
| `app.signing` | debug key | Keystore para firmar los APKs que `bundletool` genera desde un AAB. |
| `flows` | `flows/` | Carpeta (o archivo) de flows. |
| `maestro.version` | — | Versión de Maestro del equipo. |
| `defaults.profile` | `dev` | Perfil por defecto. |
| `video.bitrate_mbps` | 4 | Calidad del video, de 4 a 8 Mbps. |
| `network.capture` | según el perfil | `none` apaga la red aunque el perfil la pida. |
| `network.format` | `txt` | Formato de la red: `txt`, `har` o `ambos`. |
| `network.chucker_db` | `auto` | Nombre de la base de Chucker en `databases/` de la app. |
| `network.hosts` | todos | Dominios del backend que cuentan para el resumen y las alertas; incluye subdominios. |
| `alerts.slow_ms` | 3000 | Desde cuántos ms una llamada es lenta. |
| `redact.enabled` | `false` | Sanitización y leak scan en cada corrida. |
| `redact.presets` | los tres | Reglas genéricas: `base`, `pii`, `payments`. |
| `redact.headers`, `redact.json_keys` | — | Headers y claves propios del proyecto que se enmascaran. |
| `evidence.dir` | `evidence/` | Carpeta de la evidencia, relativa a `testigo.yaml`. Va en `.gitignore`. |
| `evidence.ticket_pattern` | `[A-Z]+-\d+` | Cómo encontrar el ticket en el nombre del branch. |
| `evidence.keep_raw` | `false` | Como `--keep-raw` en cada corrida. |

**Variables de los flows.** Los flows usan `${USER}`, `${PASSWORD}`, etc. Los valores salen de `.env.testigo` (formato `KEY=VALOR`, junto a `testigo.yaml`, en `.gitignore`) y de `--env`, que gana. Nunca van escritos en el YAML: el lint lo bloquea. Con `--redact`, sus valores aparecen como `****` en la evidencia.

## Evidencia de una corrida

```
evidence/20261016-143012_PROJ-123_a1b2c3d/   # fecha, ticket del branch y commit
├── summary.md                # para pegar en el PR o el ticket: flows, alertas de red y crashes
├── metadata.json             # git, app, dispositivo, versiones, opciones y cada flow con sus alertas
├── junit.xml                 # solo con --ci
└── abrir_app/                # name del flow o nombre del archivo
    ├── report.html           # reporte de Maestro, también si el flow falla; autocontenido
    ├── video.mp4             # con video; video-1.mp4, video-2.mp4… si dura más de 170 s
    ├── logcat.txt            # con logs: solo los logs de la app
    ├── crash.txt             # solo si la app crasheó
    ├── network.txt           # con red: cada llamada en texto, con el JSON formateado
    ├── network.har           # con --network-format har o ambos
    ├── network-summary.json  # una fila por endpoint y las alertas
    ├── raw/                  # solo con --keep-raw
    └── maestro/              # logs, comandos y screenshots de Maestro
        └── console.txt       # salida de consola de Maestro
```

**Reporte.** Maestro arma `report.html` con Bootstrap desde un CDN, así que abrirlo saldría a internet. El runner le quita esos recursos, agrega los estilos en línea y una Content-Security-Policy que bloquea cualquier carga de red.

**Video.** `adb shell screenrecord` en segmentos de 170 s (Android 13 corta a los 3 minutos). Maestro corre un wrapper temporal junto al flow (`.testigo-video-<flow>.yaml`, se borra al terminar) cuyos hooks `onFlowStart` y `onFlowComplete` avisan al runner por HTTP en `127.0.0.1`, con un token por flow. Así el video empieza con el flow, sin los ~10 s del driver.

**El final del video.** `screenrecord` solo recibe un cuadro cuando la pantalla cambia, y al pararlo cierra el encoder sin vaciarlo: los últimos cuadros que todavía tenía adentro se pierden. Si el flow termina en una pantalla quieta (el resultado de un login, un `assertVisible`), justo esos cuadros eran el final. Por eso, al terminar el flow, el runner graba 1 s más y prende y apaga 3 veces la barra de *Ubicación del puntero* (un ajuste de desarrollador que no toca la app y queda como estaba): esos cambios de pantalla empujan el final al video. Al terminar el video puede verse un instante esa barra arriba. Cada flow con video tarda unos 2 s más.

**Logs y crashes.** Antes de cada flow, `logcat -c`; durante el flow, `logcat -v threadtime --uid=<uid de la app>`, así no entran logs de otras apps (Android 9 o anterior no tiene `--uid` y se guarda todo). Al final, `logcat -b crash -d`: si la app crasheó, el crash va a `crash.txt` y el flow falla aunque Maestro haya pasado.

**Red (Chucker).** Al empezar cada flow se guarda la hora del dispositivo. Al terminar, el runner espera a que la base de Chucker deje de crecer, la copia con `adb exec-out run-as <pkg> cat` y toma solo las llamadas desde el inicio del flow, aunque el flow no use `clearState`. `run-as` necesita un build debuggable. Si no lo es, la base se puede leer igual cuando adbd ya corre como root: en un emulador con imagen Google APIs, ejecuta `adb root` antes de la corrida. El runner nunca lo ejecuta por su cuenta, porque reinicia adbd y cortaría la conexión.

**Resumen.** Las alertas son las llamadas a `network.hosts` con status ≥ 400, sin respuesta o más lentas que `alerts.slow_ms`, más los crashes. Las iguales se agrupan con su cantidad. `summary.md` no lleva bodies ni query strings. El branch y el commit salen del repo de git del artefacto (el de la app) o, si no está en uno, del de la suite; solo se lee git local (`rev-parse` y `status`).

El primer flow instala el driver de Maestro y los siguientes lo reutilizan. Si un flow falla, los demás igual se ejecutan.

### Repeticiones

Con `--repeat N`, la lista de flows corre N veces, ronda tras ronda: todos los flows, y otra vez, como en el día a día y no un mismo flow seguido. Cada corrida deja su evidencia en una carpeta por ronda:

```
evidence/20261016-143012_PROJ-123_a1b2c3d/
├── summary.md        cada flow con sus corridas (✅ ❌ ✅), 2/3 y si es estable, intermitente o falla siempre
├── metadata.json     una entrada por corrida, con attempt y dir; además repeat y flaky
└── login/
    ├── 1/            lo mismo que la carpeta de un flow: report.html, video.mp4, network.txt…
    ├── 2/
    └── 3/
```

La consola resume cada flow en una línea y detalla solo las corridas que fallaron, con el motivo y su `report.html`. Un flow intermitente hace terminar la corrida con código 1, igual que uno que falla siempre: no es confiable. En `junit.xml` (con `--ci`), cada corrida es un testcase (`login #2`).

**`.gitignore`.** La primera vez, el runner escribe en la carpeta de evidencia un `.gitignore` que la ignora entera: la evidencia trae tokens y datos de la app y nunca debe terminar en un commit, aunque falte en el `.gitignore` de la suite.

**Si la corrida se corta** (Ctrl+C), el runner borra lo temporal antes de salir: el wrapper de video junto al flow, los videos que quedaban en el dispositivo, logcat corriendo, los datos crudos en carpetas temporales y el archivo temporal con la clave del keystore. Termina con código 130; la carpeta de la corrida queda con lo que alcanzó a escribir, sin `summary.md`.

## Sanitización y leak scan (opcional)

Por defecto la evidencia queda tal cual: tokens, API keys, contraseñas de los usuarios de prueba y bodies exactos, para poder replicar los requests en el backend. No sale de tu máquina, pero si la adjuntas a un ticket o a un PR, viaja con ella. Para compartirla, corre con `--redact`.

Con `--redact`, Maestro, logcat y la base de Chucker escriben primero fuera de `evidence/` y a la carpeta del flow solo entra texto sanitizado:

| Regla | Qué cubre | Queda |
| --- | --- | --- |
| Variables de los flows | Los valores de `.env.testigo` y `--env` (3 caracteres o más) | `****` |
| Preset `base` | Headers `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-Api-Key`, `X-Auth-Token`; claves `password`, `pin`, `otp`, `token`, `accessToken`, `refreshToken`, `idToken`, `secret`, `apiKey` y parecidas; patrones jwt, bearer y clave privada | `***` |
| Preset `pii` | Claves `email`, `phone`, `phoneNumber`, `document`, `documentNumber`, `dni`, `ssn`, `birthDate`, `dateOfBirth`; patrón email | `***` |
| Preset `payments` | Claves `cardNumber`, `pan`, `cvv`, `cvc`, `securityCode`, `expirationDate`; patrón card_luhn (prefijo y largo de una marca real, y Luhn) | `***` y los últimos 4 dígitos de la tarjeta |
| `redact.headers` y `redact.json_keys` | Lo propio del dominio: número de póliza, ID de cliente | `***` |

Las claves se buscan a cualquier profundidad y sin distinguir mayúsculas ni separadores (`access_token` = `accessToken`): en JSON (un objeto o una lista se enmascara entero), en `clave=valor` (query strings, `toString()` de Kotlin), en `clave: valor` y en JSON escapado dentro de un log. Un JSON queda igual byte a byte salvo los valores enmascarados. `true`, `false` y `null` no se enmascaran.

Después, el **leak scan** vuelve a revisar cada archivo de texto del flow (salvo `raw/`): valores de variables, claves declaradas con su valor a la vista y los patrones activos. Jwt, bearer y clave privada se buscan siempre, aunque el preset `base` esté desactivado. Si encuentra algo, la consola dice archivo, línea y regla (nunca el valor), la evidencia queda **no apta** y la corrida termina con código 4, aunque los flows hayan pasado.

El video y los screenshots no se pueden sanitizar: usa solo usuarios ficticios de QA.

## Preflight: dispositivo y app

- **Dispositivo:** el de `--device`, o el único conectado. Con más de uno, pide `--device` en vez de adivinar.
- **App:** lee package, versión y si es debuggable con `aapt2` (APK) o con el `bundletool` incluido (AAB). Avisa si no es debuggable: sin eso no hay red.
- **Instalación:** solo si cambió. Compara el SHA-256 del archivo con el que instaló el runner y con el `lastUpdateTime` del dispositivo, así que detecta builds con el mismo `versionCode` y apps instaladas a mano. Un AAB pasa por `bundletool build-apks` una sola vez por dispositivo; el set de APKs queda en caché.
- **Firma de un AAB:** los APKs que genera `bundletool` se firman con `app.signing` o, sin eso, con la debug key de Android Studio (`~/.android/debug.keystore`). En una máquina que nunca compiló una app esa clave no existe: el runner lo dice antes de generar nada, y `testigo setup` la crea (o el comando `keytool` que muestra el mensaje).
- **Firma distinta:** desinstalar borra los datos de la app, así que pregunta antes. Con `--ci` no pregunta y termina con código 2.

El runner guarda su estado en `~/.testigo/` (`installs.properties` y `cache/`). Se puede borrar sin problema: la siguiente corrida reinstala.

`bundletool` no es una dependencia Maven: `./gradlew build` descarga una vez el jar oficial de GitHub, verifica su SHA-256 y lo deja en `.gradle/downloads/`. La distribución lo lleva en `tools/`, fuera del classpath, porque el jar trae su propia versión de Kotlin.

## Formato de los flows

Maestro exige la misma forma en cada archivo, también en los subflows que se llaman con `runFlow`: la configuración con `appId` (o `url`), una línea con `---` y debajo los comandos. Sin eso falla con `Config Section Required`, `Config Field Required` o `Commands Section Required` antes de ejecutar nada.

`testigo run` lo revisa antes de tocar el dispositivo o Maestro, en los mismos archivos que la política. Si un archivo no cumple, imprime cuál y qué agregar, con el `appId` del flow que lo llama, y termina con código 2, también con `--dry-run`:

```text
Formato de Maestro: 1 archivo que no acepta
  ✗ flows/subflows/login_flow.yaml  no empieza con la configuración (appId y ---), que Maestro exige en cada archivo, también en los subflows
    Agrega al inicio:
      appId: com.example.myapplication
      ---
```

`testigo doctor` lo marca con ✖ en Flows.

## Política local-only

Antes de tocar el dispositivo o Maestro, `testigo run` revisa cada flow, sus subflows (`runFlow`), sus scripts (`runScript`) y cada expresión `${…}`. Si encuentra algo de esto, la corrida termina con código 3, también con `--dry-run`:

- comandos de IA (`assertWithAI`, `assertNoDefectsWithAI`, `extractTextWithAI`);
- `http` en JavaScript hacia un host que no sea localhost, o con una URL que no se puede verificar;
- credenciales escritas en el YAML: valores fijos en `env` para claves como `PASSWORD` o `TOKEN`, texto fijo en un campo de contraseña, o tokens JWT;
- lo que no se puede revisar: una ruta dinámica, un archivo que no existe o un YAML inválido.

Solo usan internet `install.sh`, para bajar `testigo` de sus releases en GitHub, y [`testigo setup`](#testigo-setup), para descargar herramientas de sus sitios oficiales después de tu confirmación. Ninguno envía nada de tus apps, tus flows ni tu evidencia. `testigo init` y el resto no se conectan a nada.

Cada proceso de Maestro se lanza con `MAESTRO_CLI_NO_ANALYTICS=1`, `MAESTRO_DISABLE_UPDATE_CHECK=true`, `MAESTRO_API_URL=http://127.0.0.1:9` y `MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true`, sin `MAESTRO_CLI_AI_*` ni `MAESTRO_CLOUD_API_KEY`. Solo se ejecutan `maestro test` y `maestro --version`.

## Instalación

**Requisitos.** `bundletool` va incluido en el runner; lo demás lo instala [`testigo setup`](#testigo-setup), o tú a mano:

| Herramienta | Para qué | A mano |
| --- | --- | --- |
| Java 17 o más | El runner, Maestro y bundletool | Cualquier JDK 17+ (Temurin, Corretto o el `jbr` de Android Studio). `bin/testigo` lo encuentra aunque no haya `JAVA_HOME`. |
| Maestro | Ejecutar los flows | `export MAESTRO_VERSION=<la del equipo>; curl -Ls "https://get.maestro.mobile.dev" \| bash`, y `~/.maestro/bin` en el PATH. |
| Android SDK Platform-Tools | `adb`: instalar, logcat, `run-as` | SDK Manager de Android Studio. `ANDROID_HOME` apuntando al SDK si no está en la carpeta por defecto. |
| Android SDK Build-Tools | `aapt2`: leer el APK; generar APKs desde un AAB | SDK Manager de Android Studio. |
| Dispositivo o emulador | Donde corren los flows | Un teléfono con *Depuración USB* o un emulador con imagen Google APIs. |
| La app | Lo que se prueba | Un APK o AAB de cualquier build type, de preferencia debuggable; para la red, con Chucker. Ver [Requisitos de la app](../README.md#requisitos-de-la-app). |

**Con `install.sh`.** En macOS y Linux, una línea instala la última versión publicada:

```bash
curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | bash
curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | TESTIGO_VERSION=0.2.0 bash   # una versión en particular
~/.local/bin/testigo setup                                  # prepara la máquina y deja testigo en el PATH
```

Baja de los releases de GitHub el `.tar.gz` (o el `.zip`, si falta `tar`) y su `.sha256`, por HTTPS, y verifica el SHA-256 antes de instalar. Deja la versión en `~/.testigo/cli/<versión>`, apunta `~/.testigo/cli/current` a ella y crea el enlace `~/.local/bin/testigo`; si ya hay un `testigo` ahí que no es suyo, no lo pisa. No usa sudo ni edita el perfil de la terminal. Variables: `TESTIGO_VERSION`, `TESTIGO_ARCHIVE` (un archivo local con su `.sha256` al lado, sin descargar), `TESTIGO_HOME` (en lugar de `~/.testigo`) y `TESTIGO_BIN_DIR` (en lugar de `~/.local/bin`). `sh install.sh --help` las explica. Termina con código 1 si no puede descargar, si el SHA-256 no coincide o si la versión no existe, y con 2 si falta una herramienta (`curl`, `tar` o `unzip`), si una variable tiene un valor inválido o si el sistema no es macOS ni Linux.

Cada versión lleva `bin/`, `lib/`, `tools/` (bundletool), el README, el `CHANGELOG.md`, `LICENSE` y `NOTICE`, `docs/` con esta guía, la [guía paso a paso](GUIA.md) y la de [flows con IA](FLOWS-CON-IA.md), `skills/` con la skill de Claude Code para escribir flows (`testigo setup` ofrece copiarla), `THIRD_PARTY_NOTICES.md` y `licenses/`. Los releases traen el mismo contenido en `.zip` y en `.tar.gz`, el `.sha256` de cada uno y `SHA256SUMS`.

**A mano.** Se descomprime el archivo donde se quiera y se ejecuta su `bin/testigo setup`:

```bash
shasum -a 256 -c testigo-0.3.0.zip.sha256              # verifica que el zip llegó entero
unzip testigo-0.3.0.zip -d ~/herramientas
~/herramientas/testigo-0.3.0/bin/testigo setup         # prepara la máquina y deja testigo en el PATH
```

En Windows (todavía sin probar) se descomprime igual, se agrega `testigo-0.3.0\bin` al PATH y el comando es `testigo` (`bin\testigo.bat`).

**Actualizar.** Con `install.sh`, la misma línea: deja la versión nueva al lado de la anterior, mueve `cli/current` y borra las más viejas, así que quedan la activa y la anterior. La terminal ya apunta a `cli/current`, y si `testigo` es el de esta instalación y `~/.testigo/env.sh` ya usa `cli/current`, el instalador termina con «Listo: tu terminal ya usa esta versión». Si otro `testigo` va antes en el PATH (un zip a mano, o la carpeta de una versión que puso un setup anterior a la 0.3.0), lo avisa y pide `testigo setup` con la ruta completa. Para volver a la anterior, `TESTIGO_VERSION=<versión>`. Una instalación a mano se actualiza borrando su carpeta y descomprimiendo el archivo nuevo en el mismo lugar; con otra carpeta, su `bin/testigo setup` actualiza el PATH. El estado del runner, el Java descargado y la configuración de la terminal viven aparte, en `~/.testigo/`, y no cambian. Detalle en la [guía](GUIA.md#actualizar-el-cli).

**Desinstalar.** `curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | bash -s -- --uninstall` (o `sh install.sh --uninstall`) quita `~/.testigo/cli` y el enlace, si es suyo. No toca Java, Maestro, el Android SDK, las suites ni `~/.testigo/env.sh`.

[`testigo doctor`](#testigo-doctor) revisa todo lo de la tabla, el dispositivo, `run-as` y la base de Chucker.

## Desarrollo

### Compilar y probar

```bash
./gradlew build          # compila, corre los tests y deja en build/distributions/ el .zip y el .tar.gz, sus .sha256 y SHA256SUMS
./gradlew installDist    # deja el CLI en build/install/testigo/bin/testigo, sin archivos
sh scripts/test-install.sh build/distributions   # prueba install.sh con ese build en una carpeta temporal

# Atajo contra la suite de ejemplo, sin instalar:
./gradlew run --args="run --dry-run --profile evidencia --tags smoke"
```

Antes de compilar, `./scripts/check-env.sh` revisa que la máquina tenga Java, Maestro y el Android SDK.

**CI.** En cada push a `master` y en cada pull request, [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) compila y corre los tests, revisa los scripts con ShellCheck (también que ninguna `$VAR` quede pegada a una letra no ASCII, que el bash 3.2 de macOS lee mal) y corre `scripts/test-install.sh` con el build en Linux x64 y arm64 y en macOS Apple Silicon e Intel. Cada action está fijada por el SHA de su commit; Dependabot propone las actualizaciones una vez por semana.

**Versión.** Está en un solo lugar: `version` de `build.gradle.kts`. La tarea `generateBuildInfo` la escribe en `build/generated/` como una constante que leen `testigo --version`, `metadata.json` y el HAR, y los archivos de la distribución toman su nombre. Entre un release y el siguiente lleva `-dev` (por ejemplo `0.3.0-dev`); para lanzar una versión se le quita el sufijo y su sección `Sin publicar` del `CHANGELOG.md` pasa a llevar el número y la fecha.

**Publicar.** Con esos dos cambios en un commit de `master`, se sube su tag: `git tag -a v<versión> -m "testigo <versión>" && git push origin v<versión>`. [`.github/workflows/release.yml`](../.github/workflows/release.yml) revisa que el tag coincida con `version`, que su commit esté en `master` y que el `CHANGELOG.md` tenga la sección `## <versión> - <fecha>`; compila sin caché, genera la attestation de los archivos y de `install.sh`, y prueba `install.sh` en Linux x64 y arm64 y macOS Apple Silicon e Intel. Recién entonces publica el release con el `.zip`, el `.tar.gz`, sus `.sha256`, `SHA256SUMS`, `install.sh` y, como notas, esa sección del CHANGELOG. Si algo falla, no publica nada. Un tag con sufijo (`v0.3.0-rc.1`) sale como pre-release y no cambia la versión que instala `curl … | bash` por defecto. Al final, el workflow instala la versión publicada con `curl … | bash` en Linux y macOS y verifica su attestation, como lo haría alguien del equipo: `gh attestation verify testigo-<versión>.tar.gz -R Hacybeyker/testigo`.

En IntelliJ IDEA: **File > Open** sobre esta carpeta, un **Gradle JVM** 17 o superior en **Settings > Build Tools > Gradle**, y la configuración incluida **testigo run --dry-run (demo)**. Hasta el primer `./gradlew build`, el IDE marca `GENERATED_VERSION` como desconocida: la genera ese build.

### Estructura

```
src/main/kotlin/testigo/
├── Main.kt
├── cli/        comandos (Clikt), modo interactivo (Mordant), render del plan y comando equivalente
├── config/     testigo.yaml, perfiles, precedencia, plan de la corrida y edición que conserva comentarios
├── maestro/    flows: descubrimiento, headers y un maestro test por flow
├── policy/     política local-only y forma de Maestro: lint de flows y entorno de Maestro
├── preflight/  dispositivo, app e instalación antes de los flows
├── doctor/     testigo doctor: Java, herramientas, dispositivo, run-as y la base de Chucker
├── setup/      testigo setup: revisión de la máquina, descargas verificadas, instaladores y entorno de la terminal
├── scaffold/   testigo init: los archivos de una suite nueva
├── video/      screenrecord por segmentos y hooks del wrapper
├── logs/       logcat de la app y crashes
├── network/    Chucker → texto, HAR 1.2, resumen por endpoint y alertas
├── redact/     presets, sanitización y leak scan
├── report/     reporte de Maestro autocontenido, git, summary.md, metadata.json y junit.xml
├── android/    adb, SDK, elección de dispositivo y acceso a los archivos de la app (run-as o root)
├── process/    ejecución de herramientas externas con timeout y limpieza si la corrida se corta
├── artifact/   APK o AAB: lectura del manifest, bundletool, caché e instalación
└── util/
gradle/start-scripts/  búsqueda de Java ≥ 17 (y descarga con testigo setup) que se agrega a bin/testigo; revisión en bin/testigo.bat
docs/GUIA.md           la guía paso a paso; va en cada versión
docs/USO.md            esta guía; va en cada versión
docs/FLOWS-CON-IA.md   cómo usar la skill de Claude Code; va en cada versión
skills/testigo-flows/  la skill de Claude Code para escribir flows; va en cada versión
CHANGELOG.md           qué trae cada versión; va en cada versión
LICENSE y NOTICE       la licencia del proyecto (Apache 2.0); van en cada versión
THIRD_PARTY_NOTICES.md licencias de las dependencias; va en cada versión con licenses/
scripts/check-env.sh   verifica que la máquina tenga todo lo necesario
scripts/test-install.sh prueba install.sh con un build o un release (lo usa la CI)
install.sh             el instalador de una línea: curl … | bash
.github/               la CI (build, ShellCheck e install.sh en Linux y macOS), los releases y Dependabot
examples/demo-suite/   suite de ejemplo para probar el CLI
```

### Versiones

| Dependencia | Versión |
| --- | --- |
| Kotlin | 2.4.21 |
| Gradle (wrapper) | 9.7.1, la más nueva soportada oficialmente por Kotlin 2.4.21 |
| Clikt | 5.1.0 |
| Mordant | 3.1.0, forzada: la 3.0.2 que trae Clikt falla en Java 21 |
| kaml | 0.104.0 |
| kotlinx.serialization | 1.11.0 |
| JUnit | 6.1.3 |
| bundletool | 1.18.3, jar oficial de GitHub verificado por SHA-256 |
| sqlite-jdbc | 3.53.4.0 |

Están en `gradle/libs.versions.toml`.
