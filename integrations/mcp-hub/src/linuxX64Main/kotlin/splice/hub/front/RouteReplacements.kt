package splice.hub.front

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RouteReplacements(
    private val known: Map<String, Route>,
    private val daemon: Daemon,
    private val timers: Timers,
    private val clock: Clock,
) {
    private val candidateReplies = CandidateReplyParser()

    /** Starts the candidate that the daemon derived from its current canonical configured server state. */
    fun start(
        route: Route,
        oldSocket: String,
        oldGeneration: String,
        then: (ReplacementCandidate?, String?) -> Unit,
    ) {
        val extra = buildJsonObject {
            put(OLD_SOCKET, oldSocket)
            put(OLD_GENERATION, oldGeneration)
        }
        ask(route, REPLACEMENT_START, clock.now() + START_BUDGET, extra) { status, reply ->
            val result = candidateReplies.parse(status, reply, oldSocket)
            then(result.value, result.refusal)
        }
    }

    /** Revalidates all aliases and launches still held by [oldSocket] before any front route can move. */
    fun commit(
        route: Route,
        oldSocket: String,
        oldGeneration: String,
        candidate: ReplacementCandidate,
        then: (String?) -> Unit,
    ) {
        ask(
            route,
            REPLACEMENT_COMMIT,
            clock.now() + START_BUDGET,
            body(oldSocket, oldGeneration, candidate),
        ) { status, reply ->
            completion(status, reply, "the candidate no longer matches the canonical replacement", then)
        }
    }

    /** Moves every route sharing the old exact generation, and returns exactly the routes that later rollback may restore. */
    fun replace(route: Route, oldSocket: String, oldGeneration: String, candidate: ReplacementCandidate): Set<String>? {
        if (!matches(route, oldSocket, oldGeneration, candidate)) return null
        if (known.values.any { held -> differentGeneration(held, oldSocket, oldGeneration) }) return null
        val moved = known.values.filter { held -> held.socket == oldSocket && held.generation == oldGeneration }.toSet()
        moved.forEach { held ->
            held.socket = candidate.socket
            held.generation = candidate.generation
            held.listing = null
        }
        // The caller persists these routes together with the drain's moved keys and ROUTED phase.
        return moved.map(Route::key).toSet()
    }

    /** Restores only the route keys this replacement moved, never unrelated aliases already using the candidate. */
    fun restore(
        moved: Set<String>,
        oldSocket: String,
        oldGeneration: String,
        candidate: ReplacementCandidate,
    ): Boolean {
        val affected = moved.map { known[it] ?: return false }
        if (affected.any { held -> noLongerCandidate(held, candidate) }) return false
        affected.forEach { held ->
            held.socket = oldSocket
            held.generation = oldGeneration
            held.listing = null
        }
        // The caller removes the rolled-back drain in the same persisted snapshot.
        return true
    }

    fun retire(
        route: Route,
        oldSocket: String,
        oldGeneration: String,
        candidate: ReplacementCandidate,
        then: (String?) -> Unit,
    ) {
        ask(
            route,
            REPLACEMENT_RETIRE,
            clock.now() + START_BUDGET,
            body(oldSocket, oldGeneration, candidate),
        ) { status, reply ->
            completion(status, reply, "the daemon did not retire the exact old holder", then)
        }
    }

    fun rollback(
        route: Route,
        oldSocket: String,
        oldGeneration: String,
        candidate: ReplacementCandidate,
        then: (String?) -> Unit,
    ) {
        ask(
            route,
            REPLACEMENT_ROLLBACK,
            clock.now() + START_BUDGET,
            body(oldSocket, oldGeneration, candidate),
        ) { status, reply ->
            completion(status, reply, "the daemon could not confirm safe replacement rollback", then)
        }
    }

    /** Repeats ownership checks until the daemon confirms this exact holder generation exited or no relay id needs it. */
    fun confirmExit(socket: String, generation: String, pending: () -> Boolean, then: () -> Unit) {
        if (!pending()) return
        val body = Rpc.encode(
            buildJsonObject {
                put(SOCKET, socket)
                put(GENERATION, generation)
            },
        )
        daemon.call(REPLACEMENT_EXIT, body) { status, _ ->
            if (status in OK..NO_CONTENT) {
                then()
            } else {
                timers.at(clock.now() + EXIT_RETRY) { confirmExit(socket, generation, pending, then) }
            }
        }
    }

    private fun matches(
        route: Route,
        oldSocket: String,
        oldGeneration: String,
        candidate: ReplacementCandidate,
    ): Boolean = listOf(
        route.socket == oldSocket,
        route.generation == oldGeneration,
        route.shared == candidate.shared,
    ).all { it }

    private fun differentGeneration(route: Route, oldSocket: String, oldGeneration: String): Boolean =
        route.socket == oldSocket && route.generation != oldGeneration

    private fun noLongerCandidate(route: Route, candidate: ReplacementCandidate): Boolean =
        route.socket != candidate.socket || route.generation != candidate.generation

    private fun completion(status: Int, reply: String, fallback: String, then: (String?) -> Unit) {
        if (status in OK..NO_CONTENT) {
            then(null)
        } else {
            then(Rpc.daemonWords(reply) ?: fallback)
        }
    }

    private fun body(oldSocket: String, oldGeneration: String, candidate: ReplacementCandidate): JsonObject =
        buildJsonObject {
            put(OLD_SOCKET, oldSocket)
            put(OLD_GENERATION, oldGeneration)
            put(CANDIDATE_SOCKET, candidate.socket)
            put(CANDIDATE_TUPLE, candidate.tuple)
            put(CANDIDATE_GENERATION, candidate.generation)
        }

    private fun ask(
        route: Route,
        path: String,
        until: Long,
        extra: JsonObject,
        then: (Int, String) -> Unit,
    ) {
        val body = buildJsonObject {
            put(SERVER, route.server)
            put(LAUNCH, route.launch)
            extra.forEach { (key, value) -> put(key, value) }
        }
        daemon.call(path, Rpc.encode(body)) { status, text ->
            val unreachable = status == 0 || status == UNAVAILABLE
            if (unreachable && clock.now() < until) {
                timers.at(clock.now() + RETRY) { ask(route, path, until, extra, then) }
            } else {
                then(status, text)
            }
        }
    }
}

