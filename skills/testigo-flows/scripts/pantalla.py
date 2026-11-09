#!/usr/bin/env python3
"""Lo que un flow puede seleccionar en la pantalla actual, en pocas líneas.

Lee el árbol de accesibilidad con `adb shell uiautomator dump`, el mismo que usa Maestro en Android,
sin instalar el driver de Maestro. Deja solo los nodos de la app que tienen texto, content description,
id, clic o estado.

`uiautomator dump` solo lista lo que está en pantalla en ese momento: un ítem de una lista perezosa que no se ha mostrado, o
algo que se compone al hacer scroll, no aparece. Que no salga aquí no prueba que la app no lo tenga.

Uso: python3 -I pantalla.py <package> [--sin-texto] [--serial ID]
  --sin-texto  cambia cada texto por su largo: para apps con datos sensibles en pantalla.
"""
import subprocess
import sys
import xml.etree.ElementTree as ET

args = sys.argv[1:]
if not args or args[0].startswith("-"):
    sys.exit(__doc__)
package = args[0]
mask = "--sin-texto" in args
adb = ["adb"]
if "--serial" in args:
    adb += ["-s", args[args.index("--serial") + 1]]

remote = "/sdcard/testigo-pantalla.xml"


def adb_run(*command):
    return subprocess.run(adb + list(command), capture_output=True, text=True)


# Un volcado que falla no escribe nada, y el `cat` siguiente leería el de una vez anterior como si fuera de ahora:
# se borra antes del dump y se comprueba su salida antes de leer nada.
adb_run("shell", "rm", "-f", remote)
dumped = adb_run("shell", "uiautomator", "dump", remote)
message = (dumped.stdout + dumped.stderr).strip()
if dumped.returncode != 0 or "dumped to" not in message:
    adb_run("shell", "rm", "-f", remote)
    sys.exit(
        f"uiautomator dump falló ({message or 'sin salida'}). No leí ningún volcado anterior. "
        "¿Está el dispositivo conectado, con la pantalla encendida y desbloqueado?"
    )
dump = adb_run("shell", "cat", remote).stdout
adb_run("shell", "rm", "-f", remote)

start = dump.find("<?xml")
if start < 0:
    sys.exit("El volcado de uiautomator no trae la jerarquía. ¿Está el dispositivo conectado y desbloqueado?")
root = ET.fromstring(dump[start:])


def shown(value):
    if not value:
        return "-"
    return f"<{len(value)} caracteres>" if mask else value


lines = []
for node in root.iter("node"):
    if node.get("package") != package:
        continue
    text = node.get("text") or ""
    desc = node.get("content-desc") or ""
    rid = node.get("resource-id") or ""
    flags = [f for f in ("clickable", "long-clickable", "checkable", "checked", "selected", "focused") if node.get(f) == "true"]
    if node.get("enabled") == "false":
        flags.append("disabled")
    if not (text or desc or rid or flags):
        continue
    if rid.startswith("android:id/") and not (text or desc or flags):
        continue  # contenedores del sistema (android:id/content): no aportan selectores
    bounds = node.get("bounds", "")
    hidden = bounds == "[0,0][0,0]"
    rid_short = rid.rsplit("/", 1)[-1] if rid else "-"
    lines.append(
        f"text={shown(text)} | desc={shown(desc)} | id={rid_short} | {','.join(flags) or '-'}"
        + (" | fusionado en el padre" if hidden else f" | {bounds}")
    )

if not lines:
    sys.exit(f"No hay nodos de {package} en la pantalla: ¿está la app al frente?")
print(f"{len(lines)} nodos de {package}")
print("\n".join(lines))
