# testigo: el runner, Maestro y bundletool necesitan Java 17 o más. Se busca antes de arrancar la JVM, porque con un
# Java más viejo la JVM ni siquiera carga el runner. Orden: JAVA_HOME, el java del PATH, el que instala `testigo setup`
# (~/.testigo/jdk), el que registra macOS (java_home), el de Android Studio (jbr) y los de /usr/lib/jvm. Si no hay
# ninguno, `testigo setup` ofrece descargar Eclipse Temurin 21 en ~/.testigo/jdk: sin root y con su SHA-256 verificado.
TESTIGO_APP_HOME=$APP_HOME
export TESTIGO_APP_HOME
testigo_state=${TESTIGO_HOME:-$HOME/.testigo}
case $testigo_state in
    "$HOME"/*) testigo_show="~${testigo_state#"$HOME"}" ;;
    *)         testigo_show=$testigo_state ;;
esac

# Versión mayor de un JDK: de su archivo release o, si no lo tiene, preguntándole a su java.
testigo_major () {
    testigo_v=
    if [ -r "$1/release" ] ; then
        testigo_v=$( sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' "$1/release" )
    fi
    if [ -z "$testigo_v" ] ; then
        testigo_v=$( "$1/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' )
    fi
    case $testigo_v in 1.*) testigo_v=${testigo_v#1.} ;; esac
    printf '%s\n' "${testigo_v%%[!0-9]*}"
}

# Si $1 es un JDK 17 o más.
testigo_usable () {
    [ -n "$1" ] && [ -x "$1/bin/java" ] || return 1
    testigo_m=$( testigo_major "$1" )
    [ -n "$testigo_m" ] && [ "$testigo_m" -ge 17 ]
}

# Si el java del PATH es 17 o más. El /usr/bin/java de macOS sin ningún JDK no es un Java: ni se ejecuta.
testigo_path_usable () {
    command -v java >/dev/null 2>&1 || return 1
    if "$darwin" && [ "$( command -v java )" = /usr/bin/java ] && ! /usr/libexec/java_home >/dev/null 2>&1 ; then
        return 1
    fi
    testigo_v=$( java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.specification\.version = //p' )
    case $testigo_v in 1.*) testigo_v=${testigo_v#1.} ;; esac
    testigo_m=${testigo_v%%[!0-9]*}
    [ -n "$testigo_m" ] && [ "$testigo_m" -ge 17 ]
}

# Cómo escribir este mismo testigo en un mensaje: `testigo` si es el que encuentra la terminal (también por un enlace, como
# ~/.local/bin/testigo); si no, la ruta que deja install.sh (cli/current), que no cambia con cada versión, o la de esta copia.
testigo_command () {
    testigo_f=$( command -v testigo 2>/dev/null ) || testigo_f=
    while [ -n "$testigo_f" ] && [ -h "$testigo_f" ] ; do
        testigo_l=$( ls -ld -- "$testigo_f" )
        testigo_l=${testigo_l#*' -> '}
        case $testigo_l in
            /*) testigo_f=$testigo_l ;;
            *)  testigo_f=${testigo_f%/*}/$testigo_l ;;
        esac
    done
    if [ -n "$testigo_f" ] && [ "$( cd -P -- "${testigo_f%/*}" 2>/dev/null && pwd )" = "$APP_HOME/bin" ] ; then
        echo testigo
    elif [ -h "$testigo_state/cli/current" ] && [ "$( cd -P -- "$testigo_state/cli/current" 2>/dev/null && pwd )" = "$APP_HOME" ] ; then
        echo "$testigo_show/cli/current/bin/testigo"
    else
        echo "$APP_HOME/bin/testigo"
    fi
}

# Solo con `testigo setup` en una terminal: descarga Temurin 21 en ~/.testigo/jdk/current, si el usuario dice que sí.
testigo_offer_jdk () {
    testigo_machine=$( uname -m )
    if "$darwin" && [ "$( sysctl -n sysctl.proc_translated 2>/dev/null )" = 1 ] ; then
        testigo_machine=arm64    # una terminal con Rosetta en un Mac con Apple Silicon
    fi
    case "$( uname -s )/$testigo_machine" in
        Darwin/arm64)              testigo_pkg=aarch64_mac   testigo_sha=3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998 ;;
        Darwin/x86_64)             testigo_pkg=x64_mac       testigo_sha=44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce ;;
        Linux/aarch64|Linux/arm64) testigo_pkg=aarch64_linux testigo_sha=23e37e026f12f3e706f18938ff611db3032d075b09d0879a25d06718c773e223 ;;
        Linux/x86_64|Linux/amd64)  testigo_pkg=x64_linux     testigo_sha=ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94 ;;
        *)
            echo "No tengo un JDK para descargar en $( uname -s ) $testigo_machine: instala Java 17 o más a mano." >&2
            return 1 ;;
    esac
    testigo_jdk=jdk-21.0.12.1+1
    testigo_url=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_${testigo_pkg}_hotspot_21.0.12.1_1.tar.gz

    echo
    echo "testigo necesita Java 17 o más y no encontré ninguno en esta máquina."
    echo "Puedo descargar Java 21 (Eclipse Temurin 21.0.12.1, unos 200 MB) y dejarlo en $testigo_show/jdk:"
    echo "  · desde $testigo_url"
    echo "  · verificando su SHA-256 antes de usarlo"
    echo "  · sin root ni sudo; no cambia nada fuera de $testigo_show"
    printf '¿Descargarlo ahora? [s/N] '
    read -r testigo_answer || testigo_answer=
    case $testigo_answer in
        s|S|si|Si|SI|sí|Sí|SÍ|y|Y|yes|Yes) ;;
        *) echo "No descargué nada." ; return 1 ;;
    esac
    if ! command -v curl >/dev/null 2>&1 ; then
        echo "Falta curl para descargar el JDK: instala Java 17 o más a mano." >&2
        return 1
    fi
    mkdir -p "$testigo_state/jdk" || return 1
    testigo_tmp=$( mktemp -d "$testigo_state/jdk/.download-XXXXXX" ) || return 1
    trap 'rm -rf "$testigo_tmp" ; echo ; echo "Cancelado: borré la descarga a medias." ; exit 130' INT TERM
    if ! curl -fL --progress-bar --proto '=https' --tlsv1.2 -o "$testigo_tmp/jdk.tar.gz" "$testigo_url" ; then
        rm -rf "$testigo_tmp" ; trap - INT TERM
        echo "No pude descargar el JDK. Revisa la conexión o el proxy (curl usa HTTPS_PROXY) y vuelve a intentarlo." >&2
        return 1
    fi
    if command -v shasum >/dev/null 2>&1 ; then
        testigo_got=$( shasum -a 256 "$testigo_tmp/jdk.tar.gz" )
    else
        testigo_got=$( sha256sum "$testigo_tmp/jdk.tar.gz" )
    fi
    testigo_got=${testigo_got%% *}
    if [ "$testigo_got" != "$testigo_sha" ] ; then
        rm -rf "$testigo_tmp" ; trap - INT TERM
        echo "El SHA-256 del JDK descargado no coincide ($testigo_got): lo borré sin usarlo." >&2
        return 1
    fi
    if ! tar -xzf "$testigo_tmp/jdk.tar.gz" -C "$testigo_tmp" || [ ! -d "$testigo_tmp/$testigo_jdk" ] ; then
        rm -rf "$testigo_tmp" ; trap - INT TERM
        echo "No pude descomprimir el JDK." >&2
        return 1
    fi
    rm -rf "${testigo_state:?}/jdk/$testigo_jdk"
    mv "$testigo_tmp/$testigo_jdk" "$testigo_state/jdk/" || { rm -rf "$testigo_tmp" ; trap - INT TERM ; return 1 ; }
    rm -rf "$testigo_tmp" ; trap - INT TERM
    testigo_rel=$testigo_jdk
    [ -d "$testigo_state/jdk/$testigo_jdk/Contents/Home" ] && testigo_rel=$testigo_jdk/Contents/Home
    rm -f "$testigo_state/jdk/current"
    ln -s "$testigo_rel" "$testigo_state/jdk/current" || return 1
    if ! testigo_usable "$testigo_state/jdk/current" ; then
        echo "El JDK quedó en $testigo_show/jdk, pero no arranca." >&2
        return 1
    fi
    echo "✔ Java 21 listo en $testigo_show/jdk/current"
    echo
}

testigo_need=false
testigo_bad_home=
testigo_bad_major=
if [ -n "$JAVA_HOME" ] ; then
    if ! testigo_usable "$JAVA_HOME" ; then
        testigo_need=true
        testigo_bad_home=$JAVA_HOME
        [ -x "$JAVA_HOME/bin/java" ] && testigo_bad_major=$( testigo_major "$JAVA_HOME" )
    fi
elif ! testigo_path_usable ; then
    testigo_need=true
fi

if "$testigo_need" ; then
    testigo_found=
    for testigo_dir in \
        "$testigo_state/jdk/current" \
        "$( "$darwin" && /usr/libexec/java_home -v 17+ 2>/dev/null )" \
        "/Applications/Android Studio"*.app/Contents/jbr/Contents/Home \
        "$HOME/Applications/Android Studio"*.app/Contents/jbr/Contents/Home \
        /opt/android-studio/jbr \
        "$HOME/android-studio/jbr" \
        /usr/local/android-studio/jbr \
        /snap/android-studio/current/jbr \
        /usr/lib/jvm/*
    do
        if testigo_usable "$testigo_dir" ; then
            testigo_found=$testigo_dir
            break
        fi
    done
    if [ -z "$testigo_found" ] && [ "$1" = setup ] && [ -t 0 ] && [ -t 1 ] && testigo_offer_jdk ; then
        testigo_found=$testigo_state/jdk/current
    fi
    if [ -n "$testigo_found" ] ; then
        if [ -n "$testigo_bad_home" ] ; then
            echo "Aviso: JAVA_HOME apunta a ${testigo_bad_major:+Java $testigo_bad_major en }$testigo_bad_home; testigo usa el Java de $testigo_found. \`$( testigo_command ) setup\` lo deja configurado." >&2
            TESTIGO_JAVA_IGNORED=$testigo_bad_home
            export TESTIGO_JAVA_IGNORED
        fi
        JAVA_HOME=$testigo_found
        TESTIGO_JAVA_FOUND=$testigo_found
        export JAVA_HOME TESTIGO_JAVA_FOUND
    else
        echo >&2
        if [ -n "$testigo_bad_major" ] ; then
            echo "Error: testigo necesita Java 17 o más, y JAVA_HOME es Java $testigo_bad_major: $testigo_bad_home" >&2
        elif [ -n "$testigo_bad_home" ] ; then
            echo "Error: JAVA_HOME no apunta a un JDK: $testigo_bad_home" >&2
        else
            echo "Error: testigo necesita Java 17 o más y no encontré ninguno (ni en el PATH, ni el de Android Studio)." >&2
        fi
        echo "Ejecuta \`$( testigo_command ) setup\` en una terminal: descarga Java 21 en $testigo_show/jdk, sin root." >&2
        echo "O apunta JAVA_HOME a un JDK 17 o más nuevo, por ejemplo el que trae Android Studio (jbr)." >&2
        echo >&2
        exit 2
    fi
fi

# testigo: la salida va siempre en UTF-8. Sin locale (contenedores, SSH, CI), Java escribiría "?" en vez de acentos y ✔.
TESTIGO_OPTS="-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 $TESTIGO_OPTS"

