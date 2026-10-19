package testigo.cli

import testigo.config.NetworkMode
import testigo.config.ProjectLocator
import testigo.config.RunPlan
import testigo.config.Source
import testigo.config.networkFor
import testigo.util.displayFrom

/**
 * El comando con flags que reproduce la corrida, para repetirla o copiarla a CI.
 * Siempre incluye perfil, app y flows; el resto, solo si cambia lo que daría el perfil.
 * Nunca incluye valores de `--env`: suelen ser credenciales.
 */
object EquivalentCommand {
    private val SAFE = Regex("[A-Za-z0-9_./:=,@%+-]+")

    fun build(plan: RunPlan): String {
        val cwd = plan.cwd
        val profile = plan.profile.value
        val args = mutableListOf("testigo", "run")

        plan.project.file?.let { file ->
            if (file != cwd.resolve(ProjectLocator.FILE_NAME).normalize()) {
                args += listOf("--config", file.displayFrom(cwd))
            }
        }
        args += listOf("--profile", profile.cliName)
        args += listOf("--app", plan.artifact.value.path.displayFrom(cwd))
        plan.flowRoots.value.forEach { args += listOf("--flows", it.displayFrom(cwd)) }
        if (plan.tags.isNotEmpty()) args += listOf("--tags", plan.tags.joinToString(","))
        if (plan.excludeTags.isNotEmpty()) args += listOf("--exclude-tags", plan.excludeTags.joinToString(","))

        if (plan.record.value != profile.record) args += if (plan.record.value) "--record" else "--no-record"
        if (plan.record.value && plan.videoBitrate.source == Source.FLAG) {
            args += listOf("--video-bitrate", plan.videoBitrate.value.toString())
        }
        if (plan.network.value != networkFor(profile, plan.project.config.network.capture).value) {
            args += listOf("--network", plan.network.value.cliName)
        }
        if (plan.network.value != NetworkMode.NONE && plan.networkFormat.source == Source.FLAG) {
            args += listOf("--network-format", plan.networkFormat.value.cliName)
        }
        if (plan.logs.value != profile.logs) args += if (plan.logs.value) "--logs" else "--no-logs"

        plan.device.value?.let { args += listOf("--device", it) }
        if (plan.env.fileSource == Source.FLAG) plan.env.file?.let { args += listOf("--env-file", it.displayFrom(cwd)) }
        if (plan.out.source == Source.FLAG) args += listOf("--out", plan.out.value.displayFrom(cwd))
        if (plan.ci) args += "--ci"
        if (plan.keepRaw.source == Source.FLAG) args += "--keep-raw"
        if (plan.redact.source == Source.FLAG) args += if (plan.redact.value) "--redact" else "--no-redact"
        if (plan.repeat > 1) args += listOf("--repeat", plan.repeat.toString())

        return args.joinToString(" ") { shellQuote(it) }
    }

    fun shellQuote(arg: String): String =
        if (arg.isNotEmpty() && SAFE.matches(arg)) arg else "'" + arg.replace("'", "'\\''") + "'"
}
