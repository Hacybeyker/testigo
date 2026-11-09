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
            warnings.append(
                f"{Path(f).name}:{i + 1} {m.group(2)} con {rel.group(1)}. Compara un solo eje y calza con elementos de otras filas: "
                "un assert así puede pasar sin probar nada. Usa childOf con la fila, o verifica antes el estado contrario."
            )

# takeScreenshot solo acepta un nombre o una subcarpeta dentro de la carpeta de la corrida.
TAKE_SCREENSHOT = re.compile(r"^(\s*)-\s+takeScreenshot\s*:(.*)$")
PATH_KEY = re.compile(r"""\bpath\s*:\s*(?:"([^"]*)"|'([^']*)'|([^\s,}#]+))""")


def screenshot_targets(text):
    """(línea, ruta) de cada takeScreenshot, en sus tres formas: `- takeScreenshot: nombre`, `- takeScreenshot: {label: a, path: b}`
    y el bloque con `path:` en las líneas siguientes. Las líneas comentadas no cuentan."""
    lines = text.splitlines()
    found = []
    for index, line in enumerate(lines):
        match = TAKE_SCREENSHOT.match(line)
        if not match:
            continue
        rest = re.sub(r"\s+#.*$", "", match.group(2)).strip()
        if rest.startswith("{"):
            key = PATH_KEY.search(rest)
            if key:
                found.append((index + 1, next(g for g in key.groups() if g is not None)))
        elif rest:
            found.append((index + 1, rest.strip("\"'")))
        else:
            for offset in range(index + 1, len(lines)):
                follow = lines[offset]
                if follow.strip() and len(follow) - len(follow.lstrip()) <= len(match.group(1)):
                    break  # el siguiente paso: terminó el bloque
                if follow.lstrip().startswith("#"):
                    continue
                key = re.match(r"\s*" + PATH_KEY.pattern, follow)
                if key:
                    found.append((offset + 1, next(g for g in key.groups() if g is not None)))
    return found


for f in files:
    for number, target in screenshot_targets(Path(f).read_text(encoding="utf-8")):
        target = target.strip()
        if target.startswith(("/", "\\")) or re.match(r"[A-Za-z]:", target) or ".." in re.split(r"[/\\]", target):
            failed = True
            print(f"1. captura FALLA: {Path(f).name}:{number} takeScreenshot con ruta fuera de la carpeta de la corrida ({target}). "
                  "Maestro la rechaza al correr: usa un nombre (paso1) o una subcarpeta (antes/paso1).")


def top_level_steps(text):
    """Los comandos de primer nivel del flow (después de `---`), cada uno con su línea y su texto."""
    lines = text.splitlines()
    start = next((i + 1 for i, line in enumerate(lines) if line.strip() == "---"), 0)
    steps = []
    for number in range(start, len(lines)):
        line = lines[number]
        if re.match(r"-(\s|$)", line):
            steps.append([number + 1, [line]])
        elif steps and (not line.strip() or line[0] in " \t"):
            steps[-1][1].append(line)
    return [(number, "\n".join(body)) for number, body in steps]


# Toques frágiles: un scroll rápido se toma como toque, y un toque justo después de un scroll, pegado al borde, no llega.
DEFAULT_SPEED = 40
TAPS = ("tapOn", "longPressOn", "doubleTapOn")
for f in files:
    steps = top_level_steps(Path(f).read_text(encoding="utf-8"))
    names = [(re.match(r"-\s*(\w+)", body) or [None, ""])[1] for _, body in steps]
    for index, (number, body) in enumerate(steps):
        if names[index] == "scrollUntilVisible":
            speed = re.search(r"speed:\s*(\d+)", body)
            if speed and int(speed.group(1)) > DEFAULT_SPEED:
                warnings.append(
                    f"{Path(f).name}:{number} scrollUntilVisible con speed {speed.group(1)}, sobre el 40 por defecto: un scroll rápido "
                    "puede tomarse como un toque y abrir un ítem de la lista. Bájalo, o quita el speed."
                )
        if names[index] in TAPS and index > 0 and names[index - 1] == "scrollUntilVisible":
            if not re.search(r"centerElement:\s*true", steps[index - 1][1]):
                warnings.append(
                    f"{Path(f).name}:{number} {names[index]} justo después de scrollUntilVisible, sin centerElement: true ni "
                    "waitForAnimationToEnd entre los dos: un toque pegado al borde inferior puede no llegar a la app sin dar error."
                )

for w in warnings:
    print(f"   aviso: {w}")
for f in files:
    check = subprocess.run(["bash", str(maestro_local), "check-syntax", str(f)], capture_output=True, text=True)
    try:
        rel = Path(f).relative_to(Path.cwd())  # Path.is_relative_to es de Python 3.9
    except ValueError:
        rel = f
    if check.returncode == 0:
        print(f"2. sintaxis OK: {rel}")
    else:
        failed = True
        msg = [l for l in (check.stdout + check.stderr).splitlines() if l.strip() and "JAVA_TOOL_OPTIONS" not in l]
        print(f"2. sintaxis FALLA: {rel}")
        print("\n".join(msg[:15]))

sys.exit(1 if failed else 0)
