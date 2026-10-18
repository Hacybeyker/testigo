package testigo.config

import testigo.TestigoException
import testigo.artifact.ArtifactFormat

/** Reglas que el parser de YAML no puede validar solo. Reporta todos los problemas juntos. */
object ProjectValidator {

    fun validate(config: ProjectConfig, origin: String) {
        val problems = buildList {
            if (config.version != ProjectConfig.SUPPORTED_VERSION) {
                add("version: ${config.version} no está soportada; usa version: ${ProjectConfig.SUPPORTED_VERSION}")
            }
            config.app.artifact?.let { artifact ->
                if (ArtifactFormat.of(artifact) == null) add("app.artifact debe terminar en .apk o .aab: $artifact")
            }
            if (config.app.packageName.isBlank()) add("app.package no puede estar vacío (usa auto)")
            config.app.signing?.let { signing ->
                if (signing.passwordEnv.isBlank()) add("app.signing.password_env no puede estar vacío")
            }
            if (config.flows?.isBlank() == true) add("flows no puede estar vacío")
            config.video.bitrateMbps?.let { mbps ->
                if (mbps !in VideoConfig.MIN_MBPS..VideoConfig.MAX_MBPS) {
                    add("video.bitrate_mbps debe estar entre ${VideoConfig.MIN_MBPS} y ${VideoConfig.MAX_MBPS}: $mbps")
                }
            }
            if (config.alerts.slowMs <= 0) add("alerts.slow_ms debe ser mayor que 0")
            if (config.redact.headers.any { it.isBlank() }) add("redact.headers no puede tener valores vacíos")
            if (config.redact.jsonKeys.any { it.isBlank() }) add("redact.json_keys no puede tener valores vacíos")
            if (config.evidence.dir?.isBlank() == true) add("evidence.dir no puede estar vacío")
            runCatching { Regex(config.evidence.ticketPattern) }.onFailure {
                add("evidence.ticket_pattern no es una expresión regular válida")
            }
        }
        if (problems.isNotEmpty()) {
            throw TestigoException("$origin tiene errores:\n" + problems.joinToString("\n") { "  - $it" })
        }
    }
}
