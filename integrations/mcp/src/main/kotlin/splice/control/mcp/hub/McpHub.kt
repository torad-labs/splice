// NEW: MCP hub steps 3 and 4, the daemon half (docs/specs/mcp-hub.md; splice-lead, Oct 12). The front holds every
// session's connection and calls the daemon only to find a server's holder, to start one, and to keep what a fresh
// holder listed. The three calls, with the shapes agreed with splice-config (body `{"server", "launch"}`, management
// key as bearer):
//   - resolve starts nothing: where the server's socket is, whether it is shared, and its kept listing,
//     which the front answers a session's initialize and tools/list from;
//   - start starts the holder unless it runs, and answers once its socket accepts connections;
//   - listing keeps a fresh holder's initialize and tools/list results (HubListings).
// The class comes from McpSharing's plan over the servers the launch loads (hub step 3's order): a shared server has
// one holder for every session; a per-session server has one per launch, started in that launch's folder with its
// environment, and stopped once the launch is gone ([sweep]). A per-session server for a launch splice cannot place
// is refused in words rather than started somewhere it was never meant to run.
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
import splice.core.util.LogSink
import splice.core.wire.HttpStatus
import java.nio.file.Path

// why: 204 stays local: HttpStatus declares no 2xx but OK, as McpHost notes for its own 202.
private const val HTTP_NO_CONTENT = 204

// why: how long a shared holder no session is bound to is left alone after it was recorded: the front saves a session
// only once its initialize is answered, so a holder started for a session that is still connecting is never stopped.
private const val SHARED_GRACE_MS = 300_000L

// why: the words the log gives for each kind of stop.
private const val ENDED = "its session ended"
private const val IDLE_REASON = "no session called it within the MCP idle limit"
private const val ORPHANED = "no current plan names it and no live session is bound to it"

/** What a hub call answers: a status and a JSON body, or none for 204. */
public data class McpHubReply(val status: Int, val body: String?)

/** Where the hub keeps its files and finds its holder: [stateDir] is splice's state folder, [holder] the
 *  splice-mcp-holder binary beside splice.jar, [home] the folder a shared server starts in. */
public data class McpHubPaths(val stateDir: Path, val holder: Path, val home: Path, val slice: String = APP_MCP_SLICE)

/** One server as one launch reaches it: its holder's key, its declared tuple, the launch and its place for a
 *  per-session copy (the place null when splice cannot tell where the launch runs), and its kept listing's digest. */
private data class HubTarget(
    val key: String,
    val spec: McpServerSpec,
    val launch: String?,
    val place: McpLaunchPlace?,
    val listing: String,
) {
    val shared: Boolean get() = launch == null
}

/** A target, or the refusal the front is answered with; exactly one is set. */
private data class Targeted(val target: HubTarget?, val refusal: McpHubReply?)

/** One sweep's view of the shared holders: the live sessions the front holds (null when it saved nothing readable), the
 *  sockets a current plan names, each holder's last call, the idle limit, and the sweep's moment. */
private data class SharedView(
    val bound: List<HubBinding>?,
    val current: Set<String>,
    val lastCalls: Map<String, Long>,
    val idleMs: Long,
    val now: Long,
) {
    /** Idle once neither a call nor its own start is within the limit: a holder started again after an idle stop still
     *  carries its old last_call until the front saves its new handshake. */
    fun idle(record: HubRecord): Boolean =
        lastCalls[record.socket]?.let { now - maxOf(it, record.recordedAt) >= idleMs } == true

    /** Why a shared holder stops, or null while it stays: it stays while the front cannot say who is bound, while a
     *  session is bound to it or a current plan names it, and while it may still be starting for a session the front
     *  has not saved yet. */
    fun stopReason(record: HubRecord): String? = when {
        bound == null -> null
        idle(record) -> IDLE_REASON
        bound.any { it.socket == record.socket } -> null
        record.socket in current -> null
        now - record.recordedAt < SHARED_GRACE_MS -> null
        else -> ORPHANED
    }
}

