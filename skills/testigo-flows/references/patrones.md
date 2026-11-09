# Patrones para los casos difíciles

Cada patrón dice cuándo usarlo. Si un caso no está aquí, escríbelo con las reglas de `maestro.md` y confírmalo en el dispositivo.

## Llegar a un elemento de una lista larga

Las listas perezosas solo tienen en la jerarquía los ítems visibles.

```yaml
- scrollUntilVisible:
    element: { text: "Términos y condiciones" }
    direction: DOWN
    centerElement: true      # deja el elemento al centro: lo de abajo también queda a la vista
- tapOn: "Términos y condiciones"
```

## Una fila entre muchas con el mismo id

```yaml
- tapOn:
    id: btn_detalle
    childOf:
      containsDescendants:
        - text: "Pedido 1042"
```

Funciona si la fila es clickable: `childOf` toma primero los clickables que contienen ese texto. Si la fila no es clickable, confírmalo con `pantalla.py` antes de confiar.

## Estado de un RadioButton, Checkbox o Switch sin texto propio

El control no tiene texto: su etiqueta es otro nodo de la misma fila. Búscalo dentro de la fila con `childOf`, nunca con `leftOf` o `rightOf`, que no miran la fila.

```yaml
# Antes de la acción, el estado contrario: prueba que el selector distingue la fila
- assertNotVisible:
    checked: true
    childOf:
      containsDescendants:
        - text: "Oscuro|Dark"
- tapOn: "Oscuro|Dark"
- assertVisible:
    checked: true
    childOf:
      containsDescendants:
        - text: "Oscuro|Dark"
```

Para «no está marcado» usa `assertNotVisible` con `checked: true`, no `assertVisible` con `checked: false`: cualquier nodo de la fila que no se puede marcar (la etiqueta, por ejemplo) ya tiene `checked: false`.

Si `pantalla.py` muestra que el estado está en **la fila misma** (una fila `selectable` o `toggleable`, sin un control propio marcado), el estado no está dentro de la fila, sino en ella:

```yaml
- assertVisible:
    checked: true
    containsDescendants:
      - text: "Dark"
```

Funciona porque los ancestros que también contienen «Dark» no están marcados. Y no uses `childOf` ahí: una fila marcada puede dejar de ser clickable, y `childOf` busca primero los clickables.

## Mensajes que duran poco: snackbar con acción

Un snackbar corto dura unos 4 segundos, y Maestro, después de cada tap, espera a que la pantalla se asiente. Con esa espera puede no quedar tiempo para tocar la acción:

```yaml
- tapOn:
    text: "Unbookmark"
    childOf: { id: "newsResourceCard:2" }
    waitToSettleTimeoutMs: 500      # no esperar a que la pantalla se quede quieta
- extendedWaitUntil:
    visible: "UNDO"
    timeout: 3000
- tapOn:
    text: "UNDO"
    waitToSettleTimeoutMs: 500
```

Entre el tap que lo muestra y el de la acción, nada de asserts. Lo que pasó antes del UNDO, verifícalo en otro flow sin UNDO. Anota el margen en la cobertura: en un equipo más lento es lo primero que se vuelve intermitente.

## Un ítem cuyo texto es un dato que no controlas

Cuando el texto sale del backend (un título, un nombre), cópialo y reúsalo, en vez de escribirlo en el flow:

```yaml
- copyTextFrom:
    text: ".+"               # cualquier elemento con texto...
    below: { text: "Destacados" }   # ...el más cercano debajo del encabezado
- tapOn: ${maestro.copiedText}
# más adelante, en otra pantalla
- assertVisible: ${maestro.copiedText}
```

Funciona porque Maestro también compara por igualdad exacta. Es un hueco de testabilidad: pide un `testTag` o un id para el ítem.

## Diálogos que aparecen a veces

Calificar la app, novedades, encuestas, y avisos del sistema que tapan la app (compatibilidad de páginas de 16 KB, «la app no responde»). No los toques por si acaso, y ojo con el momento: muchos aparecen uno o dos segundos **después** de que la pantalla ya se ve.

