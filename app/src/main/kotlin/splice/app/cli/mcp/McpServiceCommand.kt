package splice.app.cli.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.app.cli.AdminSupport
import splice.client.mcp.TempleMcpEntry
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import splice.core.wire.HttpStatus
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.MgmtKeyRead
import splice.daemonclient.PostReading
import splice.topology.TopologyStatePaths
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal const val MCP_SERVICE_USAGE =
    "install temple --script FILE --url URL [--command COMMAND] [--codex-home DIR]"
private const val BACKUP_HASH_CHARS = 12
private const val SERVICE_REFRESH_MS = 90_000

/** Persists the already-resolved Temple entry, rather than teaching Splice a machine-specific checkout path.
 *  Temple calls this once while provisioning; the registration survives both processes and every reboot. */
internal class McpServiceCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    private data class Requested(val script: Path, val url: String, val command: String, val codexHome: Path?)

    fun run(args: List<String>): Int {
        val wanted = parse(args)
        if (wanted == null) {
            err.line("usage: splice mcp $MCP_SERVICE_USAGE")
            return 2
        }
        return try {
            val home = AdminSupport.home()
            val source = TempleMcpEntry(home, env, TopologyStatePaths(env, home).current())
            persist(source, wanted)
            refresh()
        } catch (failure: java.io.IOException) {
            err.line("Temple MCP was not registered (${failure::class.simpleName}); no credential values were printed.")
            1
        }
    }

    private fun parse(args: List<String>): Requested? {
        if (args.take(2) != listOf("install", "temple")) return null
        val pairs = args.drop(2).chunked(2)
        val flags = pairs.filter { it.size == 2 }.associate { it[0] to it[1] }
        val allowed = setOf("--script", "--url", "--command", "--codex-home")
        val malformed = pairs.any { it.size != 2 } || flags.size != pairs.size
        if (malformed || !allowed.containsAll(flags.keys)) return null
        return requested(flags)
    }

    private fun requested(flags: Map<String, String>): Requested? {
        val script = flags["--script"]?.let(PathProbe::spelled)?.toAbsolutePath()?.normalize()
        val url = flags["--url"]?.takeIf(::validUrl)
        val native = flags["--codex-home"]?.let(PathProbe::spelled)?.toAbsolutePath()?.normalize()
        val badNative = "--codex-home" in flags && native == null
        val missing = script == null || url == null
        val unusable = script?.let { !Files.isRegularFile(it) } == true || badNative
        val badCommand = flags["--command"]?.isBlank() == true
        val invalid = unusable || badCommand
        return if (missing || invalid) {
            null
        } else {
            Requested(checkNotNull(script), checkNotNull(url), flags["--command"] ?: "bun", native)
        }
    }

    private fun validUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    } catch (_: java.net.URISyntaxException) {
        false
    }

    private fun persist(source: TempleMcpEntry, wanted: Requested) {
        val previous = source.registration()
        val native = wanted.codexHome?.toString() ?: JsonScalars.str(previous, "codex_home")
            ?: env("TEMPLE_CODEX_HOME") ?: env("CODEX_HOME")
        val body = buildJsonObject {
            putJsonObject("server") {
                put("command", wanted.command)
                put("args", JsonArray(listOf(JsonPrimitive(wanted.script.toString()))))
                putJsonObject("env") { put("TEMPLE_URL", wanted.url) }
            }
            native?.let { put("codex_home", it) }
        }
        if (previous == body) return
        val file = source.registrationFile
        Files.createDirectories(file.parent)
        if (Files.exists(file)) backup(file)
        SecureFile.writeAtomic0600(file, body.toString())
    }

    private fun refresh(): Int {
        val key = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key
        val read = key?.let {
            val url = "http://127.0.0.1:${AdminSupport.controlPort(env)}/api/mcp/services/refresh"
            ControlPlaneClient.post(url, it, "{}", SERVICE_REFRESH_MS)
        }
        return when {
            read == null || read == PostReading.NotConnected -> {
                out.line("Temple MCP registered durably. Splice will start it at its next daemon boot.")
                0
            }
            read is PostReading.Answered && read.reply.status == HttpStatus.OK -> {
                out.line("Temple MCP registered and its client entries refreshed through Splice.")
                0
            }
            else -> {
                err.line("Temple MCP is registered, but the live refresh did not complete. Retry the same install.")
                1
            }
        }
    }

    private fun backup(file: Path) {
        val old = Files.readString(file)
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(old.toByteArray()))
            .take(BACKUP_HASH_CHARS)
        val saved = file.resolveSibling("${file.fileName}.bak-$hash")
        if (!Files.exists(saved)) SecureFile.writeAtomic0600(saved, old)
    }
}
