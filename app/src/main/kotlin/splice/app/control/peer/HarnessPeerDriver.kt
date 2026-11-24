package splice.app.control.peer

import io.ktor.http.HttpStatusCode
import splice.core.harness.HarnessPeerConfig
import splice.core.harness.HarnessPeerProtocol
import splice.sessions.note.PeerTarget
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerConfig
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.PeerSubmission
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus
import splice.upstream.codemode.ProcessDispatchers

/** The delivery boundary after the common principal, target, pin, scope and rate checks. */
internal fun interface HarnessPeerDriver {
    suspend fun deliver(message: HarnessPeerMessage): SendOutcome
}

internal data class HarnessPeerMessage(
    val target: SessionRecord,
    val text: String,
    val sender: PeerSender,
    val id: String?,
    val local: LocalPeerBinding? = null,
    val inbox: SessionRecord? = null,
)

internal data class HarnessPeerResolution(val target: PeerTarget, val local: LocalPeerBinding? = null)

internal data class LocalPeerBinding(val config: HarnessPeerConfig, val owner: NativeProcess)

internal class HarnessPeerDrivers(private val parts: PeerParts) {
    private val targets = PeerTargets(parts.registry)
    private val processes = PeerOwnerProcesses()
    private val native = NativeSocketPeerDriver(parts.outbox)
    private val acp = AcpPeerDriver(native)
    private val codex = CodexAppServerPeerDriver(native)
    private val fallback = LocalPeerDelivery(ProcessDispatchers().io())

    fun resolve(query: String): HarnessPeerResolution {
        val peer = parts.store.get(query) ?: parts.store.list().singleOrNull { it.sessionId == query }
        if (peer == null) return HarnessPeerResolution(targets.resolve(query))
        val owner = parts.store.nativeOwners[peer.name]
        if (owner != null && !processes.live(owner)) {
            return HarnessPeerResolution(PeerTarget.NotRunning("the peer owner has exited"))
        }
        return registeredTarget(peer, owner, query)
    }

    private fun registeredTarget(peer: PeerConfig, owner: NativeProcess?, query: String): HarnessPeerResolution {
        val harness = parts.store.harnesses[peer.name]
        val config = config(harness)
        val target = when {
            harness != null && config?.autoSubscribe != true -> PeerTarget.NotRunning("the harness peer is not enabled")
            config != null && localDriver(config) -> return localTarget(peer, config, owner)
            peer.name in parts.store.nativeInboxes -> inboxTarget(peer.name)
            else -> targets.resolve(query)
        }
        return HarnessPeerResolution(target)
    }

    private fun inboxTarget(name: String): PeerTarget {
        val inbox = ownerRecord(name) ?: return PeerTarget.NoSocket("the peer's harness has no live inbox")
        val resolved = targets.resolve(inbox.sessionId.orEmpty())
        if (resolved !is PeerTarget.One) return resolved
        return if (resolved.record.process == inbox.process) {
            PeerTarget.One(inbox.copy(name = name))
        } else {
            PeerTarget.NotRunning("the peer's session moved")
        }
    }

    fun ownerRecord(name: String): SessionRecord? {
        val owner = parts.store.nativeOwners[name]?.takeIf(processes::live) ?: return null
        val legacy = name !in parts.store.harnesses
        return parts.registry.read().singleOrNull {
            it.availability != SessionAvailability.GONE && it.process.pid == owner.pid &&
                (it.process.procStart == owner.procStart || (legacy && it.process.procStart == null))
        }
    }

    suspend fun deliver(message: HarnessPeerMessage): SendOutcome =
        message.local?.let { deliverLocal(message, it) } ?: deliverSocket(message)

    private suspend fun deliverSocket(message: HarnessPeerMessage): SendOutcome {
        val peer = message.target.name?.let(parts.store::get) ?: return native.deliver(message)
        val owner = parts.store.nativeOwners[peer.name]
        if (owner != null && !processes.live(owner)) return SendOutcome.Failed("the peer owner has exited")
        return dispatch(message, peer)
    }

    private suspend fun dispatch(message: HarnessPeerMessage, peer: PeerConfig): SendOutcome {
        val config = config(parts.store.harnesses[peer.name])
        return when {
            config?.driver == HarnessPeerProtocol.ACP -> acp.deliver(message.copy(inbox = ownerRecord(peer.name)))
            message.target.sessionId != peer.sessionId -> native.deliver(message)
            peer.name in parts.store.codexThreads -> codex.deliver(message)
            else -> native.deliver(message)
        }
    }

    private suspend fun deliverLocal(message: HarnessPeerMessage, binding: LocalPeerBinding): SendOutcome {
        val name = message.target.name.orEmpty()
        val sameSession = parts.store.get(name)?.sessionId == message.target.sessionId
        val config = config(parts.store.harnesses[name])
        val owner = parts.store.nativeOwners[name]
        val current = if (config != null && owner != null) LocalPeerBinding(config, owner) else null
        return if (sameSession && current == binding) {
            fallback.deliver(message)
        } else {
            SendOutcome.Refused(HttpStatusCode.Conflict.value, "the target is no longer the resolved peer")
        }
    }

    private fun config(harness: String?): HarnessPeerConfig? =
        parts.harnesses.entries.firstOrNull { it.wire == harness }?.peer

    private fun localDriver(config: HarnessPeerConfig?): Boolean =
        config?.driver == HarnessPeerProtocol.COMMAND || config?.driver == HarnessPeerProtocol.WEBHOOK

    private fun localTarget(
        peer: PeerConfig,
        config: HarnessPeerConfig,
        owner: NativeProcess?,
    ): HarnessPeerResolution {
        if (owner == null) return HarnessPeerResolution(PeerTarget.NotRunning("the peer has no live harness owner"))
        val record = SessionRecord(
            peer.sessionId,
            peer.name,
            SessionStatus(),
            SessionRoute.Unknown,
            SessionAvailability.LIVE,
            SessionProcess(owner.pid, peer.cwd, null, null, null, procStart = owner.procStart),
            SessionClient(null, null),
        )
        return HarnessPeerResolution(PeerTarget.One(record), LocalPeerBinding(config, owner))
    }
}

private class NativeSocketPeerDriver(private val outbox: PeerOutbox?) : HarnessPeerDriver {
    override suspend fun deliver(message: HarnessPeerMessage): SendOutcome =
        outbox?.send(message.target, message.text, message.sender, message.id)
            ?: SendOutcome.Failed("peer messaging is not available in this build")
}

private class AcpPeerDriver(private val socket: HarnessPeerDriver) : HarnessPeerDriver {
    override suspend fun deliver(message: HarnessPeerMessage): SendOutcome {
        val inbox = message.inbox
        val sameSession = inbox?.sessionId == message.target.sessionId
        return if (sameSession && inbox?.process?.sameAs(message.target.process) == true) {
            socket.deliver(message)
        } else {
            SendOutcome.Failed("the ACP session no longer owns its inbox")
        }
    }
}

/** The socket feeds Codex's existing journal and startTurn consumer; a socket write is not a started turn. */
private class CodexAppServerPeerDriver(private val socket: HarnessPeerDriver) : HarnessPeerDriver {
    override suspend fun deliver(message: HarnessPeerMessage): SendOutcome {
        val outcome = socket.deliver(message)
        return if (outcome is SendOutcome.Submitted) outcome.copy(submission = PeerSubmission.QUEUED) else outcome
    }
}
