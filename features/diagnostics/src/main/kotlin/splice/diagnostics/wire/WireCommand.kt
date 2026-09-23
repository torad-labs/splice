// NEW: V4-173 — `splice wire <head> [--last N] [--json]`: the upstream request bodies a head sent,
// exactly as it sent them. The operator's question (2026-09-20): "we're a proxy man, how aren't we
// able to check the system prompt from the requests?" — until this row nothing kept a body past
// its round. The head keeps them only when its operator opted in ([heads.KEY.overrides] wireTap =
// N), in memory, and serves them on its own port under the management key (GET /wire), so this
// verb needs the daemon up and the key file readable, unlike `splice logs`. Each record prints one
// header line and then the body string verbatim; --json prints the head's payload as served.
//
// A diagnostics slice since LAYOUT-01: the operator's read of what a head sent. It reaches the head
// through integrations/daemon-client (the management key, the loopback GET) and the head's port
// through integrations/topology; the lines leave through TerminalOutput.
package splice.diagnostics.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.terminal.BOLD
import splice.core.terminal.DIM
import splice.core.terminal.RESET
import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.SafeFailureText
import splice.daemonclient.ControlPlaneClient
import splice.daemonclient.ControlReply
import splice.daemonclient.MgmtKeyFile
import splice.daemonclient.MgmtKeyRead
import splice.topology.TopologyLoader
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Files
import java.time.Instant

// why: the 5s budget JdkAddHttp gave this verb before it read through the daemon client; the ring of
// bodies a head serves can be large, and ControlPlaneClient's 3s default was sized for shutdown answers.
private const val WIRE_READ_TIMEOUT_MS = 5_000

private const val WIRE_USAGE = "usage: splice wire <head> [--last N] [--json]"

internal data class WireOpts(val head: String, val last: Int, val json: Boolean)

private data class WireTarget(val port: Int, val key: String)

/** The one network seam of `splice wire`: a request to the head's own port under the management key,
 *  or null when nothing answers. The method rides along so a test pins the whole request. */
public fun interface WireFetch {
    public fun request(method: String, url: String, bearer: String): ControlReply?
}

/** `splice wire`. [output] is stdout, [errors] stderr: `--json | jq` must never read a refusal. */
public class WireCommand(
    private val output: TerminalOutput,
    private val errors: TerminalOutput,
    private val http: WireFetch = WireFetch { method, url, bearer ->
        ControlPlaneClient.send(url, method, bearer, readTimeoutMs = WIRE_READ_TIMEOUT_MS)
    },
) {
    private val json = Json { ignoreUnknownKeys = true }

    public fun wire(args: List<String>, envReader: EnvReader): Boolean {
        val opts = parseWireArgs(args)
            ?: return fail("unknown or malformed arguments ${args.joinToString(" ")}\n$WIRE_USAGE")
        val target = target(opts.head, envReader) ?: return false
        val url = "http://127.0.0.1:${target.port}/wire?last=${opts.last}"
        return report(opts, target.port, http.request("GET", url, target.key))
    }

    /** Where to ask and what to present: the head's port from the topology, the management key. */
    private fun target(head: String, envReader: EnvReader): WireTarget? {
        val port = headPort(head, envReader) ?: return null
        val key = mgmtKey(envReader) ?: return null
        return WireTarget(port, key)
    }

    /** The management key, or null with the reason printed: the route takes nothing else. */
    private fun mgmtKey(envReader: EnvReader): String? = when (val read = MgmtKeyFile().read(envReader)) {
        is MgmtKeyRead.Present -> read.key
        MgmtKeyRead.Absent -> fail("no management key yet — the daemon mints it on first launch").let { null }
        is MgmtKeyRead.Unreadable -> fail("management key unreadable: ${read.reason}").let { null }
    }

    /** What the head answered, in the head's own words when it refused. */
    private fun report(opts: WireOpts, port: Int, reply: ControlReply?): Boolean = when (reply?.status) {
        null -> fail("head ${opts.head} is not answering on :$port — is the daemon running? (splice status)")
        HttpURLConnection.HTTP_OK -> printPayload(reply.body, opts.json)
        HttpURLConnection.HTTP_NOT_FOUND -> fail(JsonScalars.str(parse(reply.body), "error") ?: reply.body)
        else -> fail("head ${opts.head} answered ${reply.status}: ${reply.body}")
    }

    /** The head's port from the topology, read-only; null with the reason printed when it cannot be. */
    private fun headPort(head: String, envReader: EnvReader): Int? {
        val path = TopologyLoader.configPath(envReader)
        val topology = try {
            TopologyLoader.parse(Files.readString(path))
        } catch (unreadable: IOException) {
            return fail("cannot read $path: ${SafeFailureText.render(unreadable)}").let { null }
        }
        val cfg = topology.heads[head]
        if (cfg == null) {
            fail("no head named '$head' in $path — heads: ${topology.heads.keys.joinToString(", ")}")
            return null
        }
        return cfg.port
    }

    private fun printPayload(body: String, raw: Boolean): Boolean {
        if (raw) {
            output.line(body)
            return true
        }
        val payload = parse(body) ?: return fail("head answered something that is not the wire payload: $body")
        val records = payload["records"] as? JsonArray ?: JsonArray(emptyList())
        val keep = JsonScalars.strOrEmpty(payload["keep"])
        val head = JsonScalars.strOrEmpty(payload["key"])
        output.line(
            "${BOLD}splice wire $head$RESET $DIM— ${records.size}/$keep kept upstream bodies, oldest first$RESET",
        )
        if (records.isEmpty()) output.line("  ${DIM}no upstream request since the daemon started$RESET")
        records.forEach { record -> printRecord(record.jsonObject) }
        return true
    }

    private fun printRecord(record: JsonObject) {
        val ts = JsonScalars.long(record, "ts")?.let { Instant.ofEpochMilli(it) } ?: "?"
        val session = JsonScalars.str(record, "session") ?: "-"
        val compact = if (JsonScalars.str(record, "compact") == "true") " compact" else ""
        val body = JsonScalars.strOrEmpty(record["body"])
        output.line("")
        val model = JsonScalars.strOrEmpty(record["model"])
        output.line("$BOLD── $ts$RESET  session=$session  model=$model$compact  ${DIM}${body.length} bytes$RESET")
        output.line(body)
    }

    private fun parse(text: String): JsonObject? =
        // ast-grep-ignore: kt-no-silent-result-collapse -- V4-173: callers print a non-JSON body verbatim
        Cancellables.runCatchingCancellable { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun fail(message: String): Boolean {
        errors.line("splice wire: $message")
        return false
    }

    internal fun parseWireArgs(args: List<String>): WireOpts? {
        var head: String? = null
        var last = 0
        var json = false
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--json" -> json = true
                "--last" -> {
                    last = args.getOrNull(i + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                    i += 1
                }
                else -> if (head == null && !arg.startsWith("-")) head = arg else return null
            }
            i += 1
        }
        return head?.let { WireOpts(it, last, json) }
    }
}
