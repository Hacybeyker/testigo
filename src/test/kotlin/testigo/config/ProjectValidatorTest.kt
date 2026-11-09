package testigo.config

import testigo.TestigoException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProjectValidatorTest {

    private fun config(seconds: Int?) = ProjectConfig(defaults = DefaultsConfig(flowTimeoutS = seconds))

    @Test
    fun `flow_timeout_s se acepta dentro de los limites`() {
        ProjectValidator.validate(config(null), "testigo.yaml")
        ProjectValidator.validate(config(FlowTimeout.MIN_S), "testigo.yaml")
        ProjectValidator.validate(config(FlowTimeout.MAX_S), "testigo.yaml")
    }

    @Test
    fun `flow_timeout_s fuera de los limites es un error que dice el rango`() {
        listOf(0, 29, 3601).forEach { seconds ->
            val e = assertThrows<TestigoException> { ProjectValidator.validate(config(seconds), "testigo.yaml") }
            assertTrue("defaults.flow_timeout_s debe estar entre 30 y 3600" in e.message!!, e.message)
        }
    }
}
