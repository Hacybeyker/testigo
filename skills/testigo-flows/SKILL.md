---
name: testigo-flows
description: Escribe, valida y mantiene flows de Maestro (YAML) para apps Android que se ejecutan con testigo. Úsala para crear flows de un caso o de una feature, hacer el inventario de una app (mapa, cobertura y backlog de casos), corregir un flow que falla o es intermitente, o listar qué le falta a una app para poder probarla. Sirve para Views XML, Compose, apps sin ids y proyectos de muchos módulos.
---

# testigo-flows

Escribes flows de Maestro que `testigo` ejecuta y valida. Dos ideas mandan sobre todo lo demás:

- **Un selector nunca se adivina.** Sale del código de la app o de la jerarquía real de la pantalla.
- **Un flow solo está listo cuando pasó en el dispositivo**, tres veces seguidas.

## Antes de empezar

Necesitas tres cosas. Si falta alguna, pregúntala antes de seguir:

1. **La suite:** la carpeta con `testigo.yaml`. De ahí salen el `appId` (`app.package`, o el `appId` de los flows que ya existen), la carpeta de flows y `maestro.version`.
2. **El repositorio de la app:** el código fuente. Se guarda en `app-map.yaml` como `app.source`.
3. **Un dispositivo conectado:** solo para confirmar selectores y para las corridas reales.

Luego:

- Si `maestro.version` no es `2.11.0`, avisa: las referencias están verificadas en 2.11.0. Antes de usar un comando o campo que no esté en [references/maestro.md](references/maestro.md), confírmalo con `check-syntax`.
- Lee `app-map.yaml` si existe; si no, la fase 1 lo crea. En proyectos grandes, lee solo el índice y los módulos del caso.
- Si no puedes ejecutar comandos en la máquina del dev (sin `adb` o sin el dispositivo), dale los comandos **de a uno**, espera su salida y sigue. Nunca le pases varios pasos juntos.

## Alcance: qué te pidieron

La skill no recorre toda la app por su cuenta: trabaja sobre el alcance que pide el dev. Si el pedido no deja claro cuál es, pregunta antes de empezar.

| Pedido | Ejemplo | Qué haces | Entregas |
|---|---|---|---|
| Un caso | «flow para cambiar el tema a oscuro» | Mapeas solo las pantallas del camino y escribes el flow, más los subflows que falten | El flow validado |
| Una feature o módulo | «los flows de favoritos» | Mapeas ese módulo, propones la lista de casos con [references/casos.md](references/casos.md) y esperas el visto bueno | Los flows aprobados, validados |
| Inventario de la app | «inventario completo de la app» | Mapeas todos los módulos con la pasada estática, sin dispositivo, y armas la cobertura y un backlog priorizado. **No escribes flows todavía** | `app-map.yaml`, `cobertura.md` y `huecos.md` |
| Corregir o mantener | «este flow falla», «cambió el login» | Fase 5, o revalidación por `git diff` | Flows corregidos y el mapa al día |

Después de un inventario, los flows se escriben por tandas en el orden del backlog, primero el smoke, y cada tanda se valida antes de empezar la siguiente. Nunca escribas decenas de flows sin validarlos.

## Reglas que no se negocian

