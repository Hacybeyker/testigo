# Changelog

Los cambios importantes de `testigo`, de la versión más nueva a la más vieja. El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y las versiones, [Semantic Versioning](https://semver.org/lang/es/).

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
