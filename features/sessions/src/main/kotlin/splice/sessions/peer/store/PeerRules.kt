// NEW: Oct 27, 2026: what a register may ask for (docs/specs/peer-messaging.md 2.1), moved out of PeerStore.kt unchanged
// when the codex peer joined the store, so the store's file holds the store and this holds the rules it applies.
package splice.sessions.peer.store

import java.nio.file.Files
import java.nio.file.Path

// why: docs/specs/peer-messaging.md 2.1 caps a name at 32 characters, lowercase, starting with a letter, so it is a
// safe file name, a safe envelope attribute and a safe registry name.
internal const val NAME_PATTERN = "^[a-z][a-z0-9-]{1,31}$"

// why: a sendTo entry is a name or a session id (a uuid); both fit this, and it keeps quotes and brackets out.
private const val SEND_TO_ENTRY_PATTERN = "^[A-Za-z0-9-]{1,64}$"

// why: the spec's peers-per-user limit (3.4). Since Oct 28, 2026 one host process serves every peer, each under an
// anchor of about 1 MB, so the limit no longer stands for memory; it stays as the spec states it.
private const val MAX_PEERS = 16

internal const val ANY_TARGET = "*"

/** What a register may ask for (2.1): the name, the cwd and the send scope, then whether the name is free. */
internal class PeerRules {
    private val namePattern = Regex(NAME_PATTERN)
    private val sendToPattern = Regex(SEND_TO_ENTRY_PATTERN)

    fun wellFormed(name: String): Boolean = namePattern.matches(name)

    /** Why [request] can never succeed, or null. */
    fun malformed(request: PeerRequest): PeerRegistration.Refused? {
        val cwd = Path.of(request.cwd)
        val reason = when {
            !namePattern.matches(request.name) -> "a peer name is a lowercase letter, then 1 to 31 of a-z, 0-9 or -"
            !cwd.isAbsolute || !Files.isDirectory(cwd) -> "cwd must be an absolute path to a directory"
            !validSendTo(request.sendTo) -> "sendTo is [\"*\"] or a list of names and session ids"
            else -> null
        }
        return reason?.let { PeerRegistration.Refused(PeerRefusal.INVALID, it) }
    }

    /** Why [request]'s name is not free: reserved, registered, a running session's, or over the peer limit. */
    fun taken(
        request: PeerRequest,
        liveNames: Set<String>,
        registered: Set<String>,
        folder: PeerFolder,
    ): PeerRegistration.Refused? {
        val name = request.name
        val reason = when {
            name in RESERVED.names -> "'$name' is reserved"
            name in registered || Files.exists(folder.dir(name)) -> "a peer named '$name' already exists"
            name in liveNames -> "a running session is named '$name'"
            registered.size >= MAX_PEERS -> "a user may have at most $MAX_PEERS peers"
            else -> null
        }
        return reason?.let { PeerRegistration.Refused(PeerRefusal.TAKEN, it) }
    }

    private fun validSendTo(sendTo: List<String>): Boolean =
        sendTo.isNotEmpty() && (sendTo == listOf(ANY_TARGET) || sendTo.all { sendToPattern.matches(it) })

    /** The names no peer may take (2.1): roles a reader could mistake for a person or for Splice itself. */
    private object RESERVED {
        val names: Set<String> = setOf(
            "system", "user", "operator", "human", "assistant", "claude", "anthropic", "splice", "splice-console",
            "telegram", "president", "marcos", "dispatch",
        )
    }
}
