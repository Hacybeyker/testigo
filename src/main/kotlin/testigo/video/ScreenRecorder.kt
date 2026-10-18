package testigo.video

import testigo.TestigoException
import testigo.process.Cleanup
import testigo.process.CommandRunner
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Graba la pantalla del dispositivo mientras corre un flow. */
interface Recorder {
    /** Vuelve cuando la grabación ya empezó. */
    fun start()

    /** Termina la grabación. Se puede llamar más de una vez. */
    fun stop()

    /** Trae los videos a [dir] y los borra del dispositivo. */
    fun pull(dir: Path): List<Path>
}

/**
 * `adb shell screenrecord` con la tasa de bits elegida. Maestro graba a ~1 Mbps y se ve borroso; a 4 Mbps se lee bien.
 * Como screenrecord corta a los 3 minutos en Android 13, graba en segmentos de [segmentSeconds]:
 * `video.mp4` si hubo uno solo, o `video-1.mp4`, `video-2.mp4`… si el flow fue más largo.
 *
 * El final del video. screenrecord solo recibe un cuadro cuando la pantalla cambia, y al recibir SIGINT cierra el
 * encoder sin vaciarlo: los últimos cuadros que el encoder todavía tenía adentro se pierden. Si la pantalla quedó
 * quieta (el resultado de un login, un assert), justo esos cuadros eran el final del flow. Por eso, antes de parar,
 * [stop] graba [tail] más y hace que la pantalla cambie unas veces (prende y apaga un instante la barra de "Ubicación
 * del puntero", y la deja como estaba): esos cuadros nuevos empujan los anteriores al video.
 */
