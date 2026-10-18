package testigo.artifact

import testigo.TestigoException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class ArtifactInspectorTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `lee package, version y debuggable de aapt2 dump badging`() {
        val badging = """
            package: name='com.empresa.app.qa' versionCode='70' versionName='7.0.0-qa' platformBuildVersionName='16' compileSdkVersion='36'
            sdkVersion:'24'
            targetSdkVersion:'36'
            application-label:'MiApp QA'
            application-debuggable
        """.trimIndent()

        assertEquals(ManifestInfo("com.empresa.app.qa", 70, "7.0.0-qa", true), ManifestParsers.badging(badging))
        assertNull(ManifestParsers.badging("ERROR: dump failed"))
    }

    @Test
    fun `lee el manifest de un AAB`() {
        val xml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                android:versionCode="12"
                android:versionName="1.2.0"
                package="com.empresa.app">
              <application android:debuggable="true" android:label="App"/>
            </manifest>
        """.trimIndent()

        assertEquals(ManifestInfo("com.empresa.app", 12, "1.2.0", true), ManifestParsers.manifestXml(xml))
    }

    @Test
    fun `el hash identifica el archivo`() {
        val file = tmp.resolve("app.apk")
        file.writeText("hola")

        assertEquals("b221d9dbb083a7f33428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb79", ArtifactInspector.sha256(file))
    }

    @Test
    fun `ubica bundletool en tools de la distribucion o en gradle downloads`() {
        val appHome = tmp.resolve("testigo")
        val runnerJar = appHome.resolve("lib").createDirectories().resolve("testigo.jar").createFile()
        val jar = appHome.resolve("tools").createDirectories().resolve("bundletool-all-1.18.3.jar").createFile()

        assertEquals(jar, Bundletool.locate(property = null, env = null, codeLocation = runnerJar))

        val project = tmp.resolve("proyecto")
        val classes = project.resolve("build/classes/kotlin/main").createDirectories()
        val downloaded = project.resolve(".gradle/downloads").createDirectories().resolve("bundletool-all-1.18.3.jar").createFile()

        assertEquals(downloaded, Bundletool.locate(property = null, env = null, codeLocation = classes))
        assertThrows(TestigoException::class.java) { Bundletool.locate(property = null, env = null, codeLocation = tmp.resolve("otro")) }
    }
}
