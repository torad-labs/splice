package splice.hub.front

import kotlinx.serialization.json.JsonObject

/** The stable front seam for connecting, restoring, and reattaching holder links. */
internal class HolderLinks(
    context: HolderLinkContext,
    private val known: MutableMap<String, Holder>,
    replacements: HolderReplacements,
    persistence: HolderPersistence,
) {
    private val proof = HolderProof(context.routes)
    private val memory = HolderMemory(known, context.clock, proof)
    private val exits = HolderExitMarks(known, persistence)
    private val recovery = HolderLinkRecovery(context, known, replacements, persistence, memory, proof, exits::mark)

    var stopping: Boolean
        get() = recovery.stopping
        set(value) {
            recovery.stopping = value
        }

    fun connectCandidate(holder: Holder) {
        recovery.connectCandidate(holder)
    }

    /** Reconnects only the exact retained generation and cached initialize result. */
    fun reconnect(holder: Holder, generation: String): Boolean = recovery.reconnect(holder, generation)

    fun of(route: Route): Holder? = route.socket?.let(known::get)

    /** Records a daemon-confirmed exit only while this registry still owns the captured exact generation. */
    fun markExited(holder: Holder, generation: String): HolderExitMark = exits.mark(holder, generation)

    fun adopt(fd: Int, socket: String, saved: ByteArray) {
        recovery.adopt(fd, socket, saved)
    }

    fun knownInit(route: Route, then: (JsonObject?, String?) -> Unit) {
        recovery.knownInit(route, then)
    }

    fun live(route: Route, then: (String?) -> Unit) {
        recovery.live(route, then)
    }

    fun touch(sockets: Set<String>) {
        memory.touch(sockets)
    }

    fun restore(saved: JsonObject?) {
        memory.restore(saved)
    }

    fun state(): JsonObject = memory.state()
}
