package testigo.artifact

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories

/** APKs y AABs de prueba con libs nativas: solo lo que lee [NativeLibs]. */
internal object TestApks {
    /** [stored]: sin comprimir, como una lib que Android carga desde el APK. */
    class Entry(val name: String, val bytes: ByteArray, val stored: Boolean = false)

    /** Un ELF de 64 bits con dos segmentos `LOAD` alineados a [align]. */
    fun elf(align: Long): ByteArray {
        val buffer = ByteBuffer.allocate(64 + 56 * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        buffer.putLong(0x20, 64)
        buffer.putShort(0x36, 56)
        buffer.putShort(0x38, 2)
        repeat(2) { i ->
            val at = 64 + 56 * i
            buffer.putInt(at, 1)
            buffer.putLong(at + 48, align)
        }
        return buffer.array()
    }

    fun write(path: Path, entries: List<Entry>): Path {
        path.parent.createDirectories()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { file ->
                val entry = ZipEntry(file.name)
                if (file.stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = file.bytes.size.toLong()
                    entry.compressedSize = file.bytes.size.toLong()
                    entry.crc = CRC32().apply { update(file.bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
        return path
    }
}
