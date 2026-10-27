// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): a native Codex thread on the messaging registry. A
// codex peer is a peer named `codex-<thread id>` whose session id is the thread's id. Its helper hands each message it
// receives to the thread through that Codex home's app-server, as a turn, and journals how each hand-over ended. These
// are the shapes the store, the helper, the routes and the CLI share.
package splice.sessions.peer.codex

private const val CODEX_PEER_PREFIX = "codex-"

private val THREAD_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** A live process as Claude Code's registry names one: its pid, its kernel start and the pid domain it runs in. */
public data class NativeProcess(val pid: Long, val procStart: String, val pidDomain: String)

/** The thread a codex peer stands for: its id, the CODEX_HOME whose app-server runs it, and the Codex process that
 *  hosts its lifecycle when the attach found one among its own ancestors. */
public data class CodexBinding(val thread: String, val home: String, val owner: NativeProcess?)

/** What an attach asks for: the thread with its home and owner, and the working directory the peer's record shows. */
public data class CodexRequest(val binding: CodexBinding, val cwd: String)

/** How handing one message to the thread ended. */
public sealed class TurnStart {
    /** Codex took the message, as a new turn or into the one running, which [turnId] names. */
    public data class Started(val turnId: String) : TurnStart()

    /** Codex answered with an error, so the message is not in the thread. */
    public data class Refused(val reason: String) : TurnStart()

    /** The request went out and no answer came back, so whether Codex took the message is not known. */
    public data class Unknown(val reason: String) : TurnStart()

    /** The request never went out, because the connection had already closed. */
    public data class NotSent(val reason: String) : TurnStart()
}

/** The thread, as a codex peer's helper reaches it through the app-server. */
public interface CodexThread {
    /** Hands [text] to the thread as user input sent under [clientId]. */
    public suspend fun startTurn(clientId: String, text: String): TurnStart

    /** Whether the thread's history holds a user message sent under [clientId]; null when it could not be read. */
    public suspend fun holds(clientId: String): Boolean?
}

/** The names of codex peers: `codex-` and the thread id, lowercase, which is how Codex spells a thread id. */
public class CodexNames {
    /** The canonical thread id in [text], or null when it is not a thread id. */
    public fun thread(text: String): String? = text.trim().lowercase().takeIf { THREAD_ID.matches(it) }

    public fun peer(thread: String): String = CODEX_PEER_PREFIX + thread

    /** Whether [name] is a codex peer's: `codex-` and a thread id. */
    public fun isPeer(name: String): Boolean =
        name.startsWith(CODEX_PEER_PREFIX) && THREAD_ID.matches(name.removePrefix(CODEX_PEER_PREFIX))
}