1. **Orden de selectores:** `id` → content description → texto visible → relativo a un ancla con id o texto fijo → `index`. Nunca `point`. Detalle y trampas en [references/maestro.md](references/maestro.md); sin ids, [references/sin-ids.md](references/sin-ids.md).
2. **Forma:** cada archivo, también los subflows, empieza con `appId`, `name` y `tags`, y luego `---`. Sin eso testigo corta con código 2.
3. **Política local-only de testigo** (si no, código 3): nada de comandos `*WithAI`, nada de `http`, `fetch`, `XMLHttpRequest` ni `WebSocket` en JavaScript, nada de credenciales, tokens o claves escritas en el YAML, y nada de `runFlow` o `runScript` con rutas dinámicas. Los datos van como `${VARIABLE}` y viven en `.env.testigo`.
4. **Nunca leas `.env.testigo`.** Usa los nombres de variables de `app-map.yaml` (`env:`) o pregúntaselos al dev. Nunca escribas valores reales en ningún archivo.
5. **Maestro solo a través del script de la skill:** `bash ${CLAUDE_SKILL_DIR}/scripts/maestro-local.sh <argumentos>` (abajo, `maestro-local.sh`). Usa el mismo entorno local-only que testigo. No uses Maestro Cloud, Maestro Studio, la herramienta `cheat_sheet` del MCP de Maestro, ni capturas de pantalla para elegir selectores.
6. **Privacidad:** respeta `privacy` de `app-map.yaml`. Con `code-only` no lees la pantalla: los selectores quedan sin confirmar hasta la corrida real. Con `masked`, lees la pantalla con `pantalla.py --sin-texto`. Con `hierarchy`, completa. Lo que leas de la evidencia, léelo con filtros (ver [references/testigo.md](references/testigo.md)), nunca los cuerpos completos de red.
7. **No relajes un flow para que pase:** ni asserts más débiles, ni `optional: true` en un paso o un assert del caso, ni esperas largas sin motivo. `optional: true` solo sirve para cerrar algo que aparece a veces (ver patrones). Si la app se comporta mal, es un bug: se reporta con la evidencia.
8. **No toques el código de la app.** Lo que falte para probar va a `huecos.md`, con el cambio de código sugerido.
9. **Cada flow verifica algo, y ese assert tiene que poder fallar:** termina con `assertVisible`, `assertNotVisible` o `extendedWaitUntil` sobre el resultado esperado. Si verificas un estado que cambia con la acción (marcado, seleccionado, visible), verifica antes de la acción el estado contrario con el mismo selector. Si el selector es demasiado amplio, ese primer assert falla y lo descubres; si no, un assert que pasa siempre te haría entregar un flow que no prueba nada.

## Gasta poco contexto

Todo lo que lees queda en la conversación y se vuelve a enviar en cada paso. Lee lo justo:

- **Mapa:** `python3 -I ${CLAUDE_SKILL_DIR}/scripts/mapa.py` da el índice; `mapa.py home movie` da solo esas pantallas. No hagas `cat` del mapa entero.
- **Código:** busca con `grep -n` y lee el tramo con `sed -n 'inicio,finp'`: la función del caso, sin imports. Un archivo completo solo si tiene menos de 100 líneas. En zsh, pon entre comillas los patrones: `--include='*.kt'`.
- **Referencias:** lee la que el paso necesita y, si es larga, solo su sección (`grep -n '^## '` y luego `sed -n`). No las cargues por si acaso.
- **Pantalla:** `python3 -I ${CLAUDE_SKILL_DIR}/scripts/pantalla.py <package>` muestra solo los nodos útiles de la app, sin instalar el driver de Maestro.
- **Validación sin dispositivo:** `python3 -I ${CLAUDE_SKILL_DIR}/scripts/validar.py <flow>` hace el primer nivel de la fase 4 y responde en pocas líneas.
- **Corridas:** `testigo run --flows <flow> 2>&1 | sed -n '/^Flows$/,$p;/^Error/p;/^Usage/p'`: solo el resultado, sin el plan, pero con los errores. Si no sale nada, la corrida no empezó: revisa las opciones. Varios flows van con `--flows a --flows b`, no `--flows a b`.
- **Errores:** las líneas que explican el fallo (`grep -m5 -E 'Failure|Exception|Error'`), nunca el stack trace entero.
- **Dispositivo:** pide el dato puntual (`adb shell settings get global airplane_mode_on`). Nunca `dumpsys` ni `getprop` completos: gastan contexto y exponen datos del equipo y de su red.
- **Moverte por la app al explorar:** toca el centro de los `bounds` que te dio `pantalla.py` (`adb shell input tap X Y`), nunca coordenadas a ojo. Para varios pasos, un flow temporal con `maestro-local.sh test`.
- **Comandos en zsh:** no guardes un comando en una variable para ejecutarlo después (`M="bash x.sh"; $M ...`): zsh no separa las palabras y falla. Escribe el comando completo.

