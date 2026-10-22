package splice.control.mcp.hub

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.McpPlan
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpShareClass
import splice.client.mcp.McpSharing
import splice.control.mcp.APP_MCP_SLICE
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpHandshakeBudget
import splice.control.mcp.McpResult
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import java.nio.file.Path

private const val HTTP_NO_CONTENT = 204
private const val HUB_DIR = "mcp-hub"
private const val SHARED_GRACE_MS = 300_000L
private const val ENDED = "its session ended"
private const val IDLE_REASON = "no session called it within the MCP idle limit"
private const val ORPHANED = "no current plan names it and no live session is bound to it"
private const val SERVER = "server"
private const val LAUNCH = "launch"
private const val OLD_SOCKET = "old_socket"
private const val OLD_GENERATION = "old_generation"
private const val CANDIDATE_SOCKET = "candidate_socket"
private const val CANDIDATE_TUPLE = "candidate_tuple"
private const val CANDIDATE_GENERATION = "candidate_generation"
private const val GENERATION = "generation"
private const val SOCKET = "socket"
private const val TUPLE = "tuple"
private const val SHARED = "shared"

public data class McpHubReply(val status: Int, val body: String?)

public data class McpHubPaths(
    val stateDir: Path,
    val holder: Path,
    val home: Path,
    val slice: String = APP_MCP_SLICE,
)

internal data class HubTarget(
    val key: String,
    val spec: McpServerSpec,
    val launch: String?,
    val place: McpLaunchPlace?,
    val listing: String,
) {
    val shared: Boolean get() = launch == null
}

internal data class Targeted(val target: HubTarget?, val refusal: McpHubReply?)

private data class CanonicalReplacement(
    val target: HubTarget,
    val run: HubLaunchSpec,
    val socket: String,
    val tuple: String,
)

private data class SharedView(
    val bound: List<HubBinding>?,
    val current: Set<String>,
    val draining: Set<HubDrainHolder>,
    val lastCalls: Map<String, Long>,
    val idleMs: Long,
    val now: Long,
) {
    fun stopReason(record: HubRecord): String? {
        val bindings = bound ?: return null
        if (pinned(record) || shared(record, bindings)) return null
        return idleReason(record) ?: orphanReason(record)
    }

    fun pinned(record: HubRecord): Boolean =
        record.generation?.let { HubDrainHolder(record.socket, it) in draining } ?: false

    private fun shared(record: HubRecord, bindings: List<HubBinding>): Boolean =
        bindings.any { it.socket == record.socket } || record.socket in current

    private fun idleReason(record: HubRecord): String? =
        lastCalls[record.socket]?.takeIf { now - maxOf(it, record.recordedAt) >= idleMs }?.let { IDLE_REASON }

    private fun orphanReason(record: HubRecord): String? =
        ORPHANED.takeIf { now - record.recordedAt >= SHARED_GRACE_MS }
}

