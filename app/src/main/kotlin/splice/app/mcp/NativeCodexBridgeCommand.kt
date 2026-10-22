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

    private val hostshieldRuntimePrefix = listOf("bun", "--hostshield-runtime")

    fun create(server: JsonObject, bridge: Path, socket: Path): List<String>? = try {
        command(server, bridge, socket)
    } catch (_: InvalidPathException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun command(server: JsonObject, bridge: Path, socket: Path): List<String>? {
        val executable = JsonScalars.str(server, "command")
        val arguments = arguments(server)
        val script = arguments?.let(::scriptArgument)
        return executable?.let { command ->
            arguments?.let { values ->
                script?.let { source -> invocation(command, values, source, bridge, socket) }
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
        socket: Path,
    ): List<String>? {
        val executableName = executable.substringAfterLast('/')
        return when {
            directBun(executableName, arguments, script) ->
                listOf(executable, bridge.toString(), socket.toString(), script.path.toString())
            isHostshieldLaunch(executableName, arguments, script) ->
                listOf(executable) + arguments.take(script.position) +
                    listOf(bridge.toString(), socket.toString(), script.path.toString())
            else -> null
        }
    }

    private fun directBun(
        executableName: String,
        arguments: List<String>,
        script: ScriptArgument,
    ): Boolean = executableName == "bun" && arguments.take(script.position).isEmpty()

    private fun isHostshieldLaunch(
        executableName: String,
        arguments: List<String>,
        script: ScriptArgument,
    ): Boolean =
        executableName == "hostshield" && isHostshieldRuntime(arguments, script)

    private fun isHostshieldRuntime(arguments: List<String>, script: ScriptArgument): Boolean =
        script.position == hostshieldRuntimePrefix.size &&
            arguments.take(hostshieldRuntimePrefix.size) == hostshieldRuntimePrefix
}
