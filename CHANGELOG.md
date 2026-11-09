# Changelog

Los cambios importantes de `testigo`, de la versión más nueva a la más vieja. El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones, [Semantic Versioning](https://semver.org/lang/es/).

## 0.4.0 - 2026-11-09

Para actualizar desde 0.3.0: la misma línea de `install.sh`. Los comandos, `metadata.json` (solo suma campos) y los códigos de salida son los de 0.3.0; la novedad es que un dispositivo que no responde ahora termina la corrida con código 2.

### Agregado

- Antes de cada flow, `testigo` espera a que el dispositivo esté listo (`sys.boot_completed` y la app resuelta por Android), enciende la pantalla si estaba apagada (o con el salvapantallas puesto) y descarta el bloqueo de deslizar. En Android 6 o anterior, que no tiene `cmd package resolve-activity`, basta con que Android vea la app: no espera 15 s en cada flow. Si no está listo a los 3 minutos, o tiene un bloqueo con PIN, patrón o contraseña, la corrida se detiene con código 2, dice cuántas corridas no se ejecutaron y lo deja en `summary.md` y `metadata.json` (`aborted`, `not_run`). El encabezado de `summary.md` dice «interrumpida · X de Y pasaron · Z sin correr», no «todos pasaron».
- Detecta si el dispositivo se reinició, o dejó de responder, antes o durante un flow (con el `boot_id` del kernel). El flow queda anotado (`device_note` en `metadata.json`, sección *Dispositivo* de `summary.md`), un fallo lo dice primero y no se toma como un defecto de la app.
- `--flow-timeout` y `defaults.flow_timeout_s`: tiempo máximo de cada flow, de 30 a 3600 segundos. `metadata.json` agrega `timed_out` por flow y `flow_timeout_s` en las opciones.
- La skill `testigo-flows` enseña cómo usar `takeScreenshot` (solo un nombre o una subcarpeta, no `..` ni una ruta absoluta) y dónde quedan las capturas en la evidencia; `validar.py` avisa antes de correr si un flow usa una ruta que Maestro rechaza, en cualquiera de las formas de escribirlo (`takeScreenshot: ruta`, `{label: a, path: ruta}` o con `path:` en la línea siguiente), y no cuenta lo comentado. `testigo` ya pasaba siempre `--test-output-dir`: ahora un test lo asegura.
- La skill `testigo-flows` documenta lo que sí funciona para Compose y las pantallas sin ids: `testTagsAsResourceId` en la raíz de la app y otra vez en cada diálogo, bottom sheet y popup (cada uno con su propia raíz, con el código y cómo buscar los que faltan), y los nodos virtuales de accesibilidad, que se seleccionan por texto aunque no tengan `id`.
- `pantalla.py` de la skill `testigo-flows` borra el volcado anterior antes de cada `uiautomator dump` y comprueba su salida: si el dump falla, lo dice en vez de leer un volcado viejo como si fuera de ahora. Y la skill avisa de que un dump solo lista lo que está en pantalla.
- La skill `testigo-flows` trae la receta de las capas opcionales (coach marks, ofertas, permisos): el toque opcional, y la bifurcación con `extendedWaitUntil` sobre una alternancia en el id y `runFlow` con `when: visible`.
- La skill `testigo-flows` trae la receta de los toques frágiles: un toque pegado al borde inferior justo después de un scroll (`centerElement: true` y `waitForAnimationToEnd`), `scrollUntilVisible` con `speed` alto y el `input swipe` corto de adb, que se toman como un toque. `validar.py` avisa de las dos primeras antes de correr.
- La skill `testigo-flows` explica cómo esperar en Maestro (no hay espera fija; el truco de `extendedWaitUntil` con un texto inexistente y `optional` no espera el `timeout`, medido en 2.11.0) y por qué un spinner o una cuenta regresiva no se alcanzan a ver, con qué verificar en su lugar.
- `network-steps.txt` en la evidencia de cada flow con red: cada paso de Maestro (de `commands.json`) con las llamadas de Chucker que salieron mientras corría, para contar las llamadas desde un toque concreto sin partir el flow. `testigo` mide el desfase entre el reloj del dispositivo y el de la máquina al empezar el flow y lo dice en el archivo. Sin bodies ni query strings; un `label` en el paso lo hace fácil de encontrar.

### Cambiado

- El tiempo máximo de cada flow por defecto baja de 15 a 10 minutos: un Maestro colgado detenía la corrida el doble de lo necesario. Pasado el tiempo, también se cierran los procesos que lanzó, y el flow siguiente reinstala el driver de Maestro (lo mismo tras un reinicio del dispositivo).
- Dependencias al día: Mordant 3.1.1, kotlinx.serialization 1.12.0 y sqlite-jdbc 3.54.1.0. Clikt, kaml, JUnit y Kotlin ya estaban en lo último estable; el Gradle wrapper sigue en 9.7.1, la más nueva que soporta Kotlin 2.4.21.
- `logcat.txt` ya no pierde líneas en ráfagas: mientras corre el flow, los buffers de logcat del dispositivo suben a 16 MiB (`logcat -G`) y al terminar cada uno vuelve a su propio tamaño, después de leer el de crashes (achicar un buffer poda lo más viejo). Si el dispositivo no deja cambiarlos, o `logcat -g` no dice cómo estaban (`logcat -G` persiste y no habría cómo devolverlos), lo avisa y sigue sin tocarlos. Si la corrida se corta, los buffers también vuelven: con Ctrl+C, si Maestro falla, o, si la corrida muere sin poder limpiar (`kill -9`), la siguiente lo hace antes de empezar; así no toma los 16 MiB por el tamaño original. Con dos corridas sobre el mismo dispositivo, los devuelve la última en salir.
- Si una corrida anterior murió sin limpiar y dejó su `adb logcat` abierto, la siguiente lo cierra antes de empezar, para que no escriba líneas repetidas. Cada corrida anota la suya en `~/.testigo/run/` y solo borra esa anotación: dos corridas sobre el mismo dispositivo no se pisan. Solo cierra el de una corrida que ya no existe: nunca el de otra en marcha ni un proceso que no sea el `adb -s <serial> logcat` que esa corrida lanzó. Lo reconoce por cuándo arrancó, no solo por el pid (el sistema lo reusa): un `adb logcat` de otro dispositivo, `pidcat`, un `tail -f logcat.txt` o el servidor de adb con ese pid se quedan como están. También borra el logcat crudo (sin sanitizar) que esa corrida dejó en la carpeta temporal.

### Corregido

- `validar.py` de la skill `testigo-flows` terminaba con `AttributeError` en Python 3.8 después de validar el flow, porque usaba `Path.is_relative_to`, que es de 3.9. Ahora funciona con 3.8.

## 0.3.0 - 2026-10-20

Para pasar de un zip instalado a mano (como `~/herramientas/testigo-0.2.0`): ejecuta la línea de `install.sh`, luego `~/.local/bin/testigo setup` y borra la carpeta vieja. El instalador y `testigo setup` avisan mientras la otra siga primero en el PATH. Los comandos, la evidencia, `metadata.json` y los códigos de salida son los de 0.2.0.

### Agregado

- `install.sh`, para instalar y actualizar con una línea en macOS y Linux: `curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | bash`. Baja el release de GitHub, verifica su SHA-256 y lo deja en `~/.testigo/cli/<versión>`, con `cli/current` apuntando a la activa y el comando en `~/.local/bin/testigo`. Conserva la versión anterior para volver con `TESTIGO_VERSION`, instala un archivo local con `TESTIGO_ARCHIVE` y se quita con `--uninstall`. No usa sudo ni edita el perfil de la terminal.
- Cada versión se publica también en `.tar.gz`, porque `tar` viene en todo macOS y Linux y `unzip` no siempre, con el `.sha256` de cada archivo y `SHA256SUMS`.
- `testigo doctor` empieza por la versión de testigo y cómo está instalado: con `install.sh`, a mano o desde el código.
- `testigo setup` avisa si otro `testigo` va antes en el PATH, como un zip instalado a mano, y dice cómo corregirlo.

### Cambiado

- Con testigo instalado por `install.sh`, `testigo setup` pone `~/.testigo/cli/current/bin` en el PATH, no la carpeta de la versión: después de actualizar, una terminal nueva ya usa la versión nueva sin otro `testigo setup`.
- Si falta Java, el mensaje pide `testigo setup` con el comando que funciona en esa terminal: `testigo` si está en el PATH y, si no, la ruta de `cli/current` o la de esa instalación.

## 0.2.0 - 2026-10-19

Para actualizar desde 0.1.0: descomprime el zip nuevo al lado del anterior, ejecuta una vez `~/herramientas/testigo-0.2.0/bin/testigo setup` y borra la carpeta de 0.1.0. Sin `--repeat`, la evidencia, `metadata.json`, los flags y los códigos de salida son los de 0.1.0.

### Agregado

- Skill de Claude Code `testigo-flows` en `skills/`, también en el zip: arma el mapa de la app, escribe flows para Views XML o Compose (con o sin ids) y los valida con `testigo` hasta que pasan tres veces seguidas. Guía en `docs/FLOWS-CON-IA.md`.
- `testigo run --repeat N`: corre los flows N veces, en rondas, con la evidencia de cada corrida en `<flow>/<ronda>/`. La consola y `summary.md` dicen de cada flow si es estable, intermitente o falla siempre; `metadata.json` agrega `repeat`, `flaky` y, por corrida, `attempt` y `dir`.
- `testigo doctor` avisa en un Xiaomi (MIUI o HyperOS) si «Instalar vía USB» está apagado, y en un dispositivo con páginas de 16 KB si el build trae libs nativas sin alinear, que hacen aparecer «Android App Compatibility» sobre la app.
- `testigo setup` ofrece copiar la skill a `~/.claude/skills` si Claude Code está instalado, y la actualiza cuando cambia, con un respaldo de la anterior en `~/.testigo/backups/`. Es opcional y `--yes` no la instala.

### Cambiado

- Si la instalación falla con `INSTALL_FAILED_USER_RESTRICTED`, el error dice qué activar en el teléfono.
- `testigo --version` sale de la versión del build (`version` en `build.gradle.kts`): ya no se cambia en dos lugares.

## 0.1.0 - 2026-10-17

Primera versión.

### Comandos

- `testigo setup`: revisa la máquina e instala, con tu confirmación y sin root, lo que falte: Java 17 o más (si no hay ninguno, descarga Eclipse Temurin 21), Maestro en la versión de la suite, `adb` y `aapt2` del Android SDK, la debug key y el entorno de la terminal. Cada descarga se verifica con su SHA-256. Opciones `--check`, `--yes` y `--print-env`.
- `testigo init`: crea una suite en la carpeta actual (`testigo.yaml`, un flow de demo, `.env.testigo` y `.gitignore`), con el build que encuentra, su package y si trae Chucker.
- `testigo doctor`: revisa herramientas, versión de Maestro, dispositivo, build, instalación, `run-as`, la base de Chucker y los flows, y dice cómo arreglar cada problema.
- `testigo list`: lista los flows con su nombre y sus tags.
- `testigo run`: ejecuta los flows y genera la evidencia. Sin flags, pregunta qué capturar, qué flows y con qué app, y ofrece guardarlo en `testigo.yaml` (`--save`).

### Ejecución

- Un `maestro test` por flow, con filtros por tags y perfiles `smoke`, `dev` y `evidencia`. Precedencia: flag, perfil, `testigo.yaml` y valor por defecto.
- `--dry-run` muestra el plan completo y el comando equivalente sin tocar el dispositivo.
- APK o AAB de cualquier build type. Un AAB se convierte en tu máquina con el `bundletool` incluido. La app se instala solo si cambió.
- Revisa antes de Maestro que cada flow y subflow tenga la forma que Maestro exige (`appId` y `---`) y dice qué agregar.
- Si cortas con Ctrl+C, borra lo temporal del dispositivo y de tu máquina.

### Evidencia por flow

- Video de la pantalla, también si el flow falla, hasta el último paso.
- Requests y responses leídos de Chucker, en texto, en HAR o en ambos, con resumen por endpoint y alertas (errores, sin respuesta, lentos).
- Logcat de la app y detección de crashes, también nativos.
- Reporte paso a paso de Maestro, que se abre sin internet.
- Por corrida: `summary.md` para el PR, `metadata.json` y, con `--ci`, `junit.xml`.

### Local-only y seguridad

- Maestro corre sin analytics ni servicios en la nube.
- La corrida se bloquea (código 3) si un flow usa comandos de IA, `http` hacia afuera o credenciales escritas en el YAML.
- `--redact` enmascara tokens, claves y datos personales (presets `base`, `pii` y `payments`, más claves propias) y un leak scan revisa que no quede ninguno (código 4).

### Distribución

- Zip con su `.sha256`, para macOS y Linux. En Windows está sin probar y `testigo setup` solo revisa.
- `bin/testigo` encuentra Java 17 o más por su cuenta, también el que trae Android Studio.
- Colores legibles con fondo claro u oscuro.
- Licencia Apache 2.0.
