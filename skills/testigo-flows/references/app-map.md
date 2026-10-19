# Esquema de app-map.yaml

El mapa es lo específico de cada app. Vive en la raíz de la suite, junto a `testigo.yaml`, y se versiona con ella. Guarda lo que la skill necesita para escribir flows sin releer el código cada vez.

## Claves

```yaml
version: 1
app:
  package: com.empresa.app.qa       # appId de los flows
  source: /ruta/al/repo/de/la/app   # código fuente
  commit: a1b2c3d                   # commit con el que se armó o actualizó
  variant: qa                       # build type y flavor que se prueban
  locales: [es, en]                 # idiomas que pueden estar en el dispositivo
  ids: none                         # views | testtags | partial | none: qué ids ve Maestro (ver sin-ids.md)
privacy: masked                     # code-only | masked | hierarchy (ver la regla 6 de SKILL.md)

env: [USUARIO, PASSWORD]            # nombres de variables de .env.testigo, nunca valores

modules:                            # solo en proyectos grandes: el mapa partido por módulo
  auth: app-map/auth.yaml

screens:
  <pantalla>:
    module: app
    source: ruta/al/Archivo.kt      # Fragment, Activity o Composable
    kind: compose                   # compose | views | mixed | webview
    reach: "tab Favoritos"          # cómo se llega: launchApp, tab, deep link, subflow
    window: main                    # main | dialog | bottom-sheet
    elements:
      <elemento>:
        selector: { text: "Favoritos|Favorites" }   # el recomendado, listo para el flow
        alt: { id: nav_favorites }                  # alternativa, si existe
        string: nav_favorites       # recurso de strings.xml del texto, si el selector es por texto
        dynamic: false              # true si el texto es un dato del backend
        confirmed: false            # true cuando se vio en la jerarquía del dispositivo
    flows: [flows/navegacion/tabs.yaml]
    quirks:
      - "Carrusel que se mueve solo cada 4 s"

subflows:
  abrir-pelicula: flows/subflows/abrir_pelicula.yaml

gaps:                               # también se detallan en huecos.md
  - screen: home
    element: poster
    problem: "Solo se encuentra por el título, que viene de la API"
    fix: "Modifier.testTag(\"movie_poster\") y testTagsAsResourceId en la raíz"
```

## Reglas

- **Un elemento por cosa que un flow toca**, no por cada nodo de la pantalla.
- **`selector`** es lo que se pega en el flow. Si cambia, cambia aquí primero.
- **`confirmed`** solo pasa a `true` con la jerarquía del dispositivo o una corrida real que lo use.
- **`dynamic: true`** obliga a usar `copyTextFrom` o un patrón, nunca el valor visto.
- **`string`** conecta el selector con su recurso: si el `git diff` toca ese string, los flows que lo usan se revalidan.
- **`flows`** en cada pantalla es la cobertura: si está vacío, la pantalla no tiene flow.
- **`commit`** se actualiza cada vez que se revisa el mapa contra el código.
- Con `modules`, `screens` del índice queda vacío y cada `app-map/<modulo>.yaml` tiene sus `screens`.
