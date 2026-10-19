#!/usr/bin/env python3
"""Lo que un flow puede seleccionar en la pantalla actual, en pocas líneas.

Lee el árbol de accesibilidad con `adb shell uiautomator dump`, el mismo que usa Maestro en Android,
sin instalar el driver de Maestro. Deja solo los nodos de la app que tienen texto, content description,
id, clic o estado.

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
subprocess.run(adb + ["shell", "uiautomator", "dump", remote], capture_output=True)
dump = subprocess.run(adb + ["shell", "cat", remote], capture_output=True, text=True).stdout
subprocess.run(adb + ["shell", "rm", "-f", remote], capture_output=True)

start = dump.find("<?xml")
if start < 0:
    sys.exit("No pude leer la pantalla con uiautomator dump. ¿Está el dispositivo conectado y desbloqueado?")
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
