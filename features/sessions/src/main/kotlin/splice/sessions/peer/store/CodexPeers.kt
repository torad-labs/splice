// NEW: Oct 27, 2026 (docs/peers.md, "A Codex thread as a peer"): what an attach does to the store. A codex peer is
// named `codex-<thread id>` and takes the thread's id as its session id, so a sender can name either. Its send scope is
// anyone. It does not count against the 16 peers a user registers by hand: a Codex thread attaches on its own each time
// its hooks run, so the store holds at most 64 of them. An attach that finds the peer already there brings its home,
// owner and working directory up to date and keeps its key; an attach that names no owner keeps the one it has.
package splice.sessions.peer.store

import splice.sessions.peer.PeerConfig
import splice.sessions.peer.codex.CodexBinding
import splice.sessions.peer.codex.CodexNames
import splice.sessions.peer.codex.CodexRequest
import splice.sessions.peer.codex.NativeProcess
import java.nio.file.Files
import java.nio.file.Path

// why: each codex peer is a resident helper JVM of about 48 MB, and this machine runs 40 to 50 seats at a time.
private const val MAX_CODEX_PEERS = 64

private const val ANYONE = "*"

/** The answer to an attach: the codex peer as it now stands, or why there is none. */
public sealed class CodexAttach {
    /** [fresh] when this attach made the peer or changed what its record says, so its helper starts anew. */
    public data class Attached(val config: PeerConfig, val keyFile: Path, val fresh: Boolean) : CodexAttach()

    public data class Refused(val why: PeerRefusal, val reason: String) : CodexAttach()
}

/** What an attach writes: a new peer.json, a changed one, nothing, or a refusal. */
internal sealed class CodexPlan {
    data class Create(val file: PeerFile) : CodexPlan()

    data class Update(val file: PeerFile) : CodexPlan()

    data class Same(val file: PeerFile) : CodexPlan()

    data class Refused(val why: PeerRefusal, val reason: String) : CodexPlan()
}

/** The rules of an attach, and the codex bindings peer.json files hold. */
internal class CodexPeers {
    private val names = CodexNames()

    fun name(request: CodexRequest): String = names.peer(request.binding.thread)

    fun isPeer(name: String): Boolean = names.isPeer(name)

    /** The peer.json an attach of [request] leaves, given the [current] one and the [registered] names. [newKey] is
     *  the sha256 of a key minted for a new peer. */
    fun plan(request: CodexRequest, current: PeerFile?, registered: Set<String>, newKey: PeerFileKey): CodexPlan {
        val refused = malformed(request)
        return when {
            refused != null -> CodexPlan.Refused(PeerRefusal.INVALID, refused)
            current != null -> update(current, request)
            registered.count(names::isPeer) >= MAX_CODEX_PEERS ->
                CodexPlan.Refused(PeerRefusal.TAKEN, "at most $MAX_CODEX_PEERS Codex threads may be peers at once")
            else -> CodexPlan.Create(created(request, newKey))
        }
    }

    fun binding(file: PeerFile): CodexBinding? = file.codex?.let { codex ->
        CodexBinding(codex.thread, codex.home, codex.owner?.let { NativeProcess(it.pid, it.procStart, it.pidDomain) })
    }

    private fun update(current: PeerFile, request: CodexRequest): CodexPlan {
        val next = current.copy(cwd = request.cwd, codex = codexOf(request, current.codex?.owner))
        return if (next == current) CodexPlan.Same(current) else CodexPlan.Update(next)
    }

    private fun created(request: CodexRequest, key: PeerFileKey): PeerFile = PeerFile(
        name = name(request),
        sessionId = request.binding.thread,
        createdAt = key.rotatedAt,
        cwd = request.cwd,
        key = key,
        scope = PeerFileScope(listOf(ANYONE)),
        codex = codexOf(request, null),
    )

    private fun codexOf(request: CodexRequest, kept: PeerFileOwner?): PeerFileCodex {
        val owner = request.binding.owner?.let { PeerFileOwner(it.pid, it.procStart, it.pidDomain) } ?: kept
        return PeerFileCodex(request.binding.thread, request.binding.home, owner)
    }

    private fun malformed(request: CodexRequest): String? {
        val home = Path.of(request.binding.home)
        val cwd = Path.of(request.cwd)
        return when {
            names.thread(request.binding.thread) != request.binding.thread -> "thread is a Codex thread id"
            !home.isAbsolute -> "home must be an absolute path"
            !cwd.isAbsolute || !Files.isDirectory(cwd) -> "cwd must be an absolute path to a directory"
            else -> null
        }
    }
}
