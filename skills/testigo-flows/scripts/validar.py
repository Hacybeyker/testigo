#!/usr/bin/env python3
"""Niveles 1 y 2 de la validación, sin dispositivo, con una salida corta.

1. Forma y política: testigo run --dry-run --flows <flow>.
2. Sintaxis: maestro check-syntax del flow y de cada subflow que llama (runFlow), con el entorno local-only.

Uso (desde la suite): python3 -I validar.py flows/x/caso.yaml [otro.yaml ...]
Sale con 0 si todo pasa. Si algo falla, muestra solo la parte de la salida que explica el fallo.
"""
import re
import subprocess
import sys
from pathlib import Path

flows = [Path(a) for a in sys.argv[1:]]
if not flows:
    sys.exit(__doc__)
maestro_local = Path(__file__).with_name("maestro-local.sh")
RUNFLOW = re.compile(r"""(?:runFlow:\s*|^\s*file:\s*)["']?([^"'\s#]+\.ya?ml)""", re.M)


def referenced(flow, seen):
    """El flow y sus subflows (runFlow), recursivo, sin repetir."""
    flow = flow.resolve()
    if flow in seen or not flow.is_file():
        return
    seen.append(flow)
    for target in RUNFLOW.findall(flow.read_text(encoding="utf-8")):
        if "${" not in target:
            referenced(flow.parent / target, seen)


failed = False
cmd = ["testigo", "run", "--dry-run"]
for f in flows:
    cmd += ["--flows", str(f)]
dry = subprocess.run(cmd, capture_output=True, text=True)
out = dry.stdout + dry.stderr
summary = next((l.strip() for l in out.splitlines() if "Local-only" in l), "")
if dry.returncode == 0:
    print(f"1. forma y política: OK · {summary}")
else:
    failed = True
    print(f"1. forma y política: FALLA (código {dry.returncode})")
    # Desde la sección de problemas hasta el final; sin el plan, que no explica el fallo.
    lines = out.splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith(("Política local-only", "Formato de Maestro"))), None)
    if start is None:
        start = max(0, len(lines) - 40)
    print("\n".join(lines[start:]).rstrip())

files = []
for f in flows:
    referenced(f, files)

# Asserts con selectores relativos: comparan un solo eje y pueden calzar con otra fila.
ASSERT = re.compile(r"^(\s*)-\s*(assertVisible|assertNotVisible|extendedWaitUntil)\s*:(.*)$")
RELATIVE = re.compile(r"\b(leftOf|rightOf|above|below)\s*:")
warnings = []
for f in files:
    lines = Path(f).read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        m = ASSERT.match(line)
        if not m:
            continue
        indent, block = len(m.group(1)), [m.group(3)]
        for nxt in lines[i + 1 :]:
            if nxt.strip() and len(nxt) - len(nxt.lstrip()) <= indent:
                break
            block.append(nxt)
        text = "\n".join(block)
        rel = RELATIVE.search(text)
        if rel and "childOf" not in text:
            warnings.append(f"{Path(f).name}:{i + 1} {m.group(2)} con {rel.group(1)}")
for w in warnings:
    print(f"   aviso: {w}. Compara un solo eje y calza con elementos de otras filas: un assert así puede pasar"
          " sin probar nada. Usa childOf con la fila, o verifica antes el estado contrario.")
for f in files:
    check = subprocess.run(["bash", str(maestro_local), "check-syntax", str(f)], capture_output=True, text=True)
    rel = Path(f).relative_to(Path.cwd()) if Path(f).is_relative_to(Path.cwd()) else f
    if check.returncode == 0:
        print(f"2. sintaxis OK: {rel}")
    else:
        failed = True
        msg = [l for l in (check.stdout + check.stderr).splitlines() if l.strip() and "JAVA_TOOL_OPTIONS" not in l]
        print(f"2. sintaxis FALLA: {rel}")
        print("\n".join(msg[:15]))

sys.exit(1 if failed else 0)
