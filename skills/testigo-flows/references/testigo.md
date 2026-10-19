# testigo: lo que revisa y cómo leer lo que deja

Según el código de testigo 0.2.0.

## Qué flows ejecuta

- `flows:` de `testigo.yaml` (por defecto `flows/`), recorrido completo, **excepto** las carpetas `subflows/`, los archivos `config.yaml` y lo oculto.
- Un `maestro test` por flow. Los flows se filtran por `tags`: basta con un tag en común.
- La carpeta de evidencia de cada flow se llama como su `name` (o el archivo, si no tiene), con todo lo que no sea letra, número, punto o guion cambiado por `_`. Por eso `name` va en minúsculas y sin tildes.

## Comandos que usa la skill

Siempre desde la carpeta de la suite:

```sh
testigo list                                   # flows con nombre y tags
testigo run --dry-run --flows flows/x/caso.yaml  # forma y política, sin dispositivo
testigo run --flows flows/x/caso.yaml          # corrida real con evidencia
testigo run --flows flows/x/caso.yaml --repeat 3  # estabilidad: estable, intermitente o falla siempre
testigo run --tags smoke                        # todos los de un tag
testigo doctor                                  # si algo del entorno falla
```

Otras opciones útiles: `--flows` se repite, una vez por ruta (`--flows flows/a --flows flows/b`; `--flows a b` falla); `--env CLAVE=valor` gana sobre `.env.testigo`; `--device <serial>` si hay más de un dispositivo; `--profile smoke|dev|evidencia` cambia qué se captura.

## Códigos de salida

| Código | Significa | Qué hace la skill |
|---|---|---|
| 0 | Todo pasó | Sigue al siguiente nivel |
| 1 | Algún flow falló | Fase 5: leer el fallo y corregir |
| 2 | Entorno, configuración o forma de un flow | Si es forma, el mensaje dice qué agregar; si es entorno, `testigo doctor` |
| 3 | Política local-only | Quitar lo prohibido; nunca esquivar la regla |
| 4 | Quedó un dato sin sanitizar en la evidencia | Avisar al dev; no es un problema del flow |
| 130 | Cancelado | — |

## Forma (código 2)

Cada archivo YAML, también los subflows, necesita la configuración con `appId` (o `url`), la línea `---` y al menos un comando. Los problemas que reporta: archivo vacío, sin configuración, sin `appId`, sin `---`, sin comandos.

## Política local-only (código 3)

testigo revisa cada flow, sus subflows, sus scripts y cada `${...}`, y bloquea:

- **Comandos de IA:** cualquier comando que termine en `WithAI`.
- **Red en JavaScript:** `fetch`, `XMLHttpRequest`, `WebSocket`, y `http` hacia un host que no sea localhost.
- **Lo que no puede verificar:** `eval`, `Function`, `Java`, `Packages`, accesos indirectos, un script que no entiende, y `runFlow` o `runScript` con una ruta dinámica o hacia un archivo que no existe.
- **Credenciales escritas:**
  - un `inputText` con texto fijo justo después de tocar un campo cuyo selector sugiere secreto (password, pass, pwd, pin, otp, cvv, cvc, clave, contraseña, secret, token);
  - una clave de `env` con nombre de secreto y valor fijo;
  - cualquier texto que parezca un token o una clave.

La salida es siempre la misma: el valor va en `.env.testigo` y el flow usa `${VARIABLE}`.

## Variables

- `.env.testigo`, junto a `testigo.yaml`, con `CLAVE=valor`. testigo pasa cada una a Maestro como `-e`.
- En el flow: `${CLAVE}`. En un subflow, pásalas por `env` del `runFlow` o úsalas directo: Maestro las comparte.
- La skill nunca abre `.env.testigo`. Los nombres de las variables que existen van en `env:` de `app-map.yaml`.

## Qué deja cada corrida

```
evidence/<fecha>_<ticket>_<commit>/
├── summary.md      resultados, por qué falló cada flow y alertas de red; sin bodies ni query strings
├── metadata.json   app, dispositivo, versiones, opciones y cada flow
└── <flow>/
    ├── report.html  reporte de Maestro paso a paso
    ├── video.mp4
    ├── network.txt  cada request y response (o network.har)
    ├── logcat.txt
    └── crash.txt    solo si la app se cerró
```

Con `--repeat`, cada corrida queda en `<flow>/<ronda>/` (`login/2/report.html`) y `summary.md` dice de cada flow cuántas veces pasó y si es estable, intermitente o falla siempre.

## Leer un fallo sin exponer datos

En este orden, y solo lo necesario:

1. `summary.md`: completo. Dice qué flow falló, la primera línea del motivo y las alertas de red por endpoint.
2. La salida de Maestro en la consola: el paso y el selector que fallaron.
3. `crash.txt`, si existe: la traza del crash.
4. Errores del logcat, no el archivo entero:

   ```sh
   grep -E ' E |FATAL|AndroidRuntime' logcat.txt | tail -n 40
   ```

5. Red, solo método, status y ruta, sin query ni bodies:

   ```sh
   grep -A1 -E '^#[0-9]+ ' network.txt | grep -v '^--$' | sed 's/?.*//'
   ```

No abras `video.mp4` ni `report.html` para elegir selectores: para eso está la jerarquía.

## Problemas del dispositivo

No son fallos del flow: avísale al dev qué hacer y no cambies el YAML por ellos.

| Síntoma | Causa | Qué hacer |
|---|---|---|
| `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` | El teléfono (MIUI en Xiaomi, y otras capas) pide confirmar cada instalación por USB, y Maestro reinstala su driver en cada corrida | El dev activa «Instalar vía USB» en las opciones de desarrollador, o toca Instalar en el aviso de cada corrida; `testigo doctor` dice si está activado. Para leer la pantalla mientras tanto, `pantalla.py` no instala nada |
| «Android App Compatibility» sobre la app al arrancar, a veces con demora | Emulador o teléfono con páginas de 16 KB y un APK con librerías nativas sin alinear. `clearState` borra «Don't Show Again», así que vuelve en cada flow | Correr en una imagen de 4 KB o en un teléfono sin 16 KB, o alinear el APK (cambio de build: va a `huecos.md`). En el flow, ciérralo con el tap opcional de patrones. `testigo doctor` lo confirma y dice qué libs están sin alinear |
| `adb devices` vacío o `unauthorized` | Cable, depuración USB o la huella del equipo sin aceptar | El dev reconecta y acepta el aviso en el teléfono |
| `Shell does not have permission to access user 10` | Perfil de trabajo o segundo espacio | Agrega `--user 0` a los comandos de `pm` |
| La corrida tarda en arrancar o se corta sin paso fallido | Dispositivo bloqueado o con la pantalla apagada | El dev lo desbloquea y deja la pantalla encendida |
