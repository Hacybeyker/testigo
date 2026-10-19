# Leer un proyecto Android para sacar selectores

El objetivo es saber, para cada pantalla del caso, **cómo la ve Maestro**: qué ids, textos y content descriptions expone, y cómo se llega a ella. Lee solo lo que el caso necesita.

## 1. Módulos y variante

- `settings.gradle(.kts)`: la lista de módulos (`include(":feature:login")`). En proyectos grandes, es el índice.
- El `build.gradle(.kts)` del módulo `com.android.application`: `namespace`, `applicationId`, `buildTypes` (`applicationIdSuffix`), `productFlavors` y si usa `compose = true`, `viewBinding` o `dataBinding`.
- La variante que se prueba sale del artefacto de `testigo.yaml` (`app-qa.apk` → build type `qa`). Sus recursos ganan sobre los de `main`: `src/<flavor>/res`, `src/<buildType>/res`.
- El `appId` del flow es el `applicationId` con su sufijo (`com.empresa.app` + `.qa`). Los `resource-id` llevan otro prefijo, pero con `id: nombre` no importa.

## 2. Llegar a la pantalla del caso

Busca desde lo que el dev nombra, no recorras todo. Excluye siempre `build/` y `.gradle/`: en un proyecto grande suelen ser la mayor parte de los archivos, y nada de ahí sirve.

```sh
# Por texto visible: primero en strings, después quién usa ese string
grep -rn --exclude-dir=build --exclude-dir=.gradle --include=strings.xml '>Mis pedidos<' .
grep -rn --exclude-dir=build --exclude-dir=.gradle 'R.string.orders_title\|@string/orders_title' --include='*.kt' --include='*.java' --include='*.xml' .

# Por ruta de navegación Compose o destino XML
grep -rn --exclude-dir=build --exclude-dir=.gradle 'composable(\|navigation(' --include='*.kt' .
grep -rln --exclude-dir=build --exclude-dir=.gradle '<navigation' --include='*.xml' .

# Por nombre de pantalla
grep -rln --exclude-dir=build --exclude-dir=.gradle 'class .*OrdersFragment\|fun OrdersScreen' --include='*.kt' .
```

## 3. Views XML

- **Layouts** en `res/layout*/`: `android:id="@+id/x"` → `id: x`. `android:text`, `android:hint` y `android:contentDescription` → `text:`. Resuelve `@string/...` con el `strings.xml` del idioma del dispositivo.
- `tools:text` no existe en la app: no lo uses.
- **`<include>` y `<merge>`:** los ids del layout incluido aparecen en la pantalla; un `android:id` en el `<include>` reemplaza al de la raíz incluida.
- **ViewBinding y DataBinding:** los ids son los mismos del XML. Con DataBinding, el texto puede venir de una expresión (`android:text="@{vm.title}"`): es dato, no texto fijo.
- **Textos puestos en código:** `binding.title.text = ...` o `setText(R.string.x)` en el Fragment o Activity.
- **RecyclerView:** el id del item se repite en cada fila. Selecciona la fila por su contenido (`containsDescendants`) o con `childOf`, o con `index` si el orden es fijo.
- **Vistas propias:** revisa la clase; un `contentDescription` asignado en código también cuenta.
- **Diálogos y bottom sheets:** `DialogFragment` y `BottomSheetDialogFragment` son otra ventana, pero Maestro los ve. `AlertDialog` usa ids del sistema: `android:id/button1` es el botón positivo, así que selecciona por texto.

## 4. Jetpack Compose

- **`testTag`** solo llega a Maestro si un ancestro tiene `Modifier.semantics { testTagsAsResourceId = true }`. Búscalo:

  ```sh
  grep -rn --exclude-dir=build --exclude-dir=.gradle 'testTagsAsResourceId' --include='*.kt' .
  grep -rn --exclude-dir=build --exclude-dir=.gradle 'testTag(' --include='*.kt' .
  ```

  Sin `testTagsAsResourceId`, los `testTag` no sirven: no hay ids, y el flow va por texto. Anótalo en los huecos.
- **Ventanas aparte:** `Dialog`, `ModalBottomSheet`, `Popup` y `DropdownMenu` crean otra composición. El `testTagsAsResourceId` de la pantalla puede no alcanzarlas: confírmalo en la jerarquía.
- **Textos:** `Text(text = stringResource(R.string.x))` → el texto de `strings.xml`. `Text(text = item.name)` → dato que cambia.
- **Content description:** `Icon(..., contentDescription = stringResource(...))` sirve como `text:`. Con `contentDescription = null` no hay nada que seleccionar.
- **Fusión de semántica:** `Button`, `clickable`, `NavigationBarItem`, `ListItem` y `Card(onClick)` fusionan los textos de sus hijos en un solo nodo. Selecciona por el texto del hijo: el tap cae en el contenedor clickable.
- **Estado:** `Modifier.toggleable`, `selectable`, `Checkbox`, `Switch` y `RadioButton` exponen `checked` o `selected`, que Maestro puede comparar. Un ícono que cambia sin semántica no expone nada.
- **Listas perezosas** (`LazyColumn`, `LazyRow`, `LazyVerticalGrid`, `HorizontalPager`): solo existen los ítems visibles. Usa `scrollUntilVisible` antes de buscar algo de más abajo.
- **Navegación:** `NavHost` + `composable("ruta")`, o rutas tipadas con `@Serializable`. Los deep links están en `navDeepLink { uriPattern = ... }`.
- **Interoperabilidad:** un `ComposeView` dentro de un Fragment o un `AndroidView` dentro de Compose mezclan los dos mundos. Confírmalos siempre en el dispositivo.

## 5. Navegación y deep links

- **Navigation XML** (`res/navigation/*.xml`): `<fragment android:id>` destinos, `<action>` y `<deepLink app:uri="...">`.
- **Manifest:** `<intent-filter>` con `<data android:scheme ... android:host ...>` en cada Activity. En multi-módulo, cada módulo trae su manifest y se fusionan.
- Un deep link deja empezar un flow directo en la pantalla, sin pasar por las anteriores: úsalo cuando exista y la pantalla no dependa de una sesión que el link no crea.

## 6. Idiomas

- Lista `res/values*/strings.xml` de los módulos del caso. El idioma del dispositivo decide qué texto ve Maestro.
- Si el dispositivo de pruebas tiene un idioma fijo, anótalo en `app.locale` del mapa.
- Si no, y el elemento no tiene id, usa alternancia con los textos de cada idioma: `"Favoritos|Favorites"`.
- Los placeholders (`%1$s`, `%d`) son datos: cámbialos por `.*` en el patrón.

## 7. Qué guardar en el mapa

Por cada elemento que el caso toca: el selector recomendado, una alternativa si existe, el origen (archivo y línea aproximada) y si está confirmado en el dispositivo. Lo que no tenga selector estable va a los huecos con el cambio sugerido.