public class McpHub(
    private val sharing: McpSharing,
    private val servers: McpLaunchServers,
    private val places: McpLaunchPlaces,
    paths: McpHubPaths,
    private val startBound: McpHandshakeBudget,
    io: CoroutineDispatcher,
    private val log: LogSink,
) : McpHubCalls {
    private val tuple = HubTuple(paths.home)
    private val holders = HubHolders(
        paths.stateDir.resolve(HUB_DIR),
        paths.holder,
        paths.slice,
        io,
        log,
    )
    private val listings = HubListings(paths.stateDir.resolve("mcp-listings"), log)
    private val front = HubFront(paths.stateDir.resolve(HUB_DIR).resolve("front.json"))
    private val targets = HubTargets(sharing, servers, places, tuple, holders, listings)
    private val requests = HubRequests()
    private val replacements = McpHubReplacements(
        JsonRpcCodec(),
        targets,
        tuple,
        holders,
        front,
        startBound,
    )

    override fun resolve(body: String): McpHubReply = requests.resolve(body)

    override fun attest(body: String): McpHubReply = requests.attest(body)

    override suspend fun start(body: String): McpHubReply = requests.start(body)

    override fun listing(body: String): McpHubReply = requests.listing(body)

    override fun roots(body: String): McpHubReply = requests.roots(body)

    override suspend fun replacementStart(body: String): McpHubReply = replacements.start(body)

    override fun replacementCommit(body: String): McpHubReply = replacements.commit(body)

    override fun replacementRetire(body: String): McpHubReply = replacements.retire(body)

    override fun replacementRollback(body: String): McpHubReply = replacements.rollback(body)

    override fun replacementExit(body: String): McpHubReply = replacements.exit(body)

    override fun sweep(now: Long, idleMs: Long) = requests.sweep(now, idleMs)

    override fun attached(): Map<String, Set<String>> = requests.attached()

    private inner class HubRequests {
        private val codec = JsonRpcCodec()

        fun resolve(body: String): McpHubReply {
            val targeted = targets.target(body)
            return targeted.target?.let(targets::found) ?: targets.refused(targeted)
        }

        /** Attestation reads only the recorded holder owner and never reconstructs a target from current configuration. */
        fun attest(body: String): McpHubReply {
            val socket = JsonScalars.str(codec.parse(body), SOCKET)?.takeIf { it.isNotEmpty() }
                ?: return targets.error(HttpStatus.BAD_REQUEST, "the attestation names no holder socket")
            val generation = holders.generation(socket)
                ?: return targets.error(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "the holder socket has no live owner generation",
                )
            return McpHubReply(
                HttpStatus.OK,
                JsonWire.string(
                    buildJsonObject {
                        put(SOCKET, socket)
                        put(GENERATION, generation)
                    },
                ),
            )
        }

        suspend fun start(body: String): McpHubReply {
            val targeted = targets.target(body)
            val target = targeted.target ?: return targets.refused(targeted)
            return when (val configured = targets.launch(target)) {
                is McpResult.Served -> start(target, configured.value)
                is McpResult.Refused -> targets.error(HttpStatus.INTERNAL_SERVER_ERROR, configured.reason)
            }
        }

        fun listing(body: String): McpHubReply {
            val targeted = targets.target(body)
            val target = targeted.target ?: return targets.refused(targeted)
            val parsed = codec.parse(body)
            val initialize = parsed?.get("initialize") as? JsonObject
                ?: return targets.error(HttpStatus.BAD_REQUEST, "the listing names no initialize result")
            listings.keep(target.listing, initialize, parsed["tools"] as? JsonObject)
            return McpHubReply(HTTP_NO_CONTENT, null)
        }

        fun roots(body: String): McpHubReply {
            val targeted = targets.target(body)
            val target = targeted.target ?: return targets.refused(targeted)
            if (target.shared) rootsAsked(target)
            return McpHubReply(HTTP_NO_CONTENT, null)
        }

        fun sweep(now: Long, idleMs: Long) {
            val report = front.report()
            val bound = report?.bindings?.filter { it.launch.isEmpty() || places.of(it.launch) != null }
            val view = SharedView(
                bound,
                currentShared(bound.orEmpty()),
                report?.draining.orEmpty(),
                report?.lastCalls.orEmpty(),
                idleMs,
                now,
            )
            holders.stopUnkept { record -> stopReason(record, view) }
        }

        fun attached(): Map<String, Set<String>> {
            val bindings = front.report()?.bindings ?: return emptyMap()
            val names = holders.records.files.serverBySocket()
            return bindings.filter { it.launch.isNotEmpty() }
                .mapNotNull { binding -> names[binding.socket]?.let { it to binding.launch } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, launches) -> launches.toSet() }
        }

        private suspend fun start(target: HubTarget, run: HubLaunchSpec): McpHubReply {
            val holder = HubStart(
                target.key,
                target.spec.name,
                target.launch,
                run,
                tuple.fingerprint(run),
            )
            return when (val result = holders.start(holder, startBound())) {
                is McpResult.Served -> targets.found(target)
                is McpResult.Refused -> targets.error(HttpStatus.INTERNAL_SERVER_ERROR, result.reason)
            }
        }

        private fun rootsAsked(target: HubTarget) {
            sharing.rootsAsked(target.spec)
            log(
                "[mcp-hub] ${LogSafe.str(target.spec.name)}: asks its client for roots, so each later session " +
                    "starts its own\n",
            )
        }

        private fun stopReason(record: HubRecord, view: SharedView): String? {
            if (view.pinned(record)) return null
            return record.launch?.let { launch -> ENDED.takeIf { places.of(launch) == null } }
                ?: view.stopReason(record)
        }

        private fun currentShared(bound: List<HubBinding>): Set<String> {
            val launches = bound.map { it.launch }.toSet() + ""
            return launches.flatMap { launch ->
                val place = launch.takeIf { it.isNotEmpty() }?.let(places::of)
                sharing.plan(servers.servers(launch, place)).hosted.values
                    .map { holders.socket(tuple.digest(it)).toString() }
            }.toSet()
        }
    }
}

