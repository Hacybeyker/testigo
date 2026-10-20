#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Instalador de testigo (https://github.com/Hacybeyker/testigo) para macOS y Linux.
#
#   curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | bash
#   curl -fsSL https://raw.githubusercontent.com/Hacybeyker/testigo/HEAD/install.sh | TESTIGO_VERSION=0.2.0 bash
#
# Baja el release de GitHub, verifica su SHA-256 y lo deja en ~/.testigo/cli/<versión>, con ~/.testigo/cli/current
# apuntando a la versión activa y ~/.local/bin/testigo apuntando a current. No usa sudo, no edita el perfil de la
# terminal y no envía nada: Java, Maestro, el Android SDK y el PATH los configura después `testigo setup`.
#
# Está escrito en POSIX sh (corre igual con el bash 3.2 de macOS y con dash) y todo vive en funciones: la última
# línea llama a main, así que si la descarga de este script se corta no se ejecuta nada a medias.

set -eu

TESTIGO_REPO="Hacybeyker/testigo"
TESTIGO_RELEASES="https://github.com/$TESTIGO_REPO/releases"
VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'

# Lo que se va resolviendo. Variables globales con prefijo: POSIX sh no tiene `local`.
work=""
sha_cmd=""
platform_os=""
platform_arch=""

# --- Mensajes ---------------------------------------------------------------------------------------------

say() { printf '%s\n' "$*"; }
warn() { printf '! %s\n' "$*" >&2; }
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
# Entorno o uso: código 2, como testigo.
fail_env() { printf 'Error: %s\n' "$*" >&2; exit 2; }