class ScreenRecorder(
    private val adb: Path,
    private val serial: String,
    private val bitrateMbps: Int,
    private val runner: CommandRunner,
    private val segmentSeconds: Int = SEGMENT_SECONDS,
    /** Un segmento más corto que esto no llegó al límite de tiempo: screenrecord falló y no se reintenta. */
    private val minSegment: Duration = 5.seconds,
    /** Lo que se sigue grabando después del flow, para que entre lo último que cambió en pantalla. */
    private val tail: Duration = 1.seconds,
    /** Lo que se espera después de los cuadros de relleno, para que el encoder los procese. */
    private val settle: Duration = 500.milliseconds,
    private val startProcess: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    },
) : Recorder {
    private val remoteBase = "/sdcard/testigo-video-${UUID.randomUUID().toString().take(8)}"
    private val lock = Any()
    private val segments = mutableListOf<String>()
    private var current: Process? = null
    private var watcher: Thread? = null
    private var stopping = false
    private var stopped = false
    private val pointerKey = Any()

    override fun start() {
        val first = synchronized(lock) {
            check(current == null) { "la grabación ya empezó" }
            launchSegment()
        }
        // Si la corrida se corta, el video de la app no debe quedar en el almacenamiento del dispositivo.
        Cleanup.register(this) {
            stop(flush = false)
            deleteFromDevice()
        }
        waitUntilRecording(first)
        watcher = thread(isDaemon = true, name = "testigo-screenrecord") { keepRecording() }
    }

    override fun stop() = stop(flush = true)

    @Synchronized
    private fun stop(flush: Boolean) {
        if (stopped) return
        stopped = true
        val process = synchronized(lock) {
            stopping = true
            current
        } ?: return
        if (flush && process.isAlive) flushLastFrames()
        // SIGINT: screenrecord cierra el .mp4 bien. Matar el proceso de adb lo dejaría corrupto.
        runner.run(listOf(adb.toString(), "-s", serial, "shell", "pkill", "-INT", "screenrecord"), 15.seconds)
        if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        watcher?.join(5_000)
    }

    override fun pull(dir: Path): List<Path> {
        val remotes = synchronized(lock) { segments.toList() }
        if (remotes.isEmpty()) return emptyList()
        val videos = remotes.mapIndexedNotNull { index, remote ->
            val local = dir.resolve(if (remotes.size == 1) "video.mp4" else "video-${index + 1}.mp4")
            val result = runner.run(listOf(adb.toString(), "-s", serial, "pull", remote, local.toString()), 5.minutes)
            local.takeIf { result.ok && it.exists() }
        }
        deleteFromDevice()
        Cleanup.unregister(this)
        return videos
    }

    /**
     * Graba [tail] más y cambia la pantalla [FLUSH_CYCLES] veces con la barra de "Ubicación del puntero" (un ajuste de
     * desarrollador que no toca la app), para que el encoder entregue los cuadros del final. La barra queda como
     * estaba, también si la corrida se corta. Si el dispositivo no deja cambiar el ajuste, se sigue igual.
     */
    private fun flushLastFrames() {
        sleep(tail)
        try {
            val shell = listOf(adb.toString(), "-s", serial, "shell")
            val original = if (runner.run(shell + listOf("settings", "get", "system", POINTER_LOCATION), 10.seconds).stdout.trim() == "1") "1" else "0"
            val other = if (original == "1") "0" else "1"
            Cleanup.register(pointerKey) { runner.run(shell + listOf("settings", "put", "system", POINTER_LOCATION, original), 10.seconds) }
            val toggles = List(FLUSH_CYCLES) {
                "settings put system $POINTER_LOCATION $other; sleep $FLUSH_GAP; settings put system $POINTER_LOCATION $original; sleep $FLUSH_GAP"
            }.joinToString("; ")
            runner.run(shell + toggles, 20.seconds)
            Cleanup.unregister(pointerKey)
        } catch (e: Exception) {
            // Sin cuadros de relleno, el video puede perder lo último; no es motivo para fallar la corrida.
        }
        sleep(settle)
    }

    private fun sleep(duration: Duration) {
        if (duration.isPositive()) Thread.sleep(duration.inWholeMilliseconds)
    }

    private fun deleteFromDevice() {
        val remotes = synchronized(lock) { segments.toList() }
        if (remotes.isNotEmpty()) runner.run(listOf(adb.toString(), "-s", serial, "shell", "rm", "-f") + remotes, 30.seconds)
    }

    /** Cuando un segmento llega a su límite, empieza el siguiente, hasta que se pida parar. */
    private fun keepRecording() {
        while (true) {
            val (process, startedAt) = synchronized(lock) { current to System.nanoTime() }
            process ?: return
            process.waitFor()
            synchronized(lock) {
                if (stopping) return
                // Si screenrecord terminó enseguida, no es el límite de tiempo sino un error: no se reintenta.
                if (System.nanoTime() - startedAt < minSegment.inWholeNanoseconds) return
                launchSegment()
            }
        }
    }

    /** Llamar con [lock] tomado. */
    private fun launchSegment(): String {
        val remote = "$remoteBase-${segments.size + 1}.mp4"
        segments += remote
        current = startProcess(
            listOf(
                adb.toString(), "-s", serial, "shell", "screenrecord",
                "--bit-rate", "${bitrateMbps * 1_000_000}",
                "--time-limit", "$segmentSeconds",
                remote,
            ),
        )
        return remote
    }

    /** screenrecord crea el archivo apenas empieza a grabar: se espera a que exista. */
    private fun waitUntilRecording(remote: String) {
        repeat(50) {
            val process = synchronized(lock) { current }
            if (process == null || !process.isAlive) throw TestigoException("screenrecord terminó antes de empezar a grabar.")
            if (runner.run(listOf(adb.toString(), "-s", serial, "shell", "ls", remote), 5.seconds).ok) return
            Thread.sleep(100)
        }
        throw TestigoException("screenrecord no empezó a grabar en 5 s.")
    }

    companion object {
        /** Menos que los 180 s que permite screenrecord en Android 13. */
        const val SEGMENT_SECONDS = 170

        private const val POINTER_LOCATION = "pointer_location"

        /** Cada ciclo son 2 cambios de pantalla; los encoders de hardware retienen unos pocos cuadros. */
        private const val FLUSH_CYCLES = 3
        private const val FLUSH_GAP = "0.12"
    }
}