Lo seguro es un tap opcional justo después de esperar la pantalla. Maestro espera hasta 7 segundos a que aparezca; si no aparece, sigue:

```yaml
- extendedWaitUntil:
    visible: { id: "forYou:topicSelection" }
    timeout: 20000
- tapOn:
    text: "OK"
    id: "android:id/button2"     # el botón neutro: no cambia la configuración del dispositivo
    optional: true               # aviso del sistema que a veces aparece; no es un paso del caso
- assertVisible: { id: "forYou:topicSelection" }   # la pantalla quedó a la vista
```

- `runFlow` con `when: visible` evalúa en ese instante: si el diálogo aparece un segundo después, lo salta y el paso siguiente falla con el diálogo encima. Úsalo solo cuando el diálogo ya está o no estará, no para uno que llega con demora.
- Este es el único uso válido de `optional: true`: cerrar algo que aparece a veces. Nunca en un paso o un assert del caso.
- Si sospechas una ventana del sistema, `pantalla.py` lo confirma: no hay nodos de la app aunque la app tenga el foco. Anótalo en `quirks` y, si sale del build de la app, en `huecos.md`.

## Permisos del sistema

```yaml
- launchApp:
    clearState: true
    permissions:
      all: deny               # el caso "sin permisos"
# o justo antes del paso que lo pide
- setPermissions:
    permissions: { location: allow }
```

En Android 13 o más, la app puede pedir el permiso de notificaciones al abrir: decídelo en `launchApp`.

## Formularios

```yaml
- tapOn: { id: email }
- inputText: ${EMAIL}
- tapOn: { id: password }
- inputText: ${PASSWORD}
- hideKeyboard                # el teclado puede tapar el botón
- tapOn:
    id: submit
    enabled: true             # espera a que la validación lo habilite
```

Para el caso de error, el mensaje del campo también se encuentra con `text:`, porque Maestro compara el error del campo.

## Esperar a algo concreto

Maestro no tiene espera fija: `wait`, `sleep` y `waitFor` no existen (`Invalid Command` en 2.11.0). Se espera a que aparezca algo que prueba que el momento llegó, no a que pase el tiempo:

```yaml
- tapOn: { id: enviar }
- extendedWaitUntil:
    visible: { id: lista_pedidos }   # lo que solo existe con la carga terminada, no el shimmer
    timeout: 20000
```

- Un `visible` que no aparece espera hasta el `timeout` (medido: 19.2 s con `timeout: 20000`) y sale en cuanto aparece. Pon el tiempo del peor caso, no uno corto. Si el tiempo varía mucho, el problema puede ser el backend de QA: anótalo en `quirks`.
- `notVisible` sale **enseguida si el elemento no está**, también si todavía no apareció. «Espera a que se vaya el spinner» pasa en el acto cuando el spinner aún no se mostró. Espera mejor lo que aparece cuando termina (el `visible` del resultado).
- No uses `extendedWaitUntil` de un texto que no existe con `optional: true` para «esperar N segundos»: no espera el `timeout` (medido en un emulador: `timeout: 3000` terminó a los 1.6 s y `10000`, a los 4.8 s) y el reporte lo marca como advertencia.
- Si el caso exige dejar pasar un tiempo sin que cambie nada en pantalla (un token que vence), la app tiene que mostrar el resultado: va a `huecos.md` (un `testTag` o un texto al vencer). Mientras, espera ese resultado con un `timeout` mayor que el vencimiento.
- Cada toque espera a que la pantalla se asiente, y con un spinner o una cuenta regresiva animándose tarda. `waitToSettleTimeoutMs` acota esa espera en ese toque, pero no la quita (ver «Estados que duran poco»).

## Estados que duran poco: spinner, cuenta regresiva