## Fase 1. Descubrir: el mapa de la app

1. Sigue el recorrido de [references/android.md](references/android.md): módulos, pantallas, navegación, strings por idioma, y si hay ids, `testTag` y `testTagsAsResourceId`.
2. Clasifica cómo expone la app sus elementos (`app.ids`: `views`, `testtags`, `partial` o `none`), por módulo si difieren. Si no es `views` ni `testtags`, sigue [references/sin-ids.md](references/sin-ids.md): es el caso común, no la excepción.
3. Escribe o actualiza `app-map.yaml` en la suite, con el esquema de [references/app-map.md](references/app-map.md) y la plantilla [templates/app-map.yaml](templates/app-map.yaml). Guarda `app.commit` con `git -C <app.source> rev-parse --short HEAD`.
4. Todo selector que solo viste en el código queda con `confirmed: false`.
5. Si `privacy` lo permite, confirma en el dispositivo: con la app en esa pantalla, corre `pantalla.py <package>` (o `maestro-local.sh hierarchy --compact`) y marca `confirmed: true` lo que aparece. Anota en `quirks` lo que no coincide con el código, por ejemplo un diálogo que es otra ventana o un texto que llega fusionado con otro.
6. Mapea solo lo que el alcance pide. En un inventario, mapea todo con la pasada estática y deja la confirmación en el dispositivo para cuando se escriban los flows de cada pantalla.

## Fase 2. Planear los casos

1. El dev dice qué probar. Si pide una feature entera, propón la lista de casos con [references/casos.md](references/casos.md) (camino feliz y casos borde) y espera su visto bueno antes de escribir más de tres flows.
2. Por cada caso define: nombre, punto de partida, pasos, resultado esperado, datos (`${VAR}`), tags y subflows que reutiliza.
3. Primero el smoke: los caminos que, si se rompen, rompen la app. Después, lo que el dev priorice.
4. Si un caso necesita algo que la app no permite (OTP, captcha, biometría, datos que no controla), dilo en el plan; no lo escribas a medias.

## Fase 3. Escribir

1. Carpetas: `flows/<feature>/<caso>.yaml` y subflows en `flows/subflows/`. testigo no ejecuta solas las carpetas `subflows/`.
2. Parte de [templates/flow.yaml](templates/flow.yaml) y [templates/subflow.yaml](templates/subflow.yaml). Los casos difíciles están en [references/patrones.md](references/patrones.md).
3. Empieza en un estado conocido: `launchApp` con `clearState: true`, o un deep link con `openLink`.
4. Reutiliza: login, onboarding y caminos de navegación comunes van en subflows con parámetros por `env`.
5. Si la app tiene varios idiomas y el elemento no tiene id, usa alternancia con el texto de cada `strings.xml` (`"Favoritos|Favorites"`).
6. `name` en minúsculas, sin tildes ni espacios (`favoritos_agregar_desde_detalle`): testigo lo usa como nombre de la carpeta de evidencia y cambia por `_` lo que no sea letra, número, punto o guion.
7. Una línea de comentario arriba de cada flow: qué prueba y por qué.

## Fase 4. Validar

En este orden, sin saltar niveles. Los dos primeros no usan el dispositivo.

1. **Forma, política y sintaxis:** desde la suite, `validar.py <flow>`. Corre `testigo run --dry-run` (código 2 = forma, 3 = política) y `maestro-local.sh check-syntax` del flow y de cada subflow que llama. `check-syntax` solo no basta: acepta un archivo sin `appId`. También avisa si un assert usa `leftOf`, `rightOf`, `above` o `below` sin `childOf`: revísalo antes de seguir.
2. **Selectores contra el mapa:** cada selector del flow existe en `app-map.yaml`. Si alguno está `confirmed: false`, confírmalo (fase 1) o avisa que lo confirmará la corrida real.
3. **Corrida real:** `testigo run --flows <flow> 2>&1 | sed -n '/^Flows$/,$p;/^Error/p;/^Usage/p'`.
4. **Estabilidad:** `testigo run --flows <flow> --repeat 3 2>&1 | sed -n '/^Resultado/,$p;/^Error/p;/^Usage/p'`. Listo solo si dice `estable` (3/3). `intermitente` también es un fallo: fase 5, con el motivo y el `report.html` de la corrida que falló. Para una tanda, una sola corrida con `--flows` repetido. Si testigo no conoce `--repeat` (la 0.1.0), corre el nivel 3 tres veces.

