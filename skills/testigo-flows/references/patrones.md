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

## Esperar al backend

```yaml
- extendedWaitUntil:
    visible: { id: lista_pedidos }
    timeout: 20000
```

Sin esperas fijas. Si el tiempo varía mucho, el problema puede ser el backend de QA: anótalo en `quirks`.

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
