package testigo.setup

import testigo.TestigoException
import testigo.process.Cleanup
import testigo.util.sha256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class InstallersTest {

    @TempDir
    lateinit var tmp: Path

    private fun maestroZip(version: String) = zipWith(
        tmp.resolve("descargas/maestro-$version.zip"),
        mapOf(
            "maestro/bin/maestro" to ("#!/bin/sh\necho $version\n" to "rwxr-xr-x"),
            "maestro/bin/maestro.bat" to ("@echo off\n" to "rw-r--r--"),
            "maestro/lib/maestro-cli-$version.jar" to ("jar" to "rw-r--r--"),
        ),
    )

    @Test
    fun `descomprime conservando los ejecutables y sin salir de la carpeta`() {
        val zip = maestroZip("2.11.0")
        val out = tmp.resolve("out")

        Unzip.extract(zip, out)

        assertTrue(out.resolve("maestro/bin/maestro").isExecutable(), "bin/maestro sigue siendo ejecutable")
        assertEquals("jar", out.resolve("maestro/lib/maestro-cli-2.11.0.jar").readText())

        val evil = tmp.resolve("evil.zip")
        ZipOutputStream(Files.newOutputStream(evil)).use { stream ->
            stream.putNextEntry(ZipEntry("../../fuera.txt"))
            stream.write("x".toByteArray())
            stream.closeEntry()
        }
        runCatching { Unzip.extract(evil, tmp.resolve("evil-out")) }
        assertFalse(tmp.resolve("fuera.txt").exists() || tmp.parent.resolve("fuera.txt").exists(), "nada se escribe fuera de la carpeta")
    }

    @Test
    fun `curl descarga y el SHA-256 decide si el archivo se usa`() {
        val content = "contenido"
        val expected = tmp.resolve("ref").also { it.writeText(content) }.sha256()
        val shell = FakeSetupShell(curl = { command -> Path.of(command[command.indexOf("-o") + 1]).writeText(content); 0 })
        val fetcher = CurlFetcher(shell)

        fetcher.fetch(Download("https://example.com/a.zip", expected, "1 KB"), tmp.resolve("ok/a.zip"))
        assertEquals(content, tmp.resolve("ok/a.zip").readText())
        val curl = shell.interactive.first().first
        assertEquals(listOf("curl", "-fL", "--progress-bar", "--proto", "=https", "--tlsv1.2", "-o"), curl.take(7))

        val error = assertThrows<TestigoException> { fetcher.fetch(Download("https://example.com/b.zip", "0".repeat(64), "1 KB"), tmp.resolve("mal/b.zip")) }
        assertTrue("no coincide" in error.message.orEmpty())
        assertEquals(emptyList<Path>(), tmp.resolve("mal").listDirectoryEntries(), "ni el archivo ni el .part quedan")

        val offline = CurlFetcher(FakeSetupShell(curl = { 6 }))
        val proxy = assertThrows<TestigoException> { offline.fetch(Download("https://example.com/c.zip", expected, "1 KB"), tmp.resolve("c.zip")) }
        assertTrue("HTTPS_PROXY" in proxy.message.orEmpty())
    }

    @Test
    fun `instala Maestro como el instalador oficial y reemplaza la versión anterior`() {
        val dir = tmp.resolve("home/.maestro")
        dir.resolve("lib").createDirectories().resolve("maestro-cli-2.10.0.jar").writeText("viejo")
        dir.resolve("tests").createDirectories().resolve("salida.txt").writeText("de Maestro")
        val fetcher = FakeFetcher(mapOf("cli-2.11.0/maestro.zip" to maestroZip("2.11.0")))

        val executable = MaestroInstaller(fetcher).install("2.11.0", dir)

        assertEquals(dir.resolve("bin/maestro"), executable)
        assertTrue(executable.isExecutable())
        assertEquals(listOf("maestro-cli-2.11.0.jar"), dir.resolve("lib").listDirectoryEntries().map { it.name }, "no quedan jars de otra versión")
        assertEquals("de Maestro", dir.resolve("tests/salida.txt").readText(), "lo demás de ~/.maestro no se toca")
        assertEquals(setOf("bin", "lib", "tests"), dir.listDirectoryEntries().map { it.name }.toSet(), "sin carpetas de descarga")
        assertEquals("5384593cb4e7a106489e75a821d157dd43f4e438df6bc308b72e82c685e1283a", fetcher.fetched.single().sha256)
        assertEquals(0, Cleanup.pending())
    }

    @Test
    fun `otra versión usa el checksums del release, y sin él no instala`() {
        val checksums = "29b675e10cc12080e445e9bfb2e2b4e4dfb9c0f2e30d5884120d258b5e1cd991  maestro.zip\n"
        assertEquals("29b675e10cc12080e445e9bfb2e2b4e4dfb9c0f2e30d5884120d258b5e1cd991", MaestroRelease.sha256From(checksums))
        assertNull(MaestroRelease.sha256From("abc  otro.zip\n"))

        val fetcher = FakeFetcher(
            mapOf("cli-2.10.0/maestro.zip" to maestroZip("2.10.0")),
            mapOf(MaestroRelease.checksumsUrl("2.10.0") to checksums, MaestroRelease.checksumsUrl("9.9.9") to "nada\n"),
        )
        MaestroInstaller(fetcher).install("2.10.0", tmp.resolve("m"))
        assertEquals("29b675e10cc12080e445e9bfb2e2b4e4dfb9c0f2e30d5884120d258b5e1cd991", fetcher.fetched.single().sha256)

        assertThrows<TestigoException> { MaestroInstaller(fetcher).install("9.9.9", tmp.resolve("m2")) }
        assertFalse(tmp.resolve("m2").exists() && tmp.resolve("m2").listDirectoryEntries().isNotEmpty())
    }

    @Test
    fun `el Android SDK se instala con las herramientas de Google y la licencia la acepta el usuario`() {
        val root = tmp.resolve("home/Library/Android/sdk")
        val tools = zipWith(tmp.resolve("descargas/cmdline.zip"), mapOf("cmdline-tools/bin/sdkmanager" to ("#!/bin/sh\n" to "rwxr-xr-x"), "cmdline-tools/source.properties" to ("Pkg.Revision=22.0\n" to null)))
        val download = CommandLineTools.download("Mac OS X", "aarch64")!!
        val shell = FakeSetupShell()
        val jdk = tmp.resolve("jdk")

        AndroidSdkInstaller(FakeFetcher(mapOf(download.fileName to tools)), shell).install(root, listOf("platform-tools", "build-tools;36.0.0"), download, jdk)

        val sdkmanager = root.resolve("cmdline-tools/latest/bin/sdkmanager")
        assertTrue(sdkmanager.isExecutable())
        val (command, env) = shell.interactive.single()
        assertEquals(listOf(sdkmanager.toString(), "--sdk_root=$root", "platform-tools", "build-tools;36.0.0"), command)
        assertEquals(mapOf("JAVA_HOME" to jdk.toString()), env)
        assertTrue(root.resolve("platform-tools/adb").exists() && root.resolve("build-tools/36.0.0/aapt2").exists())
        assertEquals(setOf("cmdline-tools", "platform-tools", "build-tools"), root.listDirectoryEntries().map { it.name }.toSet())

        val declined = assertThrows<TestigoException> {
            AndroidSdkInstaller(FakeFetcher(emptyMap()), FakeSetupShell(sdkmanagerCode = 1)).install(root, listOf("platform-tools"), null, jdk)
        }
        assertTrue("licencia" in declined.message.orEmpty(), "con el sdkmanager que ya estaba, no descarga nada")
    }

    @Test
    fun `la debug key se crea con keytool del Java de testigo, sin descargas`() {
        val shell = FakeSetupShell()
        val file = tmp.resolve("home/.android/debug.keystore")

        DebugKeystoreCreator(shell).create(file, tmp.resolve("jdk"))

        assertTrue(file.exists())
        val command = shell.captured.single()
        assertEquals(tmp.resolve("jdk/bin/keytool").toString(), command.first())
        assertTrue(listOf("-alias", "androiddebugkey", "-storepass", "android").all { it in command })
    }
}
