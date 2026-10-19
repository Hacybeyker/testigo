#!/usr/bin/env python3
"""Lee app-map.yaml por partes, para no cargar el mapa entero.

Uso (desde la suite):
  python3 -I mapa.py                  índice: la app, cada pantalla con sus elementos y flows, subflows y huecos
  python3 -I mapa.py home movie       solo esas pantallas, completas
  python3 -I mapa.py --mapa app-map/auth.yaml login    otro archivo del mapa (proyectos por módulo)
Sin dependencias: corta el YAML por indentación, no lo interpreta.
"""
import re
import sys
from pathlib import Path

args = sys.argv[1:]
path = Path("app-map.yaml")
if "--mapa" in args:
    i = args.index("--mapa")
    path = Path(args[i + 1])
    del args[i : i + 2]
if not path.is_file():
    sys.exit(f"No encuentro {path}. Ejecuta el script desde la carpeta de la suite.")

lines = path.read_text(encoding="utf-8").splitlines()
TOP = re.compile(r"^[A-Za-z_][\w-]*:")
SCREEN = re.compile(r"^  ([\w-]+):\s*$")
ELEMENT = re.compile(r"^      ([\w-]+):\s*$")

# Bloques de primer nivel
blocks, current = {}, None
for line in lines:
    m = TOP.match(line)
    if m:
        current = m.group(0)[:-1]
        blocks[current] = [line]
    elif current:
        blocks[current].append(line)
    else:
        blocks.setdefault("_cabecera", []).append(line)

# Pantallas dentro de screens:
screens, name = {}, None
for line in blocks.get("screens", [])[1:]:
    m = SCREEN.match(line)
    if m:
        name = m.group(1)
        screens[name] = [line]
    elif name:
        screens[name].append(line)

if args:
    missing = [a for a in args if a not in screens]
    if missing:
        sys.exit(f"Pantallas que no están en {path}: {', '.join(missing)}. Hay: {', '.join(screens)}")
    for a in args:
        print("\n".join(screens[a]).rstrip())
    sys.exit(0)

for key in ("app", "privacy", "env", "modules"):
    if key in blocks:
        print("\n".join(blocks[key]).rstrip())
print(f"screens: {len(screens)}")
for s, body in screens.items():
    elements = sum(1 for l in body if ELEMENT.match(l))
    confirmed = sum(1 for l in body if l.strip().startswith("confirmed: true"))
    flows = 0
    in_flows = False
    for l in body:
        if l.strip() == "flows:":
            in_flows = True
            continue
        if in_flows:
            if l.strip().startswith("- "):
                flows += 1
            elif l.strip():
                in_flows = False
        inline = re.match(r"^\s*flows:\s*\[(.*)\]\s*(#.*)?$", l)
        if inline:
            flows = len([x for x in inline.group(1).split(",") if x.strip()])
    window = next((l.split(":", 1)[1].strip() for l in body if l.strip().startswith("window:")), "")
    print(f"  {s}: {elements} elementos ({confirmed} confirmados) · {flows} flows" + (f" · {window}" if window and window != "main" else ""))
if "subflows" in blocks:
    print("\n".join(blocks["subflows"]).rstrip())
gaps = sum(1 for l in blocks.get("gaps", []) if l.strip().startswith("- "))
print(f"gaps: {gaps}")