## Fase 5. Corregir cuando falla

1. Lee primero `summary.md` de la corrida y la salida de Maestro: qué paso falló y con qué selector. Después, lo que haga falta de la evidencia del flow, con los filtros de [references/testigo.md](references/testigo.md).
2. Si la app sigue en la pantalla del fallo, lee la pantalla con `pantalla.py` (si `privacy` lo permite).
3. Clasifica la causa y actúa:
   - **Selector equivocado:** corrígelo y actualiza el mapa.
   - **Espera corta o animación:** agrega la espera concreta (ver patrones).
   - **Estado inicial distinto:** arregla el inicio del flow (`clearState`, subflow de preparación).
   - **Dato de prueba que no sirve:** pregúntale al dev.
   - **Bug de la app** (crash en `crash.txt`, 4xx o 5xx inesperado, pantalla incorrecta): no toques el flow; repórtalo.
   - **Entorno** (el driver de Maestro no se instala, el dispositivo se desconecta): no es el flow. Ver «Problemas del dispositivo» en [references/testigo.md](references/testigo.md).
4. Como máximo tres intentos por flow. Si no pasa, entrégalo marcado como pendiente, con la causa y lo que falta.

## Fase 6. Entregar

Al terminar, deja en la suite y resume en la respuesta:

- **Flows:** ruta, qué prueba, tags y estado (estable, o pendiente con el motivo).
- **`app-map.yaml` actualizado:** selectores confirmados, y en cada pantalla, los flows que la cubren.
- **`huecos.md`:** cada elemento sin selector estable, con el cambio de código sugerido ([templates/huecos.md](templates/huecos.md)).
- **Cobertura:** qué pantallas del alcance tienen flow, cuáles no y cuáles fallan a veces. En un inventario va en `cobertura.md` ([templates/cobertura.md](templates/cobertura.md)), con el backlog priorizado de casos por escribir.

## Proyectos grandes

- No leas todo el proyecto. Saca la lista de módulos de `settings.gradle(.kts)` y llega a la pantalla del caso buscando su ruta de navegación, su Fragment o Composable, o un texto visible en `strings.xml`.
- Con muchos módulos, el mapa se divide: `app-map.yaml` es el índice y `app-map/<modulo>.yaml` guarda las pantallas de cada uno. Carga solo los que el caso necesita.
- Los ids pueden repetirse entre módulos: confirma la pantalla con un assert antes de tocar un id genérico (`button`, `title`).
- Cuando la UI cambia: `git -C <app.source> diff --name-only <app.commit> HEAD` → pantallas del mapa afectadas → flows que las usan → revalida esos flows y actualiza `app.commit`.

## Referencias

- [references/maestro.md](references/maestro.md): comandos, selectores y cómo compara Maestro, verificado en 2.11.0.
- [references/android.md](references/android.md): cómo leer un proyecto Android para sacar selectores: módulos, XML, Compose, navegación, strings y flavors.
- [references/sin-ids.md](references/sin-ids.md): apps sin ids o con pocos: cómo detectarlo, cómo seleccionar por texto y cómo priorizar los huecos.
- [references/testigo.md](references/testigo.md): forma, política, variables, descubrimiento de flows, códigos de salida y cómo leer la evidencia.
- [references/patrones.md](references/patrones.md): listas, diálogos que aparecen a veces, permisos, teclado, datos que cambian, bottom sheets, carruseles, WebView y sin red.
- [references/casos.md](references/casos.md): catálogo de casos borde para planear.
- [references/app-map.md](references/app-map.md): esquema del mapa.
