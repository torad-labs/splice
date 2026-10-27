// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 5): `splice peer`, the verbs that register, list, revoke and rotate
// peers, set their webhooks, and send and read as one of them. Every verb is a call on the daemon's control plane: the
// management verbs present the management key, and the `--as` verbs present the peer's own key from its file.
package splice.app.cli.peer

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.net.URLEncoder
import java.nio.file.Path

/** `splice peer <verb>`. [out] is stdout, [err] stderr, so `--json | jq` never reads a refusal. */
internal class PeerCommand(
    private val out: TerminalOutput = TerminalOutput(::println),
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val env: EnvReader = EnvReader(System::getenv),
    private val stdin: PeerStdin = PeerStdin { System.`in`.readBytes().decodeToString() },
) {
    private val call = PeerCall(env, err)
    private val reader = PeerReader(call, out, err)

    suspend fun run(args: List<String>): Int {
        val verb = args.firstOrNull().orEmpty()
        val rest = args.drop(1)
        val code = when (verb) {
            "register", "list", "revoke", "rotate", "webhook" -> manage(verb, rest)
            "send", "inbox", "status" -> asPeer(verb, rest)
            "codex" -> PeerCodexCommand(call, out, env).run(rest)
            else -> null
        }
        if (code == null) err.line("usage: splice peer $PEER_USAGE")
        return code ?: PeerExit.BAD_USAGE
    }

    /** The verbs that present the management key. */
    private fun manage(verb: String, rest: List<String>): Int? = when (verb) {
        "register" -> register(rest)
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

    private fun register(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf("--cwd", "--send-to"), emptySet()).parse(args)
        val name = parsed?.words?.singleOrNull() ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val body = buildJsonObject {
            put("name", name)
            parsed.values["--cwd"]?.let { put("cwd", Path.of(it).toAbsolutePath().normalize().toString()) }
            parsed.values["--send-to"]?.let { list ->
                val names = list.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                putJsonArray("sendTo") { names.forEach { add(JsonPrimitive(it)) } }
            }
        }
        return when (val answer = call.call("POST", "/api/peers", key, body)) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                val reply = answer.body
                out.line("registered ${JsonScalars.str(reply, "name")}")
                out.line("  session id  ${JsonScalars.str(reply, "sessionId")}")
                out.line("  key file    ${JsonScalars.str(reply, "keyFile")}")
                out.line("  socket      ${JsonScalars.str(reply, "socket") ?: "not yet: the peer is still starting"}")
                PeerExit.DONE
            }
        }
    }

    private fun list(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf("--json")).parse(args)?.takeIf { it.words.isEmpty() }
            ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        return when (val answer = call.call("GET", "/api/peers", key)) {
            is PeerAnswer.Exit -> answer.code
            is PeerAnswer.Ok -> {
                if ("--json" in parsed.switches) out.line(answer.body.toString()) else PeerLines(out).peers(answer.body)
                PeerExit.DONE
            }
        }
    }

    private fun revoke(args: List<String>): Int? {
        val parsed = PeerArgsParser(emptySet(), setOf("--purge")).parse(args)
        val name = parsed?.words?.singleOrNull() ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        val purge = "--purge" in parsed.switches
        return finish(call.call("DELETE", "/api/peers/${segment(name)}?purge=$purge", key)) { "revoked $name" }
    }

    private fun rotate(args: List<String>): Int? {
        val name = args.singleOrNull()?.takeUnless { it.startsWith("--") } ?: return null
        val key = call.managementKey() ?: return PeerExit.REFUSED
        return finish(call.call("POST", "/api/peers/${segment(name)}/rotate", key)) { JsonScalars.str(it, "keyFile") }
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
        return webhookVerb(form, "/api/peers/${segment(name)}/webhook", url, parsed.values["--bearer-file"])
    }

    private fun webhookVerb(form: String, path: String, url: String?, bearerFile: String?): Int? = when (form) {
        "set url" -> webhookSet(path, url.orEmpty(), bearerFile)
        "clear" -> withKey { key -> finish(call.call("DELETE", path, key)) { "cleared" } }
        "test" -> withKey { key ->
            finish(call.call("POST", "$path/test", key)) { reply -> PeerLines(out).webhookTest(reply) }
        }
        else -> null
    }

    private fun webhookSet(path: String, url: String, bearerFile: String?): Int = withKey { key ->
        val body = buildJsonObject {
            put("url", url)
            bearerFile?.let { put("bearerFile", Path.of(it).toAbsolutePath().normalize().toString()) }
        }
        finish(call.call("PUT", path, key, body)) { reply ->
            "${JsonScalars.str(reply, "url")}\n  secret file ${JsonScalars.str(reply, "secretFile")}"
        }
    }

    private fun send(args: List<String>): Int? {
        val parsed = PeerArgsParser(setOf("--as"), emptySet()).parse(args)
        val words = parsed?.words?.takeIf { it.size == 2 } ?: return null
        val text = if (words[1] == "-") stdin.read() else words[1]
        val key = call.peerKey(parsed.values["--as"]) ?: return PeerExit.REFUSED
        val body = buildJsonObject {
            put("to", words[0])
            put("text", JsonPrimitive(text))
        }
        return finish(call.call("POST", "/api/peers/self/send", key, body)) { JsonScalars.str(it, "msgId") }
    }

    private fun withKey(block: PeerKeyed): Int {
        val key = call.managementKey() ?: return PeerExit.REFUSED
        return block(key)
    }

    /** Prints [line]'s text for a 2xx and answers 0; otherwise the exit code the call already chose. */
    private fun finish(answer: PeerAnswer, line: PeerLine): Int = when (answer) {
        is PeerAnswer.Exit -> answer.code
        is PeerAnswer.Ok -> {
            line(answer.body)?.let { out.line(it) }
            PeerExit.DONE
        }
    }

    private fun segment(name: String): String = URLEncoder.encode(name, Charsets.UTF_8)
}
