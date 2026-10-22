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
            Path.of(argument).takeIf(::isTempleScript)?.let { ScriptArgument(position, it) }
        }.singleOrNull()

    private fun isTempleScript(path: Path): Boolean =
        path.isAbsolute && isRegisteredTempleScript(path)

    private fun isRegisteredTempleScript(path: Path): Boolean =
        path.fileName?.toString() == "tools-server.ts" && Files.isRegularFile(path)

    /** The registered runtime remains opaque. Only its one anchored tools-server target is substituted. */
    private fun invocation(
        executable: String,
        arguments: List<String>,
        script: ScriptArgument,
        bridge: Path,
        endpoint: NativeCodexSocketEndpoint,
    ): List<String>? {
        if (script.position != arguments.lastIndex) return null
        val generation = endpoint.binding?.identity
        val bridgeArguments = listOf(
            bridge.toString(),
            endpoint.path.toString(),
            script.path.toString(),
            generation?.pid?.toString().orEmpty(),
            generation?.startTicks?.toString().orEmpty(),
            generation?.bootId.orEmpty(),
        )
        return listOf(executable) + arguments.take(script.position) + bridgeArguments
    }
}
