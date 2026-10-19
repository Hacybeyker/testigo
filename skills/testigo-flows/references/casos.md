# Catálogo de casos para planear

Úsalo en la fase 2. Para cada pantalla o feature, recorre la lista y quédate con lo que aplica y el dev considera importante. No hace falta un flow por cada línea: varios casos chicos caben en un flow si comparten el camino, siempre que el flow siga probando una sola cosa.

## Camino feliz

- El caso principal de la pantalla, de punta a punta, con el resultado visible al final.
- Llegar por cada entrada que exista: navegación normal, deep link, notificación (si se puede abrir con `openLink`).

## Datos y estados de la pantalla

- **Vacío:** lista sin elementos, primera vez sin datos.
- **Mucho:** listas largas (scroll hasta el final), textos largos, números grandes.
- **Error del backend:** el mensaje de error y el botón de reintentar.
- **Sin red:** modo avión antes de abrir la pantalla.
- **Carga lenta:** que el flow espere el contenido y no el shimmer.

## Formularios

- Campos vacíos y obligatorios.
- Formato inválido (correo, teléfono, documento).
- Límites: mínimo y máximo de caracteres.
- Botón deshabilitado hasta completar.
- Mensaje de error del backend (dato rechazado, ya existe).
- Teclado: que no tape el botón y que `Enter` haga lo esperado.

## Navegación

- `back` en cada paso: vuelve a donde debe y no pierde lo cargado.
- Tabs: cambiar y volver conserva el estado.
- Doble tap en un botón que navega o envía: no duplica la acción.

## Estado y sesión

- Primera vez (`clearState: true`) frente a usuario recurrente (sin `clearState`).
- Sesión expirada o cierre de sesión a mitad del flujo.
- Cambios que deben persistir: salir con `stopApp`, volver con `launchApp` y verificar.

## Permisos

- Concedido, denegado y "denegado para siempre", si la pantalla los pide.

## Acciones con efecto

- Favoritos, carrito, likes: agregar y quitar, y verlo en todas las pantallas que lo muestran.
- Compartir o abrir un link externo: verifica hasta el punto en que la app entrega el control a otra app.

## Lo que no va en un flow

- OTP, captcha, biometría: necesitan un usuario o build de QA (ver `patrones.md`).
- Datos que se consumen sin forma de restaurarlos: avisa al dev antes de escribirlo.
- Lo visual (colores, tamaños, alineación): Maestro no lo compara de forma estable.
