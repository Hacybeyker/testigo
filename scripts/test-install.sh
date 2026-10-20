#!/bin/sh
# Prueba install.sh con los archivos de un build (build/distributions) o de un release: los SHA-256, instalar el .tar.gz
# y el .zip, instalar por un pipe como `curl … | bash`, rechazar un SHA-256 que no coincide y desinstalar. Todo va a una
# carpeta temporal (TESTIGO_HOME y TESTIGO_BIN_DIR): no toca ~/.testigo ni ~/.local/bin. Necesita Java 17 o más.
# Uso: sh scripts/test-install.sh build/distributions
set -eu

ok() { printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*" >&2; exit 1; }

[ $# -eq 1 ] || fail "uso: sh scripts/test-install.sh <carpeta con testigo-<versión>.tar.gz, .zip y SHA256SUMS>"
dist=$(cd "$1" && pwd)
installer=$(cd "$(dirname "$0")/.." && pwd)/install.sh

set -- "$dist"/testigo-*.tar.gz
if [ $# -ne 1 ] || [ ! -f "$1" ]; then fail "esperaba un solo testigo-<versión>.tar.gz en $dist"; fi
tarball=$1
version=${tarball##*/testigo-}
version=${version%.tar.gz}
zip=$dist/testigo-$version.zip
[ -f "$zip" ] || fail "falta testigo-$version.zip en $dist"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unset TESTIGO_VERSION TESTIGO_ARCHIVE
TESTIGO_HOME=$work/state
TESTIGO_BIN_DIR=$work/bin
export TESTIGO_HOME TESTIGO_BIN_DIR

# Corre install.sh con [shell] y lo que siga; si falla, muestra lo que dijo.
run() {
    run_name=$1
    shift
    if ! "$@" >"$work/log" 2>&1; then
        cat "$work/log" >&2
        fail "$run_name: install.sh terminó con error"
    fi
}

installed() {
    got=$("$TESTIGO_BIN_DIR/testigo" --version) || fail "$1: testigo --version falló"
    [ "$got" = "testigo version $version" ] || fail "$1: testigo --version dice «${got}»"
    [ "$(readlink "$TESTIGO_HOME/cli/current")" = "$version" ] || fail "$1: cli/current no apunta a $version"
    ok "$1"
}

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dist" && sha256sum -c SHA256SUMS) >/dev/null || fail "SHA256SUMS no coincide con los archivos"
else
    (cd "$dist" && shasum -a 256 -c SHA256SUMS) >/dev/null || fail "SHA256SUMS no coincide con los archivos"
fi
ok "SHA256SUMS coincide ($version)"

run ".tar.gz" env TESTIGO_ARCHIVE="$tarball" sh "$installer"
installed ".tar.gz con sh ($(command -v sh))"

run ".zip" env TESTIGO_ARCHIVE="$zip" bash "$installer"
installed ".zip con bash $(bash -c 'echo "$BASH_VERSION"')"

# shellcheck disable=SC2016 # $1 y $2 son del sh -c, no de este script
run "pipe" sh -c 'cat "$1" | TESTIGO_ARCHIVE="$2" bash' sh "$installer" "$tarball"
installed "por un pipe, como curl … | bash"

if command -v shellcheck >/dev/null 2>&1; then
    shellcheck -s sh "$TESTIGO_HOME/cli/current/bin/testigo" || fail "ShellCheck encontró problemas en bin/testigo"
    ok "bin/testigo pasa ShellCheck"
fi

mkdir "$work/bad"
cp "$tarball" "$work/bad/"
printf '%064d  %s\n' 0 "testigo-$version.tar.gz" >"$work/bad/testigo-$version.tar.gz.sha256"
if env TESTIGO_HOME="$work/bad-state" TESTIGO_ARCHIVE="$work/bad/testigo-$version.tar.gz" sh "$installer" >"$work/log" 2>&1; then
    fail "instaló un archivo cuyo SHA-256 no coincide"
fi
[ ! -e "$work/bad-state/cli/$version" ] || fail "con el SHA-256 malo quedó algo en cli/$version"
ok "rechaza un SHA-256 que no coincide, sin instalar nada"

run "--uninstall" sh "$installer" --uninstall
if [ -e "$TESTIGO_BIN_DIR/testigo" ] || [ -e "$TESTIGO_HOME/cli" ]; then fail "--uninstall dejó el enlace o cli/"; fi
ok "--uninstall quita el enlace y cli/"