private class HubTargets(
    private val sharing: McpSharing,
    private val servers: McpLaunchServers,
    private val places: McpLaunchPlaces,
    private val tuple: HubTuple,
    private val holders: HubHolders,
    private val listings: HubListings,
) {
    private val codec = JsonRpcCodec()

    fun target(body: String): Targeted {
        val parsed = codec.parse(body)
        val server = JsonScalars.str(parsed, SERVER)?.takeIf { it.isNotEmpty() }
            ?: return Targeted(null, error(HttpStatus.BAD_REQUEST, "the body names no server"))
        return target(server, JsonScalars.str(parsed, LAUNCH).orEmpty())
    }

    fun target(server: String, launch: String): Targeted {
        val place = launch.takeIf { it.isNotEmpty() }?.let(places::of)
        val plan = sharing.plan(servers.servers(launch, place))
        return when (plan.classOf(server)) {
            McpShareClass.SHARED -> Targeted(shared(plan, server), null)
            McpShareClass.PER_SESSION -> Targeted(perSession(plan, server, launch, place), null)
            McpShareClass.OWN -> Targeted(null, error(HttpStatus.NOT_FOUND, notHub(plan, server)))
        }
    }

    fun launch(target: HubTarget): McpResult<HubLaunchSpec> = when {
        target.shared -> McpResult.Served(tuple.shared(target.spec))
        else -> target.place?.let { McpResult.Served(tuple.perSession(target.spec, it)) }
            ?: McpResult.Refused(
                "'${target.spec.name}' starts one copy per session, in that session's folder, and splice cannot tell " +
                    "which session this is; start the session with splice",
            )
    }

    fun found(target: HubTarget): McpHubReply {
        val socket = holders.socket(target.key).toString()
        val body = buildJsonObject {
            put(SOCKET, socket)
            put(SHARED, target.shared)
            put("listing", listings.kept(target.listing)?.toString())
            holders.generation(socket)?.let { put(GENERATION, it) }
        }
        return McpHubReply(HttpStatus.OK, JsonWire.string(body))
    }

    fun refused(targeted: Targeted): McpHubReply =
        targeted.refusal ?: error(HttpStatus.INTERNAL_SERVER_ERROR, "the hub found no answer")

    fun error(status: Int, words: String): McpHubReply =
        McpHubReply(status, JsonWire.string(buildJsonObject { put("error", words) }))

    private fun shared(plan: McpPlan, server: String): HubTarget {
        val spec = plan.hosted.getValue(server)
        val digest = tuple.digest(spec)
        return HubTarget(digest, spec, null, null, digest)
    }

    private fun perSession(
        plan: McpPlan,
        server: String,
        launch: String,
        place: McpLaunchPlace?,
    ): HubTarget {
        val spec = plan.perSession.getValue(server)
        return HubTarget(tuple.digest(spec, launch), spec, launch, place, tuple.digest(spec))
    }

    private fun notHub(plan: McpPlan, server: String): String = plan.passthrough[server]
        ?.let { "'$server' is not started by the hub: $it" }
        ?: "this launch loads no server named '$server'"
}

private data class ReplacementStart(
    val server: String,
    val launch: String,
    val oldSocket: String,
    val oldGeneration: String,
)

private data class ParsedReplacementStart(
    val values: ReplacementStart?,
    val error: String?,
)

private data class ParsedHolderExit(val socket: String?, val generation: String?)

private data class ReplacementValues(
    val server: String,
    val launch: String,
    val oldSocket: String,
    val oldGeneration: String,
    val candidateSocket: String,
    val candidateTuple: String,
    val candidateGeneration: String,
)

private fun interface ReplacementAction {
    operator fun invoke(values: ReplacementValues): McpResult<Unit>
}

private class HubReplacementParser(private val codec: JsonRpcCodec) {
    fun start(body: String): ParsedReplacementStart {
        val parsed = codec.parse(body)
        val server = JsonScalars.str(parsed, SERVER)?.takeIf { it.isNotEmpty() }
        val oldSocket = JsonScalars.str(parsed, OLD_SOCKET)
        val oldGeneration = JsonScalars.str(parsed, OLD_GENERATION)
        val error = when {
            server == null -> "the replacement names no server"
            oldSocket == null -> "the replacement names no old holder socket"
            oldGeneration == null -> "the replacement names no old holder generation"
            else -> null
        }
        val values = if (error == null) {
            ReplacementStart(
                server.orEmpty(),
                JsonScalars.str(parsed, LAUNCH).orEmpty(),
                oldSocket.orEmpty(),
                oldGeneration.orEmpty(),
            )
        } else {
            null
        }
        return ParsedReplacementStart(values, error)
    }