# /Users/ana/.testigo/cli → ~/.testigo/cli
# shellcheck disable=SC2088 # la ~ es para mostrar, no para expandir
tilde() {
    case "$1" in
        "$HOME"/*) printf '~/%s' "${1#"$HOME"/}" ;;
        *) printf '%s' "$1" ;;
    esac
}

usage() {
    cat <<EOF
Instalador de testigo para macOS y Linux.

Uso:
  curl -fsSL https://raw.githubusercontent.com/$TESTIGO_REPO/HEAD/install.sh | bash
  curl -fsSL https://raw.githubusercontent.com/$TESTIGO_REPO/HEAD/install.sh | bash -s -- --uninstall
  sh install.sh [--uninstall | --help]

Variables:
  TESTIGO_VERSION   versión a instalar, por ejemplo 0.2.0 (por defecto, la última publicada)
  TESTIGO_HOME      carpeta de testigo (por defecto, ~/.testigo); las versiones van en cli/
  TESTIGO_BIN_DIR   dónde va el enlace testigo (por defecto, ~/.local/bin)
  TESTIGO_ARCHIVE   instala un .zip o .tar.gz local, con su .sha256 al lado, sin descargar nada

Qué hace:
  - Descarga de $TESTIGO_RELEASES el archivo de la versión y su .sha256,
    por HTTPS, y verifica el SHA-256 antes de instalar.
  - Instala en \$TESTIGO_HOME/cli/<versión>, apunta cli/current a ella y crea el enlace en \$TESTIGO_BIN_DIR.
  - No usa sudo, no edita el perfil de la terminal y no envía datos a ningún lado.
  - Después, \`testigo setup\` revisa Java, Maestro, el Android SDK y el PATH, con tu confirmación.

--uninstall quita \$TESTIGO_HOME/cli y el enlace. No toca Java, Maestro, el SDK ni tus suites.
EOF
}

# --- Plataforma y herramientas ------------------------------------------------------------------------

has() { command -v "$1" >/dev/null 2>&1; }

# Cómo instalar un comando que falta, según el gestor de paquetes que haya. Como root, sin sudo.
install_hint() {
    if [ "$(id -u 2>/dev/null || echo 1)" = 0 ]; then hint_sudo=""; else hint_sudo="sudo "; fi
    if [ "$platform_os" = macos ]; then
        if has brew; then printf 'brew install %s' "$1"; else printf 'Homebrew (https://brew.sh) y luego brew install %s' "$1"; fi
    elif has apt-get; then printf '%sapt-get install -y %s' "$hint_sudo" "$1"
    elif has dnf; then printf '%sdnf install -y %s' "$hint_sudo" "$1"
    elif has yum; then printf '%syum install -y %s' "$hint_sudo" "$1"
    elif has apk; then printf '%sapk add %s' "$hint_sudo" "$1"
    elif has pacman; then printf '%spacman -S %s' "$hint_sudo" "$1"
    else printf 'el gestor de paquetes de tu sistema (%s)' "$1"
    fi
}

detect_platform() {
    case "$(uname -s)" in
        Darwin) platform_os=macos ;;
        Linux) platform_os=linux ;;
        MINGW* | MSYS* | CYGWIN*)
            fail_env "este instalador es para macOS y Linux. En Windows, usa WSL, o el zip del release con bin\\testigo.bat (sin probar todavía)." ;;
        *) fail_env "sistema no soportado: $(uname -s). testigo funciona en macOS y Linux." ;;
    esac
    case "$(uname -m)" in
        arm64 | aarch64) platform_arch=arm64 ;;
        x86_64 | amd64) platform_arch=x86_64 ;;
        *)
            platform_arch=$(uname -m)
            warn "arquitectura sin probar: $platform_arch. testigo funciona con un Java 17 o más que instales tú; testigo setup no puede descargarlo para esta arquitectura." ;;
    esac
    if [ -f /etc/alpine-release ]; then
        warn "Alpine usa musl: testigo setup no puede descargar Java aquí. Instálalo con: apk add openjdk17"
    fi
}

# El SHA-256: sha256sum en Linux, shasum en macOS.
detect_sha() {
    if has sha256sum; then sha_cmd="sha256sum"
    elif has shasum; then sha_cmd="shasum -a 256"
    else fail_env "falta sha256sum o shasum para verificar la descarga. Instala uno con: $(install_hint coreutils)"
    fi
}

sha256_of() {
    # shellcheck disable=SC2086 # sha_cmd lleva el comando y su opción
    $sha_cmd "$1" | awk '{print tolower($1)}'
}

# --- Versión y descarga ------------------------------------------------------------------------------

valid_version() { printf '%s' "$1" | grep -Eq "$VERSION_PATTERN"; }

# La última versión, desde la redirección de releases/latest: sin la API de GitHub ni su límite de consultas.
latest_version() {
    latest_url=$(curl --proto '=https' --tlsv1.2 -fsSLI -o /dev/null -w '%{url_effective}' "$TESTIGO_RELEASES/latest") ||
        fail "no pude consultar la última versión en $TESTIGO_RELEASES. Revisa la conexión o el proxy (HTTPS_PROXY), o indica TESTIGO_VERSION."
    case "$latest_url" in
        */releases/tag/v*) printf '%s' "${latest_url##*/releases/tag/v}" ;;
        *) fail "no encontré un release publicado en $TESTIGO_RELEASES." ;;
    esac
}

# Descarga $1 en $2. Con terminal y sin un tercer argumento `quiet`, con barra de progreso.
download() {
    if [ -t 2 ] && [ "${3:-}" != quiet ]; then download_progress="--progress-bar"; else download_progress="-sS"; fi
    curl --proto '=https' --tlsv1.2 -fL --retry 3 --retry-delay 2 "$download_progress" -o "$2" "$1"
}

# true si existe $1 (un archivo pequeño del release): para saber qué formatos publicó esa versión.
remote_exists() {
    curl --proto '=https' --tlsv1.2 -fsSL -o /dev/null "$1" 2>/dev/null
}

# Compara el SHA-256 de $1 con el de su archivo .sha256 ($2), con el formato `hash  nombre`.
verify() {
    verify_expected=$(awk 'NR == 1 {print tolower($1)}' "$2")
    verify_actual=$(sha256_of "$1")
    if [ -z "$verify_expected" ] || [ "$verify_expected" != "$verify_actual" ]; then
        fail "el SHA-256 de $(basename "$1") no coincide con el publicado. No instalé nada.
  esperado: ${verify_expected:-(vacío)}
  obtenido: $verify_actual"
    fi
}

