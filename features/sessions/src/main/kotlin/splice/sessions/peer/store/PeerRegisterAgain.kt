// NEW: Oct 28, 2026 (Marcos: "add the ability for anyone or anything to register to send and receive messages via
// splice peer"): a program that registers by hand registers at every start, so a register for a peer already there
// answers that peer rather than refusing it.
package splice.sessions.peer.store

import splice.sessions.peer.codex.NativeProcess
import splice.sessions.peer.owner.PeerOwnerProcesses

/** What a register for a peer already there comes to: refused, or the peer file it leaves and how that changed. */
internal sealed class RegisterAgain {
    data class Refused(val answer: PeerRegistration.Refused) : RegisterAgain()

    data class Keep(val file: PeerFile, val outcome: RegisterOutcome) : RegisterAgain()
}

/**
 * A register for a peer already there, from the owner it has (none where it has none), or from a live process where
 * its owner's process ended, keeps the peer, its session id and its key, and takes the cwd, send scope and owner the
 * register asks for. A name a live process holds, or a peer with no owner asked for by a process, stays another's.
 */
internal class PeerRegisterAgain(private val processes: PeerOwnerProcesses = PeerOwnerProcesses()) {
    fun decide(existing: PeerFile, request: PeerRequest): RegisterAgain {
        val asked = request.owner?.let { PeerFileOwner(it.pid, it.procStart, it.pidDomain) }
        val held = existing.owner
        val passes = asked != null && ended(held)
        if (held != asked && !passes) {
            val whose = if (held == null) "it has no owner" else "a running process holds it"
            val why = "a peer named '${request.name}' already exists and $whose"
            return RegisterAgain.Refused(PeerRegistration.Refused(PeerRefusal.TAKEN, why))
        }
        val updated = existing.copy(cwd = request.cwd, scope = PeerFileScope(request.sendTo), owner = asked)
        return RegisterAgain.Keep(updated, if (updated == existing) RegisterOutcome.SAME else RegisterOutcome.UPDATED)
    }

    private fun ended(held: PeerFileOwner?): Boolean =
        held != null && !processes.live(NativeProcess(held.pid, held.procStart, held.pidDomain))
}
