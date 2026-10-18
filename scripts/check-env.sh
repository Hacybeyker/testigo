#!/usr/bin/env bash
# Verifica que esta máquina tenga lo necesario para testigo. No instala ni cambia nada.
# Uso: ./scripts/check-env.sh
# Respaldo para quien compila el proyecto: con el CLI, `testigo setup` y `testigo doctor` revisan esto y más.

set -u

missing=0
warnings=0

ok()   { printf '  \342\234\223 %s\n' "$1"; }
fail() { printf '  \342\234\227 %s\n      -> %s\n' "$1" "$2"; missing=$((missing + 1)); }
warn() { printf '  ! %s\n      -> %s\n' "$1" "$2"; warnings=$((warnings + 1)); }

echo "Compilar y dry-run"

if command -v java >/dev/null 2>&1; then
  version=$(java -version 2>&1 | awk -F'"' '/version/ { print $2; exit }')
  major=${version%%.*}
  if [ "${major:-0}" -ge 17 ] 2>/dev/null; then
    ok "Java $version"
  else
    fail "Java $version: se necesita 17 o superior" "brew install --cask temurin@21"
  fi
else
  fail "Java no está instalado" "brew install --cask temurin@21"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  ok "JAVA_HOME=$JAVA_HOME"
else
  warn "JAVA_HOME no está definido" "agrega a ~/.zshrc: export JAVA_HOME=\"\$(/usr/libexec/java_home -v 21)\""
fi

echo
echo "Ejecutar flows"

if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
  ok "ANDROID_HOME=$ANDROID_HOME"
else
  fail "ANDROID_HOME no está definido o no existe" "agrega a ~/.zshrc: export ANDROID_HOME=\"\$HOME/Library/Android/sdk\""
fi

if command -v adb >/dev/null 2>&1; then
  ok "adb $(adb version 2>/dev/null | awk 'NR == 1 { print $NF }')"
else
  fail "adb no está en el PATH" "instala Platform-Tools en el SDK Manager y agrega \$ANDROID_HOME/platform-tools al PATH"
fi

aapt2=""
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
  latest=$(ls "$ANDROID_HOME/build-tools" 2>/dev/null | tail -n 1)
  [ -n "$latest" ] && [ -x "$ANDROID_HOME/build-tools/$latest/aapt2" ] && aapt2="$ANDROID_HOME/build-tools/$latest/aapt2"
fi
if [ -n "$aapt2" ]; then
  ok "aapt2 (build-tools $latest)"
else
  fail "aapt2 no encontrado en \$ANDROID_HOME/build-tools" "instala Build-Tools en el SDK Manager"
fi

if command -v emulator >/dev/null 2>&1; then
  avds=$(emulator -list-avds 2>/dev/null | grep -vc '^INFO' || true)
  if [ "${avds:-0}" -gt 0 ]; then
    ok "emulador con $avds AVD(s)"
  else
    warn "no hay emuladores creados" "crea uno en Device Manager con una imagen Google APIs"
  fi
else
  warn "el comando emulator no está en el PATH" "agrega \$ANDROID_HOME/emulator al PATH (no hace falta si usas un teléfono)"
fi

if command -v adb >/dev/null 2>&1; then
  devices=$(adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')
  if [ "$devices" -gt 0 ]; then
    ok "$devices dispositivo(s) conectado(s)"
  else
    warn "no hay dispositivos conectados" "inicia el emulador o conecta un teléfono con Depuración USB"
  fi
fi

if command -v maestro >/dev/null 2>&1; then
  maestro_version=$(MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_DISABLE_UPDATE_CHECK=true MAESTRO_API_URL=http://127.0.0.1:9 MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true maestro --version 2>/dev/null | tail -n 1)
  ok "Maestro $maestro_version"
else
  fail "Maestro no está instalado" "curl -fsSL \"https://get.maestro.mobile.dev\" | bash"
fi

# MAESTRO_DISABLE_UPDATE_CHECK solo oculta el aviso: sin MAESTRO_API_URL local,
# Maestro igual consulta api.copilot.mobile.dev al iniciar (comprobado con LuLu).
absent=""
for name in MAESTRO_CLI_NO_ANALYTICS MAESTRO_DISABLE_UPDATE_CHECK MAESTRO_API_URL MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED; do
  [ -n "${!name:-}" ] || absent="$absent $name"
done
if [ -n "$absent" ]; then
  warn "faltan variables local-only de Maestro:$absent" "agrega el bloque de variables de la guía a ~/.zshrc"
else
  case "$MAESTRO_API_URL" in
    http://127.0.0.1:*|http://localhost:*) ok "variables local-only de Maestro (MAESTRO_API_URL=$MAESTRO_API_URL)" ;;
    *) warn "MAESTRO_API_URL=$MAESTRO_API_URL apunta fuera de esta máquina" "usa: export MAESTRO_API_URL=http://127.0.0.1:9" ;;
  esac
fi

if [ -n "${MAESTRO_CLI_AI_KEY:-}" ]; then
  warn "MAESTRO_CLI_AI_KEY está definida: los comandos de IA de Maestro envían capturas fuera" "quítala de ~/.zshrc y ejecuta: unset MAESTRO_CLI_AI_KEY"
fi

echo
if [ "$missing" -eq 0 ]; then
  echo "Listo: no falta nada ($warnings aviso(s))."
else
  echo "Faltan $missing cosa(s) y hay $warnings aviso(s)."
fi
[ "$missing" -eq 0 ]
