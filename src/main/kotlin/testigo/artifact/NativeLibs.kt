package testigo.artifact

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Las librerías nativas (`.so`) de un APK o AAB y si sirven en un dispositivo con páginas de 16 KB. Si no, Android muestra
 * «Android App Compatibility» sobre la app al abrirla. Solo lee el archivo: nada sale de la máquina.
 *
 * Se revisan dos cosas, como Android: que los segmentos `LOAD` de cada ELF estén alineados a 16 KB y, si la lib va sin
 * comprimir en el APK (se carga desde ahí), que sus datos empiecen en un múltiplo de 16 KB dentro del zip. En un AAB solo
 * cuenta el ELF: el zip de los APKs lo arma bundletool.
 */
object NativeLibs {
    const val PAGE_16K = 16_384L
    private const val PT_LOAD = 1
    private const val HEAD_BYTES = 64 * 1024
    private val BITS_64 = setOf("arm64-v8a", "x86_64")

    /** Una `.so`. null: no se pudo saber (un ELF raro o un zip64). */
    data class Lib(val path: String, val elfAligned: Boolean?, val zipAligned: Boolean?) {
        val name: String get() = path.substringAfterLast('/')
        val aligned: Boolean get() = elfAligned != false && zipAligned != false
    }

    /** Las libs de [abi] (la del dispositivo, `arm64-v8a`) o, si es null, las de 64 bits. */
    fun scan(file: Path, format: ArtifactFormat, abi: String?): List<Lib> {
        val pattern = if (format == ArtifactFormat.APK) Regex("^lib/([^/]+)/[^/]+\\.so$") else Regex("^[^/]+/lib/([^/]+)/[^/]+\\.so$")
        val stored = if (format == ArtifactFormat.APK) storedDataOffsets(file) else emptyMap()
        return ZipFile(file.toFile()).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { entry ->
                    val entryAbi = pattern.find(entry.name)?.groupValues?.get(1) ?: return@filter false
                    if (abi != null) entryAbi == abi else entryAbi in BITS_64
                }
                .map { entry ->
                    val head = zip.getInputStream(entry).use { it.readNBytes(HEAD_BYTES) }
                    val zipAligned = when {
                        format != ArtifactFormat.APK || entry.method != ZipEntry.STORED -> null
                        else -> stored[entry.name]?.let { it % PAGE_16K == 0L }
                    }
                    Lib(entry.name, elfAligned(head), zipAligned)
                }
                .toList()
        }
    }

    /** true si todos los segmentos `LOAD` piden 16 KB o más; null si no es un ELF que se pueda leer. */
    internal fun elfAligned(head: ByteArray): Boolean? {
        if (head.size < 52 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) {
            return null
        }
        val is64 = head[4].toInt() == 2
        if (is64 && head.size < 64) return null
        val buffer = ByteBuffer.wrap(head).order(if (head[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val phoff = if (is64) buffer.getLong(0x20) else buffer.getInt(0x1C).toLong() and 0xffffffffL
        val entrySize = (if (is64) buffer.getShort(0x36) else buffer.getShort(0x2A)).toInt() and 0xffff
        val count = (if (is64) buffer.getShort(0x38) else buffer.getShort(0x2C)).toInt() and 0xffff
        if (phoff <= 0 || entrySize < (if (is64) 56 else 32) || phoff + entrySize.toLong() * count > head.size) return null
        var loads = 0
        for (i in 0 until count) {
            val at = (phoff + i.toLong() * entrySize).toInt()
            if (buffer.getInt(at) != PT_LOAD) continue
            loads++
            val align = if (is64) buffer.getLong(at + 48) else buffer.getInt(at + 28).toLong() and 0xffffffffL
            if (align < PAGE_16K) return false
        }
        return if (loads > 0) true else null
    }

    /**
     * Dónde empiezan los datos de cada entrada sin comprimir: el directorio central dice dónde está su cabecera local, y la
     * cabecera, cuánto mide. Vacío si el zip no se puede leer así (zip64, comentario raro).
     */
    internal fun storedDataOffsets(file: Path): Map<String, Long> {
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val length = raf.length()
            val tail = minOf(length, 0xffffL + 22).toInt()
            val end = ByteArray(tail)
            raf.seek(length - tail)
            raf.readFully(end)
            val eocd = (tail - 22 downTo 0).firstOrNull { i -> le32(end, i) == 0x06054b50L } ?: return emptyMap()
            val entries = le16(end, eocd + 10)
            val size = le32(end, eocd + 12)
            val offset = le32(end, eocd + 16)
            if (entries == 0xffff || size == 0xffffffffL || offset == 0xffffffffL || offset + size > length) return emptyMap()

            val directory = ByteArray(size.toInt())
            raf.seek(offset)
            raf.readFully(directory)
            val result = mutableMapOf<String, Long>()
            var at = 0
            for (i in 0 until entries) {
                if (at + 46 > directory.size || le32(directory, at) != 0x02014b50L) break
                val method = le16(directory, at + 10)
                val nameLength = le16(directory, at + 28)
                val extraLength = le16(directory, at + 30)
                val commentLength = le16(directory, at + 32)
                val header = le32(directory, at + 42)
                val name = String(directory, at + 46, nameLength, Charsets.UTF_8)
                if (method == ZipEntry.STORED && header != 0xffffffffL && header + 30 <= length) {
                    val local = ByteArray(30)
                    raf.seek(header)
                    raf.readFully(local)
                    if (le32(local, 0) == 0x04034b50L) result[name] = header + 30 + le16(local, 26) + le16(local, 28)
                }
                at += 46 + nameLength + extraLength + commentLength
            }
            return result
        }
    }

    private fun le16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Long = le16(bytes, at).toLong() or (le16(bytes, at + 2).toLong() shl 16)
}
