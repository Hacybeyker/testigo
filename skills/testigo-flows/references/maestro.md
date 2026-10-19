# Maestro: lo que necesitas para escribir flows

Verificado contra el CLI de Maestro 2.11.0 (sus clases, no solo la documentación). Si la suite usa otra versión, confirma con `check-syntax` cualquier cosa que no esté aquí.

## Forma de un archivo

```yaml
# Qué prueba este flow, en una línea.
appId: com.empresa.app.qa
name: feature_caso          # testigo lo usa como carpeta de evidencia: sin tildes ni espacios
tags: [smoke, feature]
env:                      # opcional: valores por defecto; nunca secretos
  MODO: normal
onFlowComplete:           # opcional: limpieza que corre aunque el flow falle
  - setAirplaneMode: disabled
---
- launchApp:
    clearState: true
```

- Claves de configuración que acepta: `appId`, `name`, `tags`, `env`, `onFlowStart`, `onFlowComplete`, `properties`, `url`.
- Todos los comandos aceptan `label` (texto que aparece en el reporte en lugar del comando) y `optional`.

## Cómo encuentra un elemento

- **`id`** compara contra el `resource-id`: el valor completo (`com.app:id/login_submit`) o lo que va después de la última `/`. Escribe solo el nombre: `id: login_submit`. Así no depende del package ni del sufijo del build type.
- **`text`** compara contra cuatro atributos: el texto visible, el hint, el content description y el error del campo (por ejemplo el de un `TextInputLayout`).
- **Coincidencia de `text` e `id`:** es una expresión regular que debe calzar con la cadena completa. Además calza si el patrón es exactamente igual al texto, y también compara el texto con los saltos de línea cambiados por espacios.
  - `text: Total` no encuentra «Total a pagar»: usa `Total.*`.
  - Un texto con `( ) . ? + * [ ] $ ^ |` calza igual si lo escribes completo y exacto, porque compara también por igualdad. Pero si el patrón no es una regex válida (por ejemplo empieza con `*` o `?`), Maestro falla antes de comparar: escápalo.
- **Espera:** Maestro busca cada elemento durante 17 segundos, o 7 si el comando es `optional`. No hace falta una espera antes de cada tap.
- **Relativos** (`below`, `above`, `leftOf`, `rightOf`): comparan **un solo eje** y ordenan por distancia al ancla, el más cercano primero. `below` y `above` comparan solo el borde superior (Y); `leftOf` y `rightOf`, solo el borde izquierdo (X).
  - Trampa: `leftOf: "Oscuro"` también calza con los elementos de otras filas que estén más a la izquierda. En un tap gana el más cercano, que suele ser el de la misma fila. En un `assertVisible` basta con que calce cualquiera, así que el assert pasa aunque mire la fila equivocada. Para «el de esta fila», usa `childOf`. `validar.py` avisa cuando un assert usa un relativo sin `childOf`.
- **`childOf`:** busca el primer elemento que calza con el selector de `childOf` (los clickables primero) y después busca solo dentro de él. Con `childOf: { containsDescendants: [ { text: "Oscuro" } ] }` el padre es la fila clickable que contiene ese texto, si la fila es clickable. Si no lo es, el padre puede terminar siendo un contenedor más grande: confírmalo con `pantalla.py`.
- **`containsDescendants`** calza con todos los ancestros que contienen esos elementos, hasta la raíz. Solo, sirve para tocar una fila clickable. En un assert, úsalo dentro de `childOf`, o junto con un filtro que solo cumple la fila: si el estado vive en la fila misma (`checked: true` en una fila `selectable`), los ancestros no están marcados y quedan fuera.
- **`text` e `id`** se quedan con el elemento más profundo que calza: un contenedor que hereda el texto de su hijo no compite con él.
- **`index`** ordena los que calzan por posición: de arriba hacia abajo y, en la misma fila, de izquierda a derecha.

## Campos de un selector

