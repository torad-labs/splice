// NEW: `splice listeners` and `splice capabilities` — what a socket manager needs to hold splice's ports for it. The
// inventory is the boot parse's own answer (the same findings pass `check-config` runs, then the same control-port
// resolution the daemon uses), so a second list of ports never exists beside it. Read-only: nothing is bound, written
// or started.
package splice.app.cli

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.listen.ListenerNames
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.TopologyRefusal
import splice.daemonclient.DaemonSettings
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/** Every address splice listens on is loopback; a socket manager binds exactly this and nothing wider. */
private const val LISTEN_BIND = "127.0.0.1"

internal class ListenerInventory(
    private val env: EnvReader,
    private val out: TerminalOutput = TerminalOutput(::println),
    private val errors: TerminalOutput = TerminalOutput { System.err.println(it) },
) {
    private val names = ListenerNames()

    /** 0 with the JSON on stdout; [CONFIG_REFUSED_EXIT] with the findings on stderr when boot would refuse. */
    fun print(): Int {
        val path = TopologyLoader.configPath(env)
        val text = try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            null
        } catch (failure: java.io.IOException) {
            errors.line("splice: splice.toml cannot be read: ${SafeFailureText.render(failure)}")
            return CONFIG_REFUSED_EXIT
        }
        val topology = if (text == null) {
            null
        } else {
            when (val read = ConfigFindings.read(text, path.toAbsolutePath().parent ?: path)) {
                is ConfigRead.Ready -> read.topology
                is ConfigRead.Refused -> {
                    errors.line("splice: ${SafeFailureText.render(TopologyRefusal(read.findings))}")
                    return CONFIG_REFUSED_EXIT
                }
            }
        }
        val control = DaemonSettings(errors).controlPort(topology, env)
        out.line(
            buildJsonObject {
                put(
                    "listeners",
                    buildJsonArray {
                        add(row("control", null, names.control, control))
                        topology?.heads?.forEach { (key, head) -> add(row("head", key, names.head(key), head.port)) }
                    },
                )
            }.toString(),
        )
        return 0
    }

    private fun row(role: String, head: String?, name: String, port: Int) = buildJsonObject {
        put("role", role)
        if (head != null) put("head", head)
        put("name", name)
        put("bind", LISTEN_BIND)
        put("port", port)
    }
}

/** What this jar can do about inherited sockets, so a manager asks instead of guessing from a version. */
internal class CapabilityReport(private val out: TerminalOutput = TerminalOutput(::println)) {
    fun print(): Int {
        out.line(
            """{"socket_activation":{"adopt_inherited":true,"protocol":1,"matches_by":"LISTEN_FDNAMES",""" +
                """"control_required":true,"missing_head":"self-bind",""" +
                """"refuses":["extra","duplicate","malformed","wrong_endpoint","non_listening",""" +
                """"native_unavailable"]}}""",
        )
        return 0
    }
}