extract() {
    case "$1" in
        *.tar.gz) tar -xzf "$1" -C "$2" ;;
        *.zip) unzip -q "$1" -d "$2" ;;
    esac
}

# --- Instalación -------------------------------------------------------------------------------------

cleanup() {
    if [ -n "$work" ] && [ -d "$work" ]; then rm -rf "$work"; fi
}

# Elige el archivo de $1 (versión) y lo deja en $work, verificado. Imprime su ruta.
fetch_release() {
    fetch_base="$TESTIGO_RELEASES/download/v$1"
    fetch_name=""
    # .tar.gz si la versión lo publica y hay tar; si no, el zip (0.2.0 y anteriores solo tienen zip).
    if remote_exists "$fetch_base/testigo-$1.tar.gz.sha256"; then
        if has tar; then
            fetch_name="testigo-$1.tar.gz"
        elif has unzip && remote_exists "$fetch_base/testigo-$1.zip.sha256"; then
            fetch_name="testigo-$1.zip"
        else
            fail_env "falta tar para descomprimir testigo $1. Instálalo con: $(install_hint tar)"
        fi
    elif remote_exists "$fetch_base/testigo-$1.zip.sha256"; then
        has unzip || fail_env "testigo $1 se publica solo en .zip y falta unzip. Instálalo con: $(install_hint unzip)"
        fetch_name="testigo-$1.zip"
    else
        fail "no existe la versión $1 en $TESTIGO_RELEASES (o no trae su .sha256)."
    fi
    say "Descargando ${fetch_name} de github.com/${TESTIGO_REPO}…" >&2
    download "$fetch_base/$fetch_name.sha256" "$work/$fetch_name.sha256" quiet >&2 ||
        fail "no pude descargar $fetch_name.sha256. Revisa la conexión o el proxy (HTTPS_PROXY)."
    download "$fetch_base/$fetch_name" "$work/$fetch_name" >&2 ||
        fail "no pude descargar $fetch_name. Revisa la conexión o el proxy (HTTPS_PROXY)."
    verify "$work/$fetch_name" "$work/$fetch_name.sha256"
    printf '%s' "$work/$fetch_name"
}

# Un archivo local (TESTIGO_ARCHIVE), con su .sha256 al lado. Imprime su ruta.
local_archive() {
    case "$1" in
        *.tar.gz) has tar || fail_env "falta tar. Instálalo con: $(install_hint tar)" ;;
        *.zip) has unzip || fail_env "falta unzip. Instálalo con: $(install_hint unzip)" ;;
        *) fail_env "TESTIGO_ARCHIVE debe ser un .zip o un .tar.gz: $1" ;;
    esac
    [ -f "$1" ] || fail_env "no existe TESTIGO_ARCHIVE: $1"
    [ -f "$1.sha256" ] || fail_env "falta $(basename "$1").sha256 junto a TESTIGO_ARCHIVE: sin él no puedo verificarlo."
    verify "$1" "$1.sha256"
    printf '%s' "$1"
}

# La versión desde el nombre del archivo: testigo-0.2.0.zip → 0.2.0.
version_from_archive() {
    archive_base=$(basename "$1")
    archive_base=${archive_base%.zip}
    archive_base=${archive_base%.tar.gz}
    printf '%s' "${archive_base#testigo-}"
}

