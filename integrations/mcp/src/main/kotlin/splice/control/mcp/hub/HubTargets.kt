package splice.control.mcp.hub

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.client.mcp.McpPlan
import splice.client.mcp.McpServerSpec
import splice.client.mcp.McpShareClass
import splice.client.mcp.McpSharing
import splice.control.mcp.JsonRpcCodec
import splice.control.mcp.McpResult
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.HttpStatus

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

internal class HubTargets(
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
        val server = JsonScalars.str(parsed, HubFields.SERVER)?.takeIf { it.isNotEmpty() }
            ?: return Targeted(null, error(HttpStatus.BAD_REQUEST, "the body names no server"))
        return target(server, JsonScalars.str(parsed, HubFields.LAUNCH).orEmpty())
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

    fun fingerprint(run: HubLaunchSpec): String = tuple.fingerprint(run)

    fun found(target: HubTarget): McpHubReply {
        val socket = holders.socket(target.key).toString()
        val body = buildJsonObject {
            put(HubFields.SOCKET, socket)
            put(HubFields.SHARED, target.shared)
            put("listing", listings.kept(target.listing)?.toString())
            holders.generation(socket)?.let { put(HubFields.GENERATION, it) }
        }
        return McpHubReply(HubResponses.OK, JsonWire.string(body))
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