public class McpHub(
    private val sharing: McpSharing,
    private val servers: McpLaunchServers,
    private val places: McpLaunchPlaces,
    paths: McpHubPaths,
    /** How long a holder may take to open its socket: the host's handshake budget, read at each start. */
    private val startBound: McpHandshakeBudget,
    io: CoroutineDispatcher,
    log: LogSink,
) : McpHubCalls {
    private val codec = JsonRpcCodec()
    private val tuple = HubTuple(paths.home)
    private val holders = HubHolders(paths.stateDir.resolve("mcp-hub"), paths.holder, paths.slice, io, log)
    private val listings = HubListings(paths.stateDir.resolve("mcp-listings"), log)
    private val front = HubFront(paths.stateDir.resolve("mcp-hub").resolve("front.json"))

    /** `POST /api/mcp/hub/resolve`: where [body]'s server is, starting nothing. */
    override fun resolve(body: String): McpHubReply {
        val targeted = target(body)
        return targeted.target?.let(::found) ?: refused(targeted)
    }

    /** `POST /api/mcp/hub/start`: [body]'s holder started unless it runs. */
    override suspend fun start(body: String): McpHubReply {
        val targeted = target(body)
        val target = targeted.target ?: return refused(targeted)
        val run = when {
            target.shared -> tuple.shared(target.spec)
            else -> target.place?.let { tuple.perSession(target.spec, it) }
                ?: return error(HttpStatus.INTERNAL_SERVER_ERROR, unplaced(target.spec.name))
        }
        return when (val up = holders.start(HubStart(target.key, target.spec.name, target.launch, run), startBound())) {
            is McpResult.Served -> found(target)
            is McpResult.Refused -> error(HttpStatus.INTERNAL_SERVER_ERROR, up.reason)
        }
    }

    /** `POST /api/mcp/hub/listing`: what a fresh holder answered, kept for the server's next sessions. */
    override fun listing(body: String): McpHubReply {
        val targeted = target(body)
        val target = targeted.target ?: return refused(targeted)
        val parsed = codec.parse(body)
        val initialize = parsed?.get("initialize") as? JsonObject
            ?: return error(HttpStatus.BAD_REQUEST, "the listing names no initialize result")
        listings.keep(target.listing, initialize, parsed["tools"] as? JsonObject)
        return McpHubReply(HTTP_NO_CONTENT, null)
    }

    /** Stops the per-session holders of launches that ended; the shared holders the front reports no call to within
     *  [idleMs] (the MCP idle limit), since the next call starts one again; and the shared holders no current plan names
     *  (a tuple change left them behind) once no live session is bound to them, a session whose launch ended no longer
     *  counting. The host's sweep calls it at [now]. */
    override fun sweep(now: Long, idleMs: Long) {
        val report = front.report()
        val bound = report?.bindings?.filter { it.launch.isEmpty() || places.of(it.launch) != null }
        val view = SharedView(bound, bound?.let(::currentShared).orEmpty(), report?.lastCalls.orEmpty(), idleMs, now)
        holders.stopUnkept { record ->
            when (val launch = record.launch) {
                null -> view.stopReason(record)
                else -> ENDED.takeIf { places.of(launch) == null }
            }
        }
    }

    override fun attached(): Map<String, Set<String>> {
        val bindings = front.report()?.bindings ?: return emptyMap()
        val names = holders.records.serverBySocket()
        return bindings.filter { it.launch.isNotEmpty() }
            .mapNotNull { binding -> names[binding.socket]?.let { it to binding.launch } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, launches) -> launches.toSet() }
    }

    /** The sockets of every shared holder a current plan names: the user-scope plan's and each bound launch's. */
    private fun currentShared(bound: List<HubBinding>): Set<String> {
        val launches = bound.map { it.launch }.toSet() + ""
        return launches.flatMap { launch ->
            val place = launch.takeIf { it.isNotEmpty() }?.let(places::of)
            val plan = sharing.plan(servers.servers(launch, place))
            plan.hosted.values.map { holders.socket(tuple.digest(it)).toString() }
        }.toSet()
    }

    /** [body]'s server as its launch reaches it, or the refusal the front is answered with. */
    private fun target(body: String): Targeted {
        val parsed = codec.parse(body)
        val server = JsonScalars.str(parsed, "server")?.takeIf { it.isNotEmpty() }
            ?: return Targeted(null, error(HttpStatus.BAD_REQUEST, "the body names no server"))
        val launch = JsonScalars.str(parsed, "launch").orEmpty()
        val place = launch.takeIf { it.isNotEmpty() }?.let(places::of)
        val plan = sharing.plan(servers.servers(launch, place))
        return when (plan.classOf(server)) {
            McpShareClass.SHARED -> Targeted(shared(plan, server), null)
            McpShareClass.PER_SESSION -> Targeted(perSession(plan, server, launch, place), null)
            McpShareClass.OWN -> Targeted(null, error(HttpStatus.NOT_FOUND, notHub(plan, server)))
        }
    }

    private fun shared(plan: McpPlan, server: String): HubTarget {
        val spec = plan.hosted.getValue(server)
        val digest = tuple.digest(spec)
        return HubTarget(digest, spec, null, null, digest)
    }

    private fun perSession(plan: McpPlan, server: String, launch: String, place: McpLaunchPlace?): HubTarget {
        val spec = plan.perSession.getValue(server)
        return HubTarget(tuple.digest(spec, launch), spec, launch, place, tuple.digest(spec))
    }

    private fun found(target: HubTarget): McpHubReply {
        val body = buildJsonObject {
            put("socket", holders.socket(target.key).toString())
            put("shared", target.shared)
            put("listing", listings.kept(target.listing)?.toString())
        }
        return McpHubReply(HttpStatus.OK, JsonWire.string(body))
    }

    private fun refused(targeted: Targeted): McpHubReply =
        targeted.refusal ?: error(HttpStatus.INTERNAL_SERVER_ERROR, "the hub found no answer")

    private fun notHub(plan: McpPlan, server: String): String = plan.passthrough[server]
        ?.let { "'$server' is not started by the hub: $it" }
        ?: "this launch loads no server named '$server'"

    private fun unplaced(server: String): String =
        "'$server' starts one copy per session, in that session's folder, and splice cannot tell which session " +
            "this is; start the session with splice"

    private fun error(status: Int, words: String) =
        McpHubReply(status, JsonWire.string(buildJsonObject { put("error", words) }))
}