# La carpeta física del archivo $1, siguiendo sus enlaces: POSIX no tiene readlink -f.
real_dir() {
    real_f=$1
    real_n=0
    while [ -L "$real_f" ] && [ "$real_n" -lt 20 ]; do
        real_l=$(readlink "$real_f")
        case "$real_l" in
            /*) real_f=$real_l ;;
            *) real_f=$(dirname "$real_f")/$real_l ;;
        esac
        real_n=$((real_n + 1))
    done
    (cd -P "$(dirname "$real_f")" 2>/dev/null && pwd)
}

# El enlace en $1 apunta a algo dentro de $2.
link_points_into() {
    [ -L "$1" ] || return 1
    case "$(readlink "$1")" in
        "$2"/*) return 0 ;;
        *) return 1 ;;
    esac
}

install_testigo() {
    home_dir="${TESTIGO_HOME:-$HOME/.testigo}"
    cli_dir="$home_dir/cli"
    bin_dir="${TESTIGO_BIN_DIR:-$HOME/.local/bin}"
    link="$bin_dir/testigo"
    link_target="$cli_dir/current/bin/testigo"

    detect_sha
    if [ -n "${TESTIGO_ARCHIVE:-}" ]; then
        version="${TESTIGO_VERSION:-$(version_from_archive "$TESTIGO_ARCHIVE")}"
    else
        has curl || fail_env "falta curl. Instálalo con: $(install_hint curl)"
        version="${TESTIGO_VERSION:-$(latest_version)}"
    fi
    version="${version#v}"
    valid_version "$version" || fail_env "versión inválida: $version (se espera algo como 0.2.0)."

    mkdir -p "$cli_dir" || fail_env "no puedo crear $(tilde "$cli_dir")."
    # Todo lo temporal dentro de cli/: mismo disco que el destino y solo para este usuario.
    work=$(mktemp -d "$cli_dir/.install.XXXXXX") || fail "no pude crear una carpeta temporal en $(tilde "$cli_dir")."
    trap cleanup EXIT
    trap 'cleanup; exit 130' INT TERM

    if [ -n "${TESTIGO_ARCHIVE:-}" ]; then
        archive=$(local_archive "$TESTIGO_ARCHIVE")
    else
        archive=$(fetch_release "$version")
    fi

    mkdir "$work/x"
    extract "$archive" "$work/x" || fail "no pude descomprimir $(basename "$archive")."
    unpacked="$work/x/testigo-$version"
    [ -x "$unpacked/bin/testigo" ] || fail "$(basename "$archive") no trae testigo-$version/bin/testigo."

    previous=""
    if [ -L "$cli_dir/current" ]; then previous=$(readlink "$cli_dir/current"); fi
    if [ -d "$cli_dir/current" ] && [ ! -L "$cli_dir/current" ]; then
        fail "$(tilde "$cli_dir/current") es una carpeta y debería ser un enlace: no la toco. Muévela y vuelve a intentar."
    fi

    rm -rf "${cli_dir:?}/$version"
    mv "$unpacked" "$cli_dir/$version"
    # Enlace relativo: sigue funcionando si se mueve TESTIGO_HOME.
    (cd "$cli_dir" && ln -sfn "$version" current)
    say "✔ testigo $version en $(tilde "$cli_dir/$version")"

    # El enlace en el PATH: si ya hay un `testigo` que no es de este instalador, no se pisa.
    link_ok=true
    if ! mkdir -p "$bin_dir" 2>/dev/null || [ ! -w "$bin_dir" ]; then
        link_ok=false
        warn "no puedo escribir en $(tilde "$bin_dir"): no creé el enlace. Usa TESTIGO_BIN_DIR con una carpeta tuya; este instalador no usa sudo."
    elif [ -e "$link" ] && ! link_points_into "$link" "$cli_dir"; then
        link_ok=false
        warn "ya existe $(tilde "$link") y no es de este instalador: no lo toqué."
    else
        ln -sfn "$link_target" "$link"
        say "✔ $(tilde "$link") → $(tilde "$link_target")"
    fi

    # Se conservan la versión nueva y la anterior, para volver atrás con TESTIGO_VERSION.
    for old in "$cli_dir"/*; do
        old_name=$(basename "$old")
        if [ ! -d "$old" ] || [ -L "$old" ] || ! valid_version "$old_name"; then continue; fi
        if [ "$old_name" = "$version" ] || [ "$old_name" = "$previous" ]; then continue; fi
        rm -rf "$old"
    done

    report "$cli_dir" "$bin_dir" "$link" "$link_ok"
}

# Qué quedó y qué sigue.
report() {
    report_cli=$1 report_bin=$2 report_link=$3 report_link_ok=$4
    report_exe="$report_cli/current/bin/testigo"

    if report_out=$("$report_exe" --version 2>/dev/null); then
        say "✔ $report_out"
    else
        warn "testigo necesita Java 17 o más y no encontré uno. testigo setup, en una terminal, ofrece descargarlo (sin root)."
    fi

    # El que la terminal va a ejecutar es el primero del PATH: esta versión (por cli/current o por el enlace) u otro, como
    # una instalación a mano o la versión anterior, si un setup de antes de la 0.3.0 dejó en el PATH su carpeta.
    report_first=$(command -v testigo 2>/dev/null || true)
    shadowed=false
    mine=false
    if [ -n "$report_first" ]; then
        report_first_dir=$(real_dir "$report_first")
        if [ "$report_first_dir" = "$(real_dir "$report_exe")" ]; then
            mine=true
        else
            shadowed=true
            case "$report_first_dir" in
                "$(cd -P "$report_cli" && pwd)"/*)
                    warn "tu terminal todavía usa otra versión, $(tilde "$report_first"): la dejó en el PATH un testigo setup anterior. El setup de esta versión (abajo) pone cli/current, que sigue sola a cada actualización." ;;
                *)
                    warn "tu terminal todavía usa otro testigo, que va antes en el PATH: $(tilde "$report_first"). El setup de esta instalación (abajo) deja la terminal en ella; después puedes borrar la otra." ;;
            esac
        fi
    fi

    # Una actualización con la terminal ya configurada por testigo setup (pone cli/current en el PATH): no hace falta otro.
    if [ "$mine" = true ] && grep -qs '/cli/current/bin' "${report_cli%/*}/env.sh"; then
        say ""
        say "Listo: tu terminal ya usa esta versión. No hace falta otro testigo setup;"
        say "  ejecútalo si quieres revisar Java, Maestro y el Android SDK de nuevo."
        return 0
    fi

    if [ "$report_link_ok" = true ]; then report_cmd="$(tilde "$report_link")"; else report_cmd="$(tilde "$report_exe")"; fi
    case ":$PATH:" in
        *":$report_bin:"*) if [ "$shadowed" = false ]; then report_cmd="testigo"; fi ;;
        *) say "  $(tilde "$report_bin") no está en tu PATH: testigo setup lo resuelve." ;;
    esac
    say ""
    say "Siguiente: $report_cmd setup"
    say "  Revisa Java, Maestro, el Android SDK y el PATH, y te pregunta antes de cambiar algo."
}

uninstall_testigo() {
    home_dir="${TESTIGO_HOME:-$HOME/.testigo}"
    cli_dir="$home_dir/cli"
    link="${TESTIGO_BIN_DIR:-$HOME/.local/bin}/testigo"
    if link_points_into "$link" "$cli_dir"; then
        rm -f "$link"
        say "✔ quité $(tilde "$link")"
    fi
    if [ -d "$cli_dir" ]; then
        rm -rf "$cli_dir"
        say "✔ quité $(tilde "$cli_dir")"
    else
        say "No hay nada instalado en $(tilde "$cli_dir")."
    fi
    say "No toqué Java (~/.testigo/jdk), Maestro, el Android SDK ni tus suites. Si testigo setup configuró tu terminal, ~/.testigo/env.sh sigue ahí; puedes dejarlo o quitar su bloque del perfil."
}

main() {
    : "${HOME:?falta HOME}"
    case "${1:-}" in
        "") detect_platform; install_testigo ;;
        --uninstall) uninstall_testigo ;;
        -h | --help) usage ;;
        *) usage >&2; fail_env "opción desconocida: $1" ;;
    esac
}

main "$@"
