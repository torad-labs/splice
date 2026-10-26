package splice.app.diagnostics

import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.codemode.NativeCodeModeRuntime
import splice.codemode.WorkerSpawn
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonWire
import splice.daemonclient.DaemonSettings
import splice.provider.codex.diagnostics.CodeModeGatewayNativeProbe
import splice.provider.codex.diagnostics.CodeModeGatewayProbe
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Internal model-free runner; Main owns the only process entry and dispatches this explicit fixture mode. */
internal object CodeModeParityProbe {
    suspend fun run(args: Array<String>) {
        when (args.firstOrNull()) {
            "--installed-settings" -> installedSettings(args)
            "--current-core-fixture" -> {
                require(args.size == 2) { "Expected a current-core probe nonce" }
                CodeModeParityCore.run(args[1])
            }
            "--native-gateway-fixture" -> nativeGateway(args)
            "--format-fixture" -> {
                require(args.size == 2) { "Expected a formatter probe nonce" }
                CodeModeParityFormat.run(args[1])
            }
            "--gateway-fixture" -> {
                require(
                    args.size == GATEWAY_PROBE_ARGUMENTS,
                ) { "Expected gateway case, nonce, state directory and phase" }
                val details = args.drop(1)
                CodeModeParityText.write(
                    System.out,
                    JsonWire.string(
                        CodeModeGatewayProbe.run(details[0], details[1], Path.of(details[2]), details.last()),
                    ),
                )
            }
            else -> {
                require(args.size == 1) { "Expected the reference identity of the candidate native host" }
                runtime(Path.of(args.single())).use { runtime ->
                    coroutineScope { CodeModeParityProtocol(runtime, CodeModeParityFrames(), this).run() }
                }
            }
        }
    }

    private fun installedSettings(args: Array<String>) {
        require(args.size == 2) { "Expected an installed-settings probe nonce" }
        val settings = DaemonSettings(TerminalOutput { CodeModeParityText.write(System.err, it) })
        val env = EnvReader(System::getenv)
        val result = buildJsonObject {
            put("nonce", args[1])
            put("controlPort", settings.controlPort(env))
            put("supervisorUnit", settings.supervisorUnit(env))
        }
        CodeModeParityText.write(System.out, JsonWire.string(result))
    }

    private suspend fun nativeGateway(args: Array<String>) {
        require(args.size == GATEWAY_PROBE_ARGUMENTS) { "Expected native gateway case, nonce, state and host identity" }
        val details = args.drop(1)
        runtime(Path.of(details.last())).use { native ->
            val result = CodeModeGatewayNativeProbe.run(details[0], details[1], Path.of(details[2]), native)
            CodeModeParityText.write(System.out, JsonWire.string(result))
        }
    }

    private fun runtime(referenceHost: Path): NativeCodeModeRuntime {
        val expected = digest(referenceHost)
        val spawn = WorkerSpawn { builder ->
            check(digest(Path.of(builder.command().first())) == expected) { "Candidate native host identity differs" }
            builder.start().also { child ->
                CodeModeParityText.write(System.err, "{\"type\":\"parity/identity\",\"nativeHostPid\":${child.pid()}}")
            }
        }
        return NativeCodeModeRuntime(spawn = spawn)
    }

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            java.security.DigestInputStream(
                input,
                digest,
            ).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        }
        return java.util.HexFormat.of().formatHex(digest.digest())
    }
}

// One mode selector plus case, nonce, state path and phase (or expected host identity).
private const val GATEWAY_PROBE_ARGUMENTS = 5

internal const val CODE_MODE_PARITY_COMMAND = "--internal-code-mode-parity"
