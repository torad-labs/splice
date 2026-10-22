package splice.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Rebuilds only registered Bun launches whose script is the anchored Temple tools server. */
internal class NativeCodexBridgeCommand {
    private data class ScriptArgument(val position: Int, val path: Path)

    fun create(server: JsonObject, bridge: Path, endpoint: NativeCodexSocketEndpoint): List<String>? = try {
        command(server, bridge, endpoint)
    } catch (_: InvalidPathException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun command(server: JsonObject, bridge: Path, endpoint: NativeCodexSocketEndpoint): List<String>? {
        val executable = JsonScalars.str(server, "command")
        val arguments = arguments(server)
        val script = arguments?.let(::scriptArgument)
        return executable?.let { command ->
            arguments?.let { values ->
                script?.let { source -> invocation(command, values, source, bridge, endpoint) }
            }
        }
    }

    private fun arguments(server: JsonObject): List<String>? {
        val values = server["args"] as? JsonArray ?: return null
        val arguments = values.mapNotNull(JsonScalars::str)
        return arguments.takeIf { it.size == values.size }
    }

    private fun scriptArgument(arguments: List<String>): ScriptArgument? =
        arguments.mapIndexedNotNull { position, argument ->
            val path = Path.of(argument)
            if (isTempleScript(path)) {
                ScriptArgument(position, path)
            } else {
                null
            }
        }.singleOrNull()

    private fun isTempleScript(path: Path): Boolean =
        path.isAbsolute && isRegisteredTempleScript(path)

    private fun isRegisteredTempleScript(path: Path): Boolean =
        path.fileName?.toString() == "tools-server.ts" && Files.isRegularFile(path)

    private fun invocation(
        executable: String,
        arguments: List<String>,
        script: ScriptArgument,
        bridge: Path,
        endpoint: NativeCodexSocketEndpoint,
    ): List<String>? {
        val executableName = executable.substringAfterLast('/')
        val generation = endpoint.binding?.identity
        val bridgeArguments = listOf(
            bridge.toString(),
            endpoint.path.toString(),
            generation?.pid?.toString().orEmpty(),
            generation?.startTicks?.toString().orEmpty(),
            generation?.bootId.orEmpty(),
        )
        return when {
            directBun(executableName, arguments, script) -> listOf(executable) + bridgeArguments
            isHostshieldLaunch(executableName, arguments, script) ->
                listOf(executable) + arguments.take(script.position) + bridgeArguments
            else -> null
        }
    }

    private fun directBun(
        executableName: String,
        arguments: List<String>,
        script: ScriptArgument,
    ): Boolean = executableName == "bun" && script.position == 0 && arguments.size == 1

    private fun isHostshieldLaunch(
        executableName: String,
        arguments: List<String>,
        script: ScriptArgument,
    ): Boolean =
        executableName == "hostshield" && isHostshieldRuntime(arguments, script)

    /** Retained HostShield commands protect Bun with a runtime/hash preamble, then pass Bun's flags after `--`. */
    private fun isHostshieldRuntime(arguments: List<String>, script: ScriptArgument): Boolean {
        val preamble = listOf("bun", "--hostshield-runtime", "", "--hostshield-sha256", "")
        if (script.position != arguments.lastIndex || arguments.indexOf("--") != preamble.size) return false
        return arguments.take(preamble.size).zip(preamble).all { (actual, expected) ->
            if (expected.isEmpty()) actual.isNotBlank() else actual == expected
        } && arguments[preamble.lastIndex].matches(Regex("[0-9a-fA-F]{64}"))
    }
}
