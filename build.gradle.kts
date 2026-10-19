import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "testigo"
version = "0.2.0"

// Java 17 es el mínimo que ya exigen Maestro y bundletool: no sumamos ningún runtime.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Descarga un archivo una sola vez y verifica su SHA-256. Si el hash no coincide, el build falla y el archivo se borra.
 */
abstract class DownloadVerified : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun download() {
        val target = output.get().asFile.toPath()
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling("${target.fileName}.part")
        URI(url.get()).toURL().openStream().use { input -> Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING) }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(partial).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            Files.deleteIfExists(partial)
            throw GradleException("SHA-256 inesperado para ${url.get()}: $actual (se esperaba ${sha256.get()})")
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Escribe `version` como una constante de Kotlin: `testigo --version`, el HAR y `metadata.json` la leen de ahí. */
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val appVersion: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = output.get().file("testigo/GeneratedBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Lo genera la tarea generateBuildInfo de build.gradle.kts a partir de `version`: no lo edites.\n" +
                "package testigo\n\ninternal const val GENERATED_VERSION = \"${appVersion.get()}\"\n",
        )
    }
}

// La versión se cambia solo en `version`, arriba: el código la recibe generada en build/generated/.
val generateBuildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
    appVersion.set(project.version.toString())
    output.set(layout.buildDirectory.dir("generated/source/buildinfo/main"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateBuildInfo) }

val bundletoolVersion = libs.versions.bundletool.get()

// bundletool oficial para convertir AAB en APKs. El SHA-256 es el que publica GitHub en el release.
// Queda en .gradle/downloads/ y sobrevive a `./gradlew clean`.
val downloadBundletool = tasks.register<DownloadVerified>("downloadBundletool") {
    url.set("https://github.com/google/bundletool/releases/download/$bundletoolVersion/bundletool-all-$bundletoolVersion.jar")
    sha256.set("a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29")
    output.set(layout.projectDirectory.file(".gradle/downloads/bundletool-all-$bundletoolVersion.jar"))
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.sqlite.jdbc)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("testigo.MainKt")
    // Los scripts de arranque quedan como bin/testigo y bin/testigo.bat.
    applicationName = "testigo"
    // Mordant (terminal de Clikt) usa la API FFM en JDK 22+; sin esto, la JVM imprime avisos.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// bin/testigo busca un Java 17 o más antes de arrancar la JVM (y, con `testigo setup`, ofrece descargarlo); bin/testigo.bat solo
// revisa JAVA_HOME. Con un Java más viejo la JVM no carga el runner y solo mostraría UnsupportedClassVersionError.
// Los fragmentos están en gradle/start-scripts/.
tasks.startScripts {
    // Variables locales y no del script: el configuration cache no puede guardar el objeto del script.
    val javaCheckSh = layout.projectDirectory.file("gradle/start-scripts/java-check.sh").asFile
    val javaCheckBat = layout.projectDirectory.file("gradle/start-scripts/java-check.bat").asFile
    inputs.files(javaCheckSh, javaCheckBat)
    doLast {
        val scripts = this as CreateStartScripts
        fun insert(script: File, anchor: String, snippet: String, after: Boolean) {
            val text = script.readText()
            check(anchor in text) { "No encontré \"$anchor\" en ${script.name}: cambió la plantilla de Gradle." }
            script.writeText(text.replaceFirst(anchor, if (after) anchor + snippet else snippet + anchor))
        }
        insert(scripts.unixScript, "# Determine the Java command to use to start the JVM.", javaCheckSh.readText(), after = false)
        insert(scripts.windowsScript, ":execute\r\n", javaCheckBat.readText(), after = true)
    }
}

// bundletool va en tools/ de la distribución, fuera del classpath: el jar trae su propia versión de Kotlin.
// El zip lleva además el README, el CHANGELOG, la licencia del proyecto (LICENSE y NOTICE), las guías (docs/), la skill de
// Claude Code para escribir flows (skills/) y los avisos y textos de licencia de las dependencias.
distributions {
    main {
        contents {
            from(downloadBundletool) { into("tools") }
            from("README.md")
            from("CHANGELOG.md")
            from("LICENSE")
            from("NOTICE")
            from("THIRD_PARTY_NOTICES.md")
            from("docs") { into("docs") }
            from("skills") {
                into("skills")
                filesMatching("**/scripts/*") { permissions { unix("rwxr-xr-x") } }
            }
            from("licenses") { into("licenses") }
        }
    }
}

// Un solo formato para repartir: zip, que se abre igual en macOS, Linux y Windows.
tasks.named("distTar") { enabled = false }

// testigo-<versión>.zip.sha256 junto al zip, para verificarlo al copiarlo a otra máquina: shasum -a 256 -c <archivo>.
val distZipSha256 = tasks.register("distZipSha256") {
    description = "Escribe el SHA-256 del zip de la distribución junto a él."
    val zip = tasks.named<Zip>("distZip").flatMap { it.archiveFile }
    val checksum = zip.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") }
    inputs.file(zip)
    outputs.file(checksum)
    doLast {
        val file = zip.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        checksum.get().writeText("$hex  ${file.name}\n")
    }
}
tasks.named("distZip") { finalizedBy(distZipSha256) }

// `./gradlew run --args="run --dry-run"` se ejecuta contra la suite de ejemplo.
tasks.named<JavaExec>("run") {
    workingDir = layout.projectDirectory.dir("examples/demo-suite").asFile
    dependsOn(downloadBundletool)
}

tasks.test {
    useJUnitPlatform()
}
