# Avisos de terceros

`testigo` se distribuye bajo la Licencia Apache 2.0 ([`LICENSE`](LICENSE) y [`NOTICE`](NOTICE)). Este archivo trata solo de lo que es de terceros.

`testigo` se construye con librerías de código abierto. Las que viajan en cada versión, en el `.zip` y el `.tar.gz` (carpetas `lib/` y `tools/`), permiten usarlas, copiarlas y redistribuirlas, también en un uso comercial o dentro de una empresa, siempre que se conserven sus avisos de licencia. Este archivo y la carpeta [`licenses/`](licenses/) cumplen con eso. Ninguna obliga a publicar el código de `testigo` ni el de la app que se prueba.

## Incluidas en cada versión

| Componente | Versión | Licencia | Para qué la usa `testigo` |
| --- | --- | --- | --- |
| [Kotlin stdlib](https://github.com/JetBrains/kotlin) | 2.4.21 | Apache 2.0 | El runtime de Kotlin, el lenguaje del runner. |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (core) | 1.11.0 | Apache 2.0 | Los modelos de `testigo.yaml` y de los headers de los flows. |
| [Clikt](https://github.com/ajalt/clikt) (`clikt`, `clikt-mordant`) | 5.1.0 | Apache 2.0 | Comandos, flags, ayuda (`--help`) y validación de opciones. |
| [Mordant](https://github.com/ajalt/mordant) (`mordant`, `mordant-omnibus`, `mordant-jvm-jna`, `mordant-jvm-ffm`, `mordant-jvm-graal-ffi`) | 3.1.0 | Apache 2.0 | La terminal y las listas de selección del modo interactivo. |
| [Colormath](https://github.com/ajalt/colormath) | 3.7.0 | MIT | Colores de la terminal; dependencia de Mordant. |
| [JNA](https://github.com/java-native-access/jna) | 5.19.1 | Apache 2.0 o LGPL 2.1 o posterior, a elección; `testigo` la usa bajo Apache 2.0 | Acceso nativo a la terminal; dependencia de Mordant. |
| [kaml](https://github.com/charleskorn/kaml) | 0.104.0 | Apache 2.0 | Leer `testigo.yaml` y los headers de los flows. |
| [SnakeYAML Engine KMP](https://github.com/krzema12/snakeyaml-engine-kmp) | 4.0.1 | Apache 2.0 | El parser YAML que usa kaml. |
| [Okio](https://github.com/square/okio) | 3.16.4 | Apache 2.0 | Entrada y salida; dependencia del parser YAML. |
| [urlencoder-lib](https://github.com/ethauvin/urlencoder) | 1.6.0 | Apache 2.0 | Codificación de URLs; dependencia del parser YAML. |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | 3.53.4.0 | Apache 2.0; incluye código BSD de 2 cláusulas (David Crawshaw) y SQLite (dominio público) | Leer la copia local de la base de Chucker. |
| [JetBrains Java Annotations](https://github.com/JetBrains/java-annotations) | 13.0 | Apache 2.0 | Anotaciones de nulabilidad; dependencia de Kotlin. |
| [bundletool](https://github.com/google/bundletool) (`tools/`) | 1.18.3 | Apache 2.0, con el aviso del Android Open Source Project | Convertir un AAB en los APKs para el dispositivo. |

## Herramientas que `testigo` usa pero no incluye

No viajan con `testigo`. Cada dev las instala, a mano o con `testigo setup`, que las descarga de su sitio oficial solo si lo confirma. `testigo` solo las ejecuta en la máquina local.

| Herramienta | Licencia | Dónde vive | Con `testigo setup` |
| --- | --- | --- | --- |
| [Maestro](https://github.com/mobile-dev-inc/maestro) | Apache 2.0 | En la máquina del dev (`~/.maestro`). | Descarga `maestro.zip` del release oficial de GitHub. |
| Android SDK Command-line Tools (`sdkmanager`), Platform-Tools y Build-Tools (`adb`, `aapt2`) | Android Software Development Kit License Agreement | En el Android SDK del dev. | Descarga las command-line tools de `dl.google.com`; `sdkmanager` muestra la licencia y el dev la acepta antes de instalar el resto. |
| Java (JDK 17 o más) | La del proveedor; [Eclipse Temurin](https://adoptium.net) y OpenJDK son GPLv2 con Classpath Exception | En la máquina del dev. | Si no hay ninguno, `bin/testigo` ofrece descargar Eclipse Temurin 21 de GitHub en `~/.testigo/jdk`. |
| [Chucker](https://github.com/ChuckerTeam/chucker) | Apache 2.0 | Dentro del build de la app que se prueba, no en `testigo`. El runner solo lee su base de datos. | No aplica. |

## Solo para compilar y probar

No viajan con `testigo`.

| Herramienta | Versión | Licencia |
| --- | --- | --- |
| [Gradle](https://github.com/gradle/gradle) (wrapper) | 9.7.1 | Apache 2.0 |
| Kotlin Gradle plugin y plugin de serialización | 2.4.21 | Apache 2.0 |
| [JUnit](https://github.com/junit-team/junit-framework) | 6.1.3 | Eclipse Public License 2.0 |

## Textos de licencia

| Archivo | Aplica a |
| --- | --- |
| [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) | Todo lo que es Apache 2.0 |
| [`licenses/NOTICE-bundletool.txt`](licenses/NOTICE-bundletool.txt) | Aviso del Android Open Source Project que trae bundletool |
| [`licenses/MIT-colormath.txt`](licenses/MIT-colormath.txt) | Colormath |
| [`licenses/BSD-2-Clause-sqlite-jdbc.txt`](licenses/BSD-2-Clause-sqlite-jdbc.txt) | La parte BSD de sqlite-jdbc |
| [`licenses/JNA-LICENSE.txt`](licenses/JNA-LICENSE.txt) | JNA (doble licencia) |

Al agregar o actualizar una dependencia en `gradle/libs.versions.toml`, actualiza también esta tabla. `./gradlew dependencies --configuration runtimeClasspath` muestra la lista exacta de lo que viaja en `lib/`.
