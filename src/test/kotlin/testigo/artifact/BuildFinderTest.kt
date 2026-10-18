package testigo.artifact

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

class BuildFinderTest {

    @TempDir
    lateinit var tmp: Path

    private fun build(relative: String, hoursAgo: Long): Path {
        val path = tmp.resolve(relative)
        path.parent.createDirectories()
        path.writeText("x")
        path.setLastModifiedTime(FileTime.from(Instant.parse("2026-10-16T20:00:00Z").minusSeconds(hoursAgo * 3600)))
        return path
    }

    @Test
    fun `encuentra los builds del repo de al lado y del propio, del más nuevo al más viejo`() {
        val apk = build("proyectos/MiApp/app/build/outputs/apk/debug/app-debug.apk", hoursAgo = 5)
        val aab = build("proyectos/MiApp/app/build/outputs/bundle/qaDebug/app-qa-debug.aab", hoursAgo = 1)
        build("proyectos/MiApp/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", hoursAgo = 1)
        build("proyectos/MiApp/app/build/intermediates/apk/debug/app-debug.apk", hoursAgo = 0)
        build("proyectos/MiApp/node_modules/x/build/outputs/apk/a.apk", hoursAgo = 0)
        val inner = build("proyectos/MiApp-e2e/demo/build/outputs/apk/debug/demo.apk", hoursAgo = 3)

        val found = BuildFinder.near(tmp.resolve("proyectos/MiApp-e2e"), home = tmp)

        assertEquals(listOf(aab, inner, apk), found)
    }

    @Test
    fun `detecta Chucker por sus clases internas, no por la variante no-op`() {
        fun apk(name: String, dex: String): Path {
            val path = tmp.resolve(name)
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                zip.putNextEntry(ZipEntry("classes2.dex"))
                zip.write("dex\n035\u0000...$dex...".toByteArray())
                zip.closeEntry()
            }
            return path
        }

        assertEquals(true, ChuckerDetector.detect(apk("qa.apk", "Lcom/chuckerteam/chucker/internal/ui/MainActivity;")))
        assertEquals(false, ChuckerDetector.detect(apk("release.apk", "Lcom/chuckerteam/chucker/api/ChuckerInterceptor;")))
        assertEquals(null, ChuckerDetector.detect(tmp.resolve("qa.apk").resolveSibling("roto.apk").also { it.writeText("no es un zip") }))
    }
}