Maestro no los alcanza a ver de forma fiable. Después de cada toque espera a que la pantalla se asiente, así que el spinner que dispara el toque ya terminó, o está por terminar, cuando llega el siguiente assert. Y entre dos pasos pasan segundos, no un tiempo fijo: en la evidencia de uso, un `assertVisible` del spinner llegó tarde con 400, 1 500 y 2 000 ms de latencia, y con una cotización que vencía a los 5 s pasaron más de 5 s entre ver el aviso y tocar «Confirmar» (`waitToSettleTimeoutMs: 300` no alcanzó; con 10 s sí).

Qué hacer:

1. Verifica el **resultado**, no el estado de paso: el contenido que aparece, el error, el botón otra vez habilitado.
2. Si el estado tiene que verse, alárgalo en el build o el backend de pruebas (más latencia, un vencimiento de 10 s en vez de 5) para que dure bastante más que lo que tarda Maestro entre pasos. Dilo en `huecos.md`.
3. Un spinner que bloquea la pantalla se prueba mejor con un test de UI de la app, con el reloj controlado, que con un flow. Si hay que verlo, `adb shell input tap X Y` seguido de `pantalla.py` unos segundos después lo alcanza solo si el estado dura esos segundos.
4. El snackbar con acción es el caso límite: ver «Mensajes que duran poco».

## Animaciones y transiciones

```yaml
- tapOn: "Ver detalle"
- waitForAnimationToEnd:
    timeout: 5000
```

Úsalo cuando la jerarquía cambia en medio de una transición y el siguiente tap cae en la pantalla anterior.

## Bottom sheets y menús

Son otra ventana. Ábrelos, confirma que están con un assert de un texto propio, y ciérralos con `back` o con su botón.

```yaml
- longPressOn: ${maestro.copiedText}
- assertVisible: "Quitar de favoritos|Remove from favorites"
- tapOn: "Quitar de favoritos|Remove from favorites"
- back
```

## Carruseles automáticos

Un `HorizontalPager` que se mueve solo cambia de página mientras Maestro toca. Prefiere otro camino a la misma pantalla; si no hay, toca con `retryTapIfNoChange: true` y verifica la pantalla de destino después.

## Tabs

```yaml
- tapOn: "Favoritos|Favorites"
- assertVisible:
    text: "Favoritos|Favorites"
    selected: true            # si el tab expone selected
```

## Sin red, y cuando la red vuelve

```yaml
appId: com.empresa.app.qa
name: home_sin_red_reintentar
tags: [offline]
onFlowComplete:
  - setAirplaneMode: disabled   # se apaga aunque el flow falle
---
- setAirplaneMode: enabled
- launchApp: { clearState: true }
- extendedWaitUntil:
    visible: "Ocurrió un error.*"
    timeout: 20000
- setAirplaneMode: disabled
# El Wi-Fi tarda unos segundos en volver y el flow no lo puede ver.
# Si la app no escucha la conectividad, el primer Reintentar puede llegar antes que la red.
- retry:
    maxRetries: 2
    commands:
      - tapOn: "Reintentar"
      - extendedWaitUntil:
          visible: "<texto que solo existe con contenido>"
          timeout: 10000
```

- Confirma en el código si la app escucha la conectividad (`NetworkCallback`) o solo reintenta al tocar: cambia lo que hay que esperar.
- El `retry` aquí cubre el tiempo del Wi-Fi, no un bug: si Reintentar no funciona, fallan todos los intentos. Dilo en la entrega.
- Para saber que el contenido cargó, usa un texto que solo existe en ese estado (un título que no está en el shimmer ni en el error).

## Deep links

```yaml
- openLink: app://producto/123
- assertVisible: { id: producto_titulo }
```

Revisa que el deep link exista en el manifest o en `navDeepLink`, y si la pantalla necesita sesión.

## WebView

Depende de lo que exponga la página. Busca con `text:` lo que aparezca en la jerarquía; si no aparece nada, es un hueco: no lo resuelvas con coordenadas.

## OTP, captcha y biometría

No se automatizan. Pide al dev un usuario o un build de QA que los salte, o deja ese paso fuera del flow y dilo en la entrega.
