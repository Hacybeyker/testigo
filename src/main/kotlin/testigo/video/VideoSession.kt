package testigo.video

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import testigo.maestro.FlowFile
import testigo.process.Cleanup
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

data class VideoOutcome(val files: List<Path>, val note: String?)

/**
 * Video de un flow. Maestro corre un wrapper temporal junto al flow cuyos hooks avisan al runner por HTTP local:
 * `onFlowStart` → empieza a grabar (responde cuando ya graba) y `onFlowComplete` → para, también si el flow falla.
 * Así el video no incluye los ~10 s en que Maestro instala y conecta su driver.
 */
class VideoSession(private val recorder: Recorder, flow: FlowFile, appId: String) : AutoCloseable {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile
    private var started = false

    @Volatile
    private var error: String? = null

    @Volatile
    private var stopper: Thread? = null

    /** El archivo que se le pasa a `maestro test` en lugar del flow. */
    val wrapper: Path

    init {
        server.createContext("/start") { exchange ->
            respond(exchange) {
                recorder.start()
                started = true
            }
        }
        // Responde enseguida y para en segundo plano: cerrar el .mp4 puede tardar más que el timeout de Maestro.
        server.createContext("/stop") { exchange ->
            respond(exchange) { stopper = thread(name = "testigo-stop-recording") { recorder.stop() } }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        wrapper = flow.path.resolveSibling(".testigo-video-${flow.path.fileName}")
        wrapper.writeText(
            VideoWrapper.content(
                appId = flow.header.appId ?: appId,
                name = flow.header.name ?: flow.path.fileName.toString().substringBeforeLast('.'),
                flowFile = flow.path.fileName.toString(),
                startUrl = "$base/start?token=$token",
                stopUrl = "$base/stop?token=$token",
            ),
        )
        // Si la corrida se corta (Ctrl+C), el wrapper no debe quedar junto al flow.
        Cleanup.register(this) {
            server.stop(0)
            wrapper.deleteIfExists()
        }
    }

    /** Después de que Maestro terminó: para la grabación si seguía y trae los videos a [dir]. */
    fun finish(dir: Path): VideoOutcome {
        stopper?.join()
        if (!started) {
            return VideoOutcome(emptyList(), error?.let { "no se grabó video: $it" } ?: "no se grabó video: el flow no llegó a empezar")
        }
        recorder.stop()
        val files = recorder.pull(dir)
        return VideoOutcome(files, if (files.isEmpty()) "no se pudo traer el video del dispositivo" else null)
    }

    override fun close() {
        server.stop(0)
        wrapper.deleteIfExists()
        Cleanup.unregister(this)
    }

    private fun respond(exchange: HttpExchange, action: () -> Unit) {
        try {
            val (code, body) = when {
                exchange.requestURI.query != "token=$token" -> 403 to "token inválido"
                else -> try {
                    action()
                    200 to "ok"
                } catch (e: Exception) {
                    error = e.message
                    500 to (e.message ?: "error")
                }
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            exchange.close()
        }
    }
}

/** El flow temporal que envuelve al real con los hooks de video. Va oculto (`.testigo-video-…`) junto al flow. */
internal object VideoWrapper {
    fun content(appId: String, name: String, flowFile: String, startUrl: String, stopUrl: String): String = """
        |# Generado por testigo para grabar video. Se borra al terminar el flow.
        |appId: ${quote(appId)}
        |name: ${quote(name)}
        |onFlowStart:
        |  - evalScript: ${'$'}{http.get('$startUrl')}
        |onFlowComplete:
        |  - evalScript: ${'$'}{http.get('$stopUrl')}
        |---
        |- runFlow: ${quote(flowFile)}
        |
    """.trimMargin()

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