    fun values(body: String): ReplacementValues? {
        val parsed = codec.parse(body)
        val server = JsonScalars.str(parsed, SERVER)?.takeIf { it.isNotEmpty() }
        val oldSocket = JsonScalars.str(parsed, OLD_SOCKET)
        val oldGeneration = JsonScalars.str(parsed, OLD_GENERATION)
        val candidateSocket = JsonScalars.str(parsed, CANDIDATE_SOCKET)
        val candidateTuple = JsonScalars.str(parsed, CANDIDATE_TUPLE)
        val candidateGeneration = JsonScalars.str(parsed, CANDIDATE_GENERATION)
        val required = listOf(server, oldSocket, oldGeneration, candidateSocket, candidateTuple, candidateGeneration)
        return if (required.any { it == null }) {
            null
        } else {
            ReplacementValues(
                server.orEmpty(),
                JsonScalars.str(parsed, LAUNCH).orEmpty(),
                oldSocket.orEmpty(),
                oldGeneration.orEmpty(),
                candidateSocket.orEmpty(),
                candidateTuple.orEmpty(),
                candidateGeneration.orEmpty(),
            )
        }
    }

    fun exit(body: String): ParsedHolderExit {
        val parsed = codec.parse(body)
        return ParsedHolderExit(
            JsonScalars.str(parsed, SOCKET),
            JsonScalars.str(parsed, GENERATION),
        )
    }
}

private class HubCanonicalReplacements(
    private val targets: HubTargets,
    private val tuple: HubTuple,
    private val holders: HubHolders,
    private val front: HubFront,
) {
    fun resolve(request: ReplacementStart): McpResult<CanonicalReplacement> {
        val routes = affected(request)
        return when {
            routes == null -> McpResult.Refused("the old holder is not retained by this front route")
            !holders.owns(request.oldSocket, expectedGeneration = request.oldGeneration) ->
                McpResult.Refused("the old holder is not the daemon owner's exact generation")
            else -> selection(routes.map(::target))
        }
    }

    private fun affected(request: ReplacementStart): List<HubRoute>? = front.report()
        ?.routes
        ?.filter { it.socket == request.oldSocket }
        ?.takeIf { routes -> routes.any { it.server == request.server && it.launch == request.launch } }

    private fun target(route: HubRoute): McpResult<Pair<HubTarget, HubLaunchSpec>> {
        val target = targets.target(route.server, route.launch).target
        val run = target?.let(targets::launch)
        return when {
            !route.shared -> McpResult.Refused("one old socket spans mixed or per-session routing targets")
            target == null -> McpResult.Refused("an affected alias no longer has a canonical hub target")
            !target.shared -> McpResult.Refused("one old socket spans mixed shared and per-session targets")
            run is McpResult.Served -> McpResult.Served(target to run.value)
            run is McpResult.Refused -> run
            else -> McpResult.Refused("an affected alias no longer has a canonical hub target")
        }
    }

    private fun selection(
        configured: List<McpResult<Pair<HubTarget, HubLaunchSpec>>>,
    ): McpResult<CanonicalReplacement> {
        val refused = configured.filterIsInstance<McpResult.Refused>().firstOrNull()
        val served = configured.filterIsInstance<McpResult.Served<Pair<HubTarget, HubLaunchSpec>>>()
        return refused ?: selected(served.map { it.value })
    }

    private fun selected(configured: List<Pair<HubTarget, HubLaunchSpec>>): McpResult<CanonicalReplacement> {
        val first = configured.firstOrNull()
        val fingerprint = first?.let { tuple.fingerprint(it.second) }
        return when {
            first == null -> McpResult.Refused("the front retained no target for the old holder")
            fingerprint == null -> McpResult.Refused("the front retained no target for the old holder")
            configured.any { candidate -> !sameTarget(first, fingerprint, candidate) } ->
                McpResult.Refused("affected aliases or launches resolve to mixed canonical replacement tuples")
            else -> McpResult.Served(
                CanonicalReplacement(
                    first.first,
                    first.second,
                    holders.socket(first.first.key).toString(),
                    fingerprint,
                ),
            )
        }
    }

    private fun sameTarget(
        first: Pair<HubTarget, HubLaunchSpec>,
        fingerprint: String,
        candidate: Pair<HubTarget, HubLaunchSpec>,
    ): Boolean =
        candidate.first.key == first.first.key && tuple.fingerprint(candidate.second) == fingerprint
}

