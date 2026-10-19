# Escribir flows con IA: la skill `testigo-flows`

`testigo` ejecuta los flows y junta la evidencia, pero alguien tiene que escribirlos. La skill `testigo-flows` es para Claude Code: lee el código de la app, confirma los selectores en la pantalla real, escribe los flows y los corre con `testigo` hasta que pasan tres veces seguidas. Viene en `skills/testigo-flows/`, en el repositorio y en el zip.

## Qué hace

- **Arma el mapa de la app** (`app-map.yaml` en la suite): pantallas, cómo se llega a cada una, el selector de cada elemento y si ya se confirmó en el dispositivo. Es lo específico de cada proyecto; la skill es la misma para todos.
- **Elige selectores sin adivinar:** primero ids (`android:id` o `testTag` con `testTagsAsResourceId`), después content description y texto de `strings.xml`. Sirve para Views XML, Compose y apps sin ids.
- **Valida cada flow en cuatro niveles:** forma, política y sintaxis sin dispositivo; selectores contra el mapa; una corrida real; y tres corridas seguidas sin fallos (`testigo run --repeat 3`).
- **Exige asserts que puedan fallar:** verifica el estado contrario antes de la acción, para que un flow en verde pruebe algo.
- **Deja la lista de lo que falta en la app** (`huecos.md`): cada elemento sin selector estable, con el cambio de código que lo arregla.

## Requisitos

- [Claude Code](https://code.claude.com/docs).
- `testigo` y Maestro instalados (`testigo setup`), y una suite creada con `testigo init`.
- `python3`, para los scripts de la skill.
- El repositorio de la app en tu máquina.
- Un dispositivo o emulador conectado, para confirmar selectores y correr los flows.

## Instalar

Con Claude Code ya instalado (existe `~/.claude`), `testigo setup` ofrece copiarla a `~/.claude/skills/testigo-flows`. Es opcional: no viene marcada y `testigo setup --yes` no la instala. Al actualizar `testigo`, `testigo setup` la actualiza también, y deja la copia anterior en `~/.testigo/backups/` por si la editaste. Claude Code la encuentra en la próxima sesión.

A mano, desde la carpeta donde descomprimiste el zip (en el [README](../README.md#instalar-y-configurar), `~/herramientas`):

```bash
mkdir -p ~/.claude/skills
cp -R ~/herramientas/testigo-0.2.0/skills/testigo-flows ~/.claude/skills/
```

Si la desarrollas, un enlace a `skills/testigo-flows` de un clon del repositorio sirve igual; `testigo setup` no toca un enlace.

## Usar

Abre Claude Code en la carpeta de la suite, con acceso al código de la app:

```bash
cd mi-suite
claude --add-dir ~/proyectos/mi-app
```

Antes, `testigo doctor` en la suite: si avisa de un Xiaomi sin «Instalar vía USB» o de un dispositivo de 16 KB, arréglalo primero, o los flows saldrán intermitentes por el dispositivo y no por la app.

Y pídele lo que necesitas con `/testigo-flows`. La skill no recorre toda la app por su cuenta: trabaja sobre el alcance que pides.

| Alcance | Ejemplo | Qué entrega |
|---|---|---|
| Un caso | `/testigo-flows escribe un flow que cambie el tema a oscuro y verifique que queda marcado` | El flow, validado |
| Una feature | `/testigo-flows propón y escribe los flows de favoritos` | Primero la lista de casos para que la apruebes; después, los flows validados |
| Inventario | `/testigo-flows haz el inventario completo de la app` | `app-map.yaml`, `huecos.md` y `cobertura.md` con un backlog priorizado. No escribe flows |
| Corregir | `/testigo-flows flows/login/ok.yaml falla, corrígelo` | El flow corregido, o el bug de la app con su evidencia |

En una app grande, empieza por el inventario y escribe los flows por tandas, en el orden de `cobertura.md`. Usa una sesión nueva por tanda: una sesión larga arrastra todo su contexto en cada paso.

## Qué deja en la suite

```
mi-suite/
├── app-map.yaml        el mapa de la app
├── huecos.md           lo que la app necesita para ser más fácil de probar
├── cobertura.md        pantallas con y sin flow, y el backlog de casos
└── flows/
    ├── <feature>/<caso>.yaml
    └── subflows/       piezas reutilizables: testigo no las ejecuta solas
```

## Privacidad

`testigo` y Maestro siguen corriendo en tu máquina y sin servicios externos: la skill llama a Maestro con el mismo entorno local-only. Pero **lo que la skill lee se envía al proveedor del modelo**: el código de la app y, si lo permites, la jerarquía de la pantalla. Antes de usarla en un proyecto, confirma que la política de tu empresa lo permite.

Para limitar lo que ve:

- **`privacy` en `app-map.yaml`:** `code-only` (no lee la pantalla), `masked` (lee la pantalla con los textos ocultos) o `hierarchy` (completa).
- **Credenciales:** la skill nunca abre `.env.testigo`; usa solo los nombres de las variables. Para impedirlo del todo, agrega en la suite un `.claude/settings.json`:

  ```json
  { "permissions": { "deny": ["Read(./.env.testigo)"] } }
  ```

- **Evidencia:** lee `summary.md` y, de la red y el logcat, solo líneas filtradas (método, código y ruta; errores), nunca los cuerpos.

## Modelo y costo

La skill no fija el modelo ni el esfuerzo: los eliges tú en Claude Code. Como referencia, en el piloto con Now in Android (35 módulos, Compose) y Opus en esfuerzo `medium`:

| Tarea | Tokens |
|---|---|
| Inventario completo, sin dispositivo | ~106 mil |
| Tanda de 5 flows y 2 subflows, con diagnóstico de fallas | ~126 mil |
| Tanda de 7 flows | ~106 mil |

Una vez armado el mapa, cada flow cuesta unos 15 a 20 mil tokens dentro de una tanda. El primer flow suelto, sin mapa y con esfuerzo máximo, costó ~114 mil.

## Limitaciones

- **OTP, 2FA, captcha y biometría** no se automatizan: hace falta un usuario o un build de QA que los salte.
- **Avisos del sistema** que tapan la app (por ejemplo, «Android App Compatibility» en un dispositivo de 16 KB con un build sin alinear) vuelven intermitentes los flows. La skill los cierra cuando aparecen, pero lo estable es un dispositivo sin ellos.
- **MIUI** pide confirmar la instalación del driver de Maestro en cada corrida: activa «Instalar vía USB» en las opciones de desarrollador. `testigo doctor` avisa de esto y del caso de 16 KB.
- Lo visual (colores, tamaños) no se compara: Maestro ve el árbol de accesibilidad, no la imagen.
