# Huecos de testabilidad · <app>

Elementos que los flows no pueden seleccionar de forma estable, con el cambio de código que lo arregla. Commit revisado: `<commit>`.

| Pantalla | Elemento | Hoy se selecciona con | Problema | Cambio sugerido | Archivo |
|---|---|---|---|---|---|
| <pantalla> | <elemento> | <selector actual o "no se puede"> | <por qué es frágil> | <cambio concreto> | <ruta> |

## Cambios que arreglan varios a la vez

- <por ejemplo: activar testTagsAsResourceId una vez en la raíz>

## Convención de nombres sugerida

`<pantalla>_<elemento>`, en minúsculas y con guion bajo. Por ejemplo: `login_submit`, `home_section_trending`.
