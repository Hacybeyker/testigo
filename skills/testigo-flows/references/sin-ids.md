# Apps sin ids, o con pocos

Es lo común, no la excepción: muchas apps no se escribieron pensando en pruebas de UI. Pasa sobre todo en Compose sin `testTagsAsResourceId`, pero también en Views con ids genéricos o repetidos, y en builds que ofuscan los nombres de los recursos. La skill no se detiene por eso: escribe los flows con lo que hay, los deja marcados como frágiles y entrega la lista de cambios que los vuelven estables.

## 1. Detectarlo en la fase 1, por módulo

En un proyecto grande cada módulo puede estar distinto. Mide antes de escribir:

```sh
# Por módulo: ids en layouts, testTag en Compose y si algo los expone
for m in $(grep -E '^[[:space:]]*include' settings.gradle* | grep -oE "[\"']:[^\"']+[\"']" | tr -d "\"'" | tr ':' '/'); do
  d=".${m}"
  echo "$m  ids=$(grep -rho 'android:id="@+id/' "$d"/src 2>/dev/null | wc -l)" \
       "testTag=$(grep -rho 'testTag(' "$d"/src 2>/dev/null | wc -l)" \
       "asResourceId=$(grep -rl 'testTagsAsResourceId' "$d"/src 2>/dev/null | wc -l)"
done
```

Clasifica `app.ids` en el mapa, y por pantalla si hace falta:

| Valor | Cuándo | Selector base |
|---|---|---|
| `views` | Views XML con `android:id` en lo que se toca | `id` |
| `testtags` | Compose con `testTag` y `testTagsAsResourceId` activo | `id` |
| `partial` | Unas pantallas sí y otras no, o ids genéricos repetidos (`button`, `title`) | `id` donde sirva, texto en el resto |
| `none` | Nada que Maestro vea como id | Texto y content description |

Confírmalo en el dispositivo: en `maestro hierarchy --compact`, una columna de `resource-id` vacía o con nombres sin sentido (`a`, `b1`) confirma `none`. Nombres sin sentido indican que el build ofusca los recursos: pide un build de QA sin esa ofuscación antes de escribir flows.

## 2. Cómo seleccionar sin ids

En este orden:

1. **Content description** de íconos y botones: suele salir de `strings.xml` y cambia poco.
2. **Texto fijo** de `strings.xml`, como regex de cadena completa:
   - Fija el idioma del dispositivo de pruebas y anótalo en `app.locales`. Si no se puede fijar, usa alternancia con los idiomas posibles: `"Favoritos|Favorites"`.
   - Placeholders (`%1$s`, `%d`) → `.*`. Escapa `( ) . ? + * [ ] $ ^ |` cuando el texto los tenga.
   - Guarda en el mapa el nombre del string (`string: nav_favorites`): así un cambio en `strings.xml` dice qué flows revisar.
3. **Relativo a un ancla de texto fijo** para lo que es dato: `below`, `childOf` o `containsDescendants` respecto de un encabezado o una etiqueta.
4. **Datos que cambian:** `copyTextFrom` y reusar `${maestro.copiedText}`; o datos de QA conocidos que el dev pone en `.env.testigo` (`${PRODUCTO_QA}`), si el backend de QA los mantiene fijos.
5. **Estado** con `checked`, `selected` o `enabled`, solo si la semántica lo expone.
6. **`index`**, solo en listas de orden fijo.
7. **Nunca `point`.**

## 3. Reglas extra cuando todo va por texto

- **Confirma la pantalla antes de actuar:** un `assertVisible` o `extendedWaitUntil` de un texto propio de la pantalla. Un mismo texto («Guardar», «Continuar») puede estar en muchas pantallas.
- **Cuida los textos repetidos en la misma pantalla:** un encabezado igual al tab (por ejemplo «Ajustes» arriba y abajo) no sirve para confirmar la pantalla.
- **Marca la fragilidad en el mapa:** los elementos por texto llevan `string:`; los relativos y por dato llevan `dynamic: true`.
- **Al mantener:** si el `git diff` toca un `strings.xml`, revalida los flows que usan esos strings.

## 4. El reporte de huecos, priorizado

Ordena `huecos.md` por cuánto arregla cada cambio:

1. **Una línea que arregla todo Compose:** `Modifier.semantics { testTagsAsResourceId = true }` en la raíz de cada pantalla o en el `Scaffold` común, y en cada `Dialog`, `ModalBottomSheet` o `Popup` que lo necesite.
2. **Componentes compartidos:** un `testTag` o `android:id` en el componente del design system (botón, celda, póster) cubre todas las pantallas que lo usan.
3. **Pantalla por pantalla:** solo los elementos que los flows tocan, con la convención `<pantalla>_<elemento>`.
4. **Estado accesible:** `toggleable`, `selectable` o `stateDescription` donde el estado solo se ve en un ícono.

## 5. Cuando llegan los ids

Si el dev aplica cambios de `huecos.md`, la skill migra: pasa el `alt` con id a `selector` en el mapa, actualiza los flows que usaban texto, los revalida y saca el hueco del reporte.