private class CandidateReplyParser {
    fun parse(status: Int, body: String, oldSocket: String): CandidateReply =
        if (status == OK) {
            configured(body, oldSocket)
        } else {
            CandidateReply(null, refusal(status, body))
        }

    private fun configured(body: String, oldSocket: String): CandidateReply {
        val candidate = record(body).candidate()
        return when {
            candidate == null -> CandidateReply(
                null,
                "the daemon did not return a complete canonical replacement generation",
            )
            candidate.socket == oldSocket -> CandidateReply(
                null,
                "the canonical replacement resolves to the old holder socket",
            )
            else -> CandidateReply(candidate, null)
        }
    }

    private fun refusal(status: Int, body: String): String =
        if (status == NOT_FOUND) {
            "the running daemon has no replacement API; install the source support before retrying"
        } else {
            Rpc.daemonWords(body) ?: "the daemon did not start a replacement"
        }

    private fun record(body: String): CandidateRecord {
        val parsed = JsonText.objectOrNull(body)
        return CandidateRecord(
            Rpc.str(parsed, SOCKET),
            Rpc.str(parsed, TUPLE),
            Rpc.str(parsed, GENERATION),
            shared(parsed),
        )
    }

    private fun shared(body: JsonObject?): Boolean? = when (Rpc.str(body, SHARED)) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private data class CandidateReply(
    val value: ReplacementCandidate?,
    val refusal: String?,
)

private data class CandidateRecord(
    val socket: String?,
    val tuple: String?,
    val generation: String?,
    val shared: Boolean?,
) {
    fun candidate(): ReplacementCandidate? = if (complete()) {
        ReplacementCandidate(
            requireNotNull(socket),
            requireNotNull(tuple),
            requireNotNull(generation),
            requireNotNull(shared),
        )
    } else {
        null
    }

    private fun complete(): Boolean = listOf(socket, tuple, generation, shared).none { it == null }
}
