package splice.app.cli.mod

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.peer.ControlRequest
import splice.app.cli.peer.PeerAsk
import splice.app.cli.peer.PeerCall
import splice.app.cli.peer.PeerExit
import splice.app.mods.MOD_CATALOG_PATH
import splice.app.mods.ModNames
import splice.app.mods.install.ModBun
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal const val MOD_USAGE =
    "install <folder|tarball> [--link] | list | remove <name> | enable <name> [--accept] | disable <name>"

internal class ModCommand(
    env: EnvReader = EnvReader(System::getenv),
    out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
) {
    private val ask = PeerAsk(
        PeerCall(
            env,
            TerminalOutput { message ->
                err.line(message.map { if (it.isISOControl() && it != '\n') ' ' else it }.joinToString(""))
            },
            "mod",
        ),
        out,
    )
    private val bun = ModBun(env)
    private val lines = ModLines()

    fun run(args: List<String>): Int {
        val tail = args.drop(1)
        val result = when (args.firstOrNull()) {
            "install" -> install(tail)
            "list" -> list(tail)
            "remove" -> remove(tail)
            "enable" -> switch(tail, enabled = true)
            "disable" -> switch(tail, enabled = false)
            else -> null
        }
        if (result == null) err.line("usage: splice mod $MOD_USAGE")
        return result ?: PeerExit.BAD_USAGE
    }

    private fun install(args: List<String>): Int? {
        val target = target(args, "--link") ?: return null
        val source = try {
            Path.of(target).toAbsolutePath()
        } catch (_: InvalidPathException) {
            err.line("splice mod: give a valid path to a mod folder or tarball")
            return PeerExit.BAD_USAGE
        }
        val body = buildJsonObject {
            put("source", source.toString())
            if ("--link" in args) put("link", true)
            bun.atInstall()?.let { put("bunPath", it.toString()) }
        }
        return ask.managed(ControlRequest("POST", MOD_CATALOG_PATH, body), line = lines::install)
    }

    private fun list(args: List<String>): Int? = if (args.isEmpty()) {
        ask.managed(ControlRequest("GET", MOD_CATALOG_PATH), line = lines::list)
    } else {
        null
    }

    private fun remove(args: List<String>): Int? {
        val name = args.singleOrNull()?.takeIf(ModNames::isModName) ?: return null
        return ask.managed(ControlRequest("DELETE", "$MOD_CATALOG_PATH/$name"), line = lines::remove)
    }

    private fun switch(args: List<String>, enabled: Boolean): Int? {
        val name = target(args, "--accept".takeIf { enabled })?.takeIf(ModNames::isModName) ?: return null
        val accept = "--accept" in args
        val body = buildJsonObject {
            put("enabled", enabled)
            if (accept) put("accept", true)
        }
        return ask.managed(ControlRequest("PUT", "$MOD_CATALOG_PATH/$name", body)) {
            lines.switch(it, enabled, accept)
        }
    }

    private fun target(args: List<String>, flag: String?): String? {
        if (args.size !in 1..2) return null
        if (args.size == 2 && flag !in args) return null
        return args.filterNot { it == flag }.singleOrNull()?.takeUnless { it.startsWith('-') }
    }
}