`text`, `id`, `index`, `below`, `above`, `leftOf`, `rightOf`, `childOf`, `containsChild`, `containsDescendants` (lista), `enabled`, `selected`, `checked`, `focused`, `width`, `height`, `tolerance`, `traits`, `point`, `css` (WebView).

Opciones de los taps: `retryTapIfNoChange`, `waitUntilVisible`, `repeat`, `delay`, `waitToSettleTimeoutMs`, `optional`, `label`.

## Comandos más usados

```yaml
- launchApp:                  # appId, clearState, stopApp, permissions, arguments
    clearState: true
    permissions: { all: deny }
- openLink: app://perfil       # deep link: llega directo a una pantalla
- tapOn: { id: login_submit }
- tapOn: "Ingresar"            # forma corta: text
- longPressOn: { text: "Pedido 123" }
- doubleTapOn: { id: foto }
- inputText: ${USUARIO}
- eraseText: 20               # borra N caracteres del campo enfocado
- hideKeyboard
- back
- pressKey: Enter
- assertVisible: { text: "Bienvenido.*" }
- assertNotVisible: { id: progress }
- extendedWaitUntil:
    visible: { id: home_root }
    timeout: 20000            # milisegundos
- waitForAnimationToEnd:
    timeout: 5000
- scroll                       # una vez hacia abajo
- scrollUntilVisible:          # element, direction, timeout, speed, visibilityPercentage, centerElement
    element: { text: "Términos" }
    direction: DOWN
    centerElement: true
- swipe:
    direction: LEFT
- copyTextFrom: { id: order_number }   # el valor queda en ${maestro.copiedText}
- runFlow:
    file: ../subflows/login.yaml
    env: { USUARIO: ${USUARIO_ADMIN} }
- runFlow:
    when:
      visible: { text: "Califica la app" }
    file: ../subflows/cerrar-calificacion.yaml
- repeat:
    times: 3
    commands:
      - scroll
- retry:                       # para un paso que depende del backend, no para tapar bugs
    maxRetries: 2
    commands:
      - tapOn: "Reintentar"
- setAirplaneMode: enabled     # enabled | disabled
- setPermissions:
    permissions: { notifications: allow }
- setLocation: { latitude: -8.11, longitude: -79.03 }
- stopApp
- clearState
- takeScreenshot: paso-final
```

- `copyTextFrom` copia el texto visible; si no hay, el hint; si no, el content description.
- `when` acepta `visible`, `notVisible`, `true` (expresión JS) y `platform`.
- Las rutas de `runFlow` y `runScript` son relativas al archivo que las llama, y deben ser literales: testigo bloquea las dinámicas.

## JavaScript

- `${...}` evalúa JavaScript en el flow: `${USUARIO}`, `${maestro.copiedText}`, `${output.total}`.
- `evalScript` y `runScript` pueden guardar valores en `output`. Sin red: testigo bloquea `fetch`, `XMLHttpRequest`, `WebSocket`, `http`, `eval`, `Function`, `Java` y `Packages`.

## Ver lo que ve Maestro

- `maestro-local.sh hierarchy`: el árbol completo de la pantalla actual, en JSON.
- `maestro-local.sh hierarchy --compact`: el mismo árbol en CSV, más corto; úsalo por defecto.
- `maestro-local.sh check-syntax <archivo>`: valida un archivo sin dispositivo.

Lo que no aparece en la jerarquía, un flow no lo puede seleccionar.

## No usar

- `assertWithAI`, `assertNoDefectsWithAI`, `extractTextWithAI` y cualquier `*WithAI`: mandan capturas a un servicio de IA. testigo los bloquea.
- `startRecording` y `stopRecording`: testigo ya graba un video por flow.
- `assertScreenshot`: compara imágenes; se rompe con cualquier dato que cambie.
- `tapOn: { point: "50%,80%" }`: depende del tamaño de pantalla y del layout.
