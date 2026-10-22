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

    /** The registered runtime prefix and launch settings the bridge preserves while replacing only its script. */
    private data class RegisteredPrefix(
        val executable: String,
        val arguments: List<String>,
        val script: ScriptArgument,
        val cwd: Path?,
        val environment: Map<String, String>,
    )

    private data class RegisteredDirectory(val value: Path?)

    /** Fields must all be present before a registered bridge prefix is safe to reconstruct. */
    private data class RegisteredPrefixFields(
        val arguments: List<String>?,
        val script: ScriptArgument?,
        val executable: String?,
        val environment: Map<String, String>?,
        val directory: RegisteredDirectory?,
    ) {
        fun prefix(): RegisteredPrefix? = when {
            arguments == null -> null
            script == null -> null
            executable == null -> null
            environment == null -> null
            directory == null -> null
            else -> RegisteredPrefix(executable, arguments, script, directory.value, environment)
        }
    }

    fun create(
        server: JsonObject,
        bridge: Path,
        endpoint: NativeCodexSocketEndpoint,
    ): NativeCodexMcpRpc.LaunchContext? = try {
        command(server, bridge, endpoint)
    } catch (_: InvalidPathException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun command(
        server: JsonObject,
        bridge: Path,
        endpoint: NativeCodexSocketEndpoint,
    ): NativeCodexMcpRpc.LaunchContext? = registeredPrefix(server)?.let { prefix ->
        invocation(prefix, bridge, endpoint)
    }

    private fun registeredPrefix(server: JsonObject): RegisteredPrefix? {
        val arguments = arguments(server)
        return RegisteredPrefixFields(
            arguments = arguments,
            script = arguments?.let(::scriptArgument),
            executable = JsonScalars.str(server, "command"),
            environment = environment(server),
            directory = cwd(server),
        ).prefix()
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
        prefix: RegisteredPrefix,
        bridge: Path,
        endpoint: NativeCodexSocketEndpoint,
    ): NativeCodexMcpRpc.LaunchContext? = prefix.takeIf { it.script.position == it.arguments.lastIndex }?.let {
        val generation = endpoint.binding?.identity
        val bridgeArguments = listOf(
            bridge.toString(),
            endpoint.path.toString(),
            it.script.path.toString(),
            generation?.pid?.toString().orEmpty(),
            generation?.startTicks?.toString().orEmpty(),
            generation?.bootId.orEmpty(),
        )
        NativeCodexMcpRpc.LaunchContext(
            command = listOf(it.executable) + it.arguments.take(it.script.position) + bridgeArguments,
            cwd = it.cwd,
            environment = it.environment,
        )
    }

    private fun cwd(server: JsonObject): RegisteredDirectory? =
        if ("cwd" !in server) {
            RegisteredDirectory(null)
        } else {
            JsonScalars.str(server, "cwd")?.let(Path::of)?.takeIf { it.isAbsolute }?.let(::RegisteredDirectory)
        }

    private fun environment(server: JsonObject): Map<String, String>? {
        val values = server["env"] as? JsonObject ?: return null
        val environment = values.mapNotNull { (name, value) -> JsonScalars.str(value)?.let { name to it } }
        return environment.takeIf { it.size == values.size }?.toMap()
    }
}
