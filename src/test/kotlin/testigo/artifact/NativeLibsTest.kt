package testigo.artifact

import testigo.artifact.TestApks.Entry
import testigo.artifact.TestApks.elf
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path

class NativeLibsTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `lee la alineacion de los segmentos LOAD del ELF`() {
        assertEquals(true, NativeLibs.elfAligned(elf(16_384)))
        assertEquals(true, NativeLibs.elfAligned(elf(65_536)))
        assertEquals(false, NativeLibs.elfAligned(elf(4_096)))
        assertNull(NativeLibs.elfAligned("no es un ELF, solo texto suficientemente largo para la cabecera...".toByteArray()))
    }

    @Test
    fun `en un APK revisa la ABI del dispositivo, el ELF y, si va sin comprimir, donde empieza en el zip`() {
        val apk = TestApks.write(
            tmp.resolve("app.apk"),
            listOf(
                Entry("AndroidManifest.xml", "x".toByteArray()),
                Entry("lib/arm64-v8a/libok.so", elf(16_384)),
                Entry("lib/arm64-v8a/libvieja.so", elf(4_096)),
                Entry("lib/arm64-v8a/libsuelta.so", elf(16_384), stored = true),
                Entry("lib/armeabi-v7a/libotra.so", elf(4_096)),
            ),
        )

        val libs = NativeLibs.scan(apk, ArtifactFormat.APK, "arm64-v8a").associateBy { it.name }

        assertEquals(setOf("libok.so", "libvieja.so", "libsuelta.so"), libs.keys, "solo la ABI del dispositivo")
        assertTrue(libs.getValue("libok.so").aligned)
        assertNull(libs.getValue("libok.so").zipAligned, "comprimida: Android la extrae y el zip no importa")
        assertEquals(false, libs.getValue("libvieja.so").elfAligned)
        assertEquals(true, libs.getValue("libsuelta.so").elfAligned)
        assertEquals(false, libs.getValue("libsuelta.so").zipAligned)
    }

    @Test
    fun `una lib sin comprimir que empieza en un multiplo de 16 KB esta alineada`() {
        val name = "lib/x86_64/liba.so"
        // Relleno para que los datos de la lib caigan justo en 16384: cabecera local (30) + nombre, dos veces.
        val padding = ByteArray(16_384 - (30 + "pad".length) - (30 + name.length))
        val bytes = elf(16_384)
        val apk = TestApks.write(tmp.resolve("app.apk"), listOf(Entry("pad", padding, stored = true), Entry(name, bytes, stored = true)))

        val offset = NativeLibs.storedDataOffsets(apk).getValue(name)
        assertEquals(16_384L, offset)
        val read = RandomAccessFile(apk.toFile(), "r").use { raf -> ByteArray(bytes.size).also { raf.seek(offset); raf.readFully(it) } }
        assertArrayEquals(bytes, read)
        assertEquals(true, NativeLibs.scan(apk, ArtifactFormat.APK, null).single().zipAligned)
    }

    @Test
    fun `en un AAB solo cuenta el ELF, y sin ABI revisa las de 64 bits`() {
        val aab = TestApks.write(
            tmp.resolve("app.aab"),
            listOf(
                Entry("base/lib/arm64-v8a/liba.so", elf(16_384), stored = true),
                Entry("base/lib/x86_64/liba.so", elf(4_096)),
                Entry("base/lib/armeabi-v7a/liba.so", elf(4_096)),
            ),
        )

        val libs = NativeLibs.scan(aab, ArtifactFormat.AAB, null)

        assertEquals(listOf("base/lib/arm64-v8a/liba.so", "base/lib/x86_64/liba.so"), libs.map { it.path })
        assertEquals(listOf(true, false), libs.map { it.aligned })
        assertTrue(libs.all { it.zipAligned == null })
    }
}
