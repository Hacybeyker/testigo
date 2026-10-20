# Changelog

Los cambios importantes de `testigo`, de la versión más nueva a la más vieja. El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones, [Semantic Versioning](https://semver.org/lang/es/).

## 0.3.0-rc.1 - 2026-10-20

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
