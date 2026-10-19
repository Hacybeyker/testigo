#!/usr/bin/env bash
# Ejecuta Maestro con el mismo entorno local-only que usa testigo:
# sin analytics, sin consulta de versión y sin las claves de IA ni de Maestro Cloud.
# Uso: maestro-local.sh hierarchy --compact
#      maestro-local.sh check-syntax flows/x/caso.yaml
set -euo pipefail

for var in $(env | sed -n 's/^\(MAESTRO_CLI_AI_[A-Za-z0-9_]*\)=.*/\1/p'); do
  unset "$var"
done
unset MAESTRO_CLOUD_API_KEY

export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_DISABLE_UPDATE_CHECK=true
export MAESTRO_API_URL=http://127.0.0.1:9
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

# El mismo orden de búsqueda que testigo: PATH, $MAESTRO_DIR/bin y ~/.maestro/bin.
bin=$(command -v maestro || true)
[ -z "$bin" ] && [ -n "${MAESTRO_DIR:-}" ] && [ -x "$MAESTRO_DIR/bin/maestro" ] && bin="$MAESTRO_DIR/bin/maestro"
[ -z "$bin" ] && [ -x "$HOME/.maestro/bin/maestro" ] && bin="$HOME/.maestro/bin/maestro"
if [ -z "$bin" ]; then
  echo "No encuentro maestro. Ejecuta testigo setup en la carpeta de la suite." >&2
  exit 2
fi

exec "$bin" "$@"
