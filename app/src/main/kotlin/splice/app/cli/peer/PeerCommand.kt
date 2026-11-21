// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 5): `splice peer`, the verbs that register, list, revoke and rotate
// peers, set their webhooks, and send and read as one of them. Every verb is a call on the daemon's control plane: the
// management verbs present the management key, and the `--as` verbs present the peer's own key from its file.
package splice.app.cli.peer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.app.cli.hook.HarnessHook
import splice.app.cli.hook.StreamDevice
import splice.app.cli.hook.StreamTerminal
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.nio.file.Path

/** `splice peer <verb>`. [out] is stdout, [err] stderr, so `--json | jq` never reads a refusal. */
internal class PeerCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
    private val stdin: PeerStdin = PeerStdin { System.`in`.readBytes().decodeToString() },
    private val terminal: StreamTerminal = StreamDevice(),
) {
    private val call = PeerCall(env, err)
    private val ask = PeerAsk(call, out)
    private val reader = PeerReader(call, out, err)
    private val hooks = HarnessHook(stdin::read, terminal, err)

    suspend fun run(args: List<String>): Int {
        val verb = args.firstOrNull().orEmpty()
        val rest = args.drop(1)
        val code = when (verb) {
            "register", "list", "revoke", "rotate", "webhook" -> manage(verb, rest)
            "send", "inbox", "status" -> asPeer(verb, rest)
            "codex" -> PeerCodexCommand(call, out, env, hooks).run(rest)
            "limit" -> PeerLimitCommand(call, out).run(rest)
            else -> null
        }
        if (code == null) err.line("usage: splice peer $PEER_USAGE")
        return code ?: PeerExit.BAD_USAGE
    }

    /** The verbs that present the management key. */
    private fun manage(verb: String, rest: List<String>): Int? = when (verb) {
        "register" -> PeerRegisterVerb(call, out, err).run(rest)
        "list" -> list(rest)
        "revoke" -> revoke(rest)
        "rotate" -> rotate(rest)
        else -> webhook(rest)
    }

    /** The `--as` verbs, which present the peer's own key. */
    private suspend fun asPeer(verb: String, rest: List<String>): Int? = when (verb) {
        "send" -> send(rest)
        "inbox" -> reader.inbox(rest)
        else -> reader.status(rest)
    }

    private fun list(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf("--json")).parse(args)?.takeIf { it.words.isEmpty() }
            ?: return null
        // The peers are printed a line each as they are read, which leaves the call no line of its own to print.
        return ask.managed(ControlRequest("GET", "/api/peers"), "--json" in parsed.switches) { reply ->
            PeerLines(out).peers(reply)
            null
        }
    }

    private fun revoke(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf("--purge")).parse(args)
        val name = parsed?.words?.singleOrNull() ?: return null
        val purge = "--purge" in parsed.switches
        return ask.managed(ControlRequest("DELETE", "/api/peers/${ask.segment(name)}?purge=$purge")) { "revoked $name" }
    }

    private fun rotate(args: List<String>): Int? {
        val name = args.singleOrNull()?.takeUnless { it.startsWith("--") } ?: return null
        val rotated = ControlRequest("POST", "/api/peers/${ask.segment(name)}/rotate")
        return ask.managed(rotated) { JsonScalars.str(it, "keyFile") }
    }

    /** `webhook set <name> <url> [--bearer-file F]`, `webhook clear <name>` and `webhook test <name>`. */
    private fun webhook(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf("--bearer-file"), emptySet()).parse(args)
        val words = parsed?.words.orEmpty()
        val name = words.getOrNull(1)
        if (parsed == null || name == null) return null
        val url = words.drop(2).singleOrNull()
        // A set takes one URL and may take a bearer file; a clear or a test takes the name alone.
        val form = when {
            url != null -> "${words[0]} url"
            words.size == 2 && parsed.values.isEmpty() -> words[0]
            else -> ""
        }
        return webhookVerb(form, "/api/peers/${ask.segment(name)}/webhook", url, parsed.values["--bearer-file"])
    }

    private fun webhookVerb(form: String, path: String, url: String?, bearerFile: String?): Int? = when (form) {
        "set url" -> webhookSet(path, url.orEmpty(), bearerFile)
        "clear" -> ask.managed(ControlRequest("DELETE", path)) { "cleared" }
        "test" -> ask.managed(ControlRequest("POST", "$path/test")) { reply -> PeerLines(out).webhookTest(reply) }
        else -> null
    }

    private fun webhookSet(path: String, url: String, bearerFile: String?): Int {
        val body = buildJsonObject {
            put("url", url)
            bearerFile?.let { put("bearerFile", Path.of(it).toAbsolutePath().normalize().toString()) }
        }
        return ask.managed(ControlRequest("PUT", path, body)) { reply ->
            "${JsonScalars.str(reply, "url")}\n  secret file ${JsonScalars.str(reply, "secretFile")}"
        }
    }

    /** `send --as <name> [--id ID] [--pid P [--proc-start S]] <to> <text|->`: an id makes the send idempotent, and a
     *  pid pins it to the process its target was resolved to. */
    private fun send(args: List<String>): Int? {
        val parsed = PeerArgsParser(PeerSendFlags.VALUED, emptySet()).parse(args)
        val words = parsed?.words?.takeIf { it.size == 2 && PeerSendFlags.valid(parsed.values) } ?: return null
        val text = if (words[1] == "-") stdin.read() else words[1]
        val key = call.peerKey(parsed.values["--as"]) ?: return PeerExit.REFUSED
        val body = PeerSendFlags.body(words[0], text, parsed.values)
        return ask.finish(call.call("POST", "/api/peers/self/send", key, body)) { JsonScalars.str(it, "msgId") }
    }
}