private class McpHubReplacements(
    codec: JsonRpcCodec,
    private val targets: HubTargets,
    private val tuple: HubTuple,
    private val holders: HubHolders,
    front: HubFront,
    private val startBound: McpHandshakeBudget,
) {
    private val parser = HubReplacementParser(codec)
    private val canonical = HubCanonicalReplacements(targets, tuple, holders, front)

    suspend fun start(body: String): McpHubReply {
        val parsed = parser.start(body)
        val request = parsed.values
        return if (request == null) {
            targets.error(HttpStatus.BAD_REQUEST, parsed.error.orEmpty())
        } else {
            start(request)
        }
    }

    private suspend fun start(request: ReplacementStart): McpHubReply {
        val replacement = when (val result = canonical.resolve(request)) {
            is McpResult.Served -> result.value
            is McpResult.Refused -> return targets.error(HttpStatus.SERVICE_UNAVAILABLE, result.reason)
        }
        return if (replacement.socket == request.oldSocket) {
            targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the canonical configured tuple still resolves to the old holder",
            )
        } else {
            started(replacement)
        }
    }

    fun commit(body: String): McpHubReply {
        val values = parser.values(body)
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the replacement names an incomplete holder tuple")
        val replacement = when (val result = canonical.resolve(start(values))) {
            is McpResult.Served -> result.value
            is McpResult.Refused -> return targets.error(HttpStatus.SERVICE_UNAVAILABLE, result.reason)
        }
        return if (matches(replacement, values)) {
            McpHubReply(HTTP_NO_CONTENT, null)
        } else {
            targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the candidate no longer matches every affected canonical alias and launch",
            )
        }
    }

    fun retire(body: String): McpHubReply = action(
        body,
        ReplacementAction { values ->
            holders.retire(
                values.oldSocket,
                values.oldGeneration,
                values.candidateSocket,
                values.candidateTuple,
                values.candidateGeneration,
            )
        },
    )

    fun rollback(body: String): McpHubReply = action(
        body,
        ReplacementAction { values ->
            holders.rollback(
                values.oldSocket,
                values.oldGeneration,
                values.candidateSocket,
                values.candidateTuple,
                values.candidateGeneration,
            )
        },
    )

    fun exit(body: String): McpHubReply {
        val values = parser.exit(body)
        val socket = values.socket
            ?: return targets.error(
                HttpStatus.BAD_REQUEST,
                "the holder exit names no socket",
            )
        val generation = values.generation
            ?: return targets.error(
                HttpStatus.BAD_REQUEST,
                "the holder exit names no generation",
            )
        return result(holders.exited(socket, generation))
    }

    private suspend fun started(replacement: CanonicalReplacement): McpHubReply = when (
        val started = holders.start(
            HubStart(
                replacement.target.key,
                replacement.target.spec.name,
                replacement.target.launch,
                replacement.run,
                replacement.tuple,
            ),
            startBound(),
        )
    ) {
        is McpResult.Refused -> targets.error(HttpStatus.SERVICE_UNAVAILABLE, started.reason)
        is McpResult.Served -> response(replacement)
    }

    private fun response(replacement: CanonicalReplacement): McpHubReply {
        val generation = holders.generation(replacement.socket)
            ?: return targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the candidate holder has no live owner generation",
            )
        return if (holders.owns(replacement.socket, replacement.tuple, generation)) {
            McpHubReply(
                HttpStatus.OK,
                JsonWire.string(
                    buildJsonObject {
                        put(SOCKET, replacement.socket)
                        put(TUPLE, replacement.tuple)
                        put(GENERATION, generation)
                        put(SHARED, replacement.target.shared)
                    },
                ),
            )
        } else {
            targets.error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "the candidate holder does not prove the canonical configured tuple",
            )
        }
    }

    private fun action(body: String, operation: ReplacementAction): McpHubReply {
        val values = parser.values(body)
            ?: return targets.error(HttpStatus.BAD_REQUEST, "the replacement names an incomplete holder tuple")
        return result(operation(values))
    }

    private fun matches(replacement: CanonicalReplacement, values: ReplacementValues): Boolean =
        replacement.socket == values.candidateSocket &&
            replacement.tuple == values.candidateTuple &&
            holders.owns(values.candidateSocket, values.candidateTuple, values.candidateGeneration)

    private fun result(value: McpResult<Unit>): McpHubReply = when (value) {
        is McpResult.Served -> McpHubReply(HTTP_NO_CONTENT, null)
        is McpResult.Refused -> targets.error(HttpStatus.SERVICE_UNAVAILABLE, value.reason)
    }

    private fun start(values: ReplacementValues): ReplacementStart =
        ReplacementStart(values.server, values.launch, values.oldSocket, values.oldGeneration)
}
