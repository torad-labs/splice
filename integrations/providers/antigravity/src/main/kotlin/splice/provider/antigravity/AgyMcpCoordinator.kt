// NEW: agy-desktop-parity-2 (docs/specs/desktop-antigravity.md). Coordinates opening agy with merged Claude-file and
// Desktop MCP servers (including SDK servers through McpBridge), adding servers via `mcp_set_servers`, and draining
// Desktop's `mcp_message` replies and agy's updates concurrently while an ACP open/resume call is in flight.
package splice.provider.antigravity

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.provider.antigravity.skills.SkillShelf
import java.nio.file.Path

/** Callback for agy messages drained while an ACP open or resume request is awaiting its reply. */
internal interface AgyMessageSink {
    fun accept(message: JsonObject)
}

/** Static launch inputs for MCP discovery and agy session setup; [skills] gives a new session the index it appends to
 *  agy's system prompt when agy offers that, read when the session opens so a reload before then counts. */
internal data class CoordinatorEnv(
    val claude: ClaudeArgs,
    val strictMcp: Boolean,
    val cwd: Path,
    val env: EnvReader,
    val log: LogSink,
    val skills: SkillShelf,
)

/** Active agy process handshake and channels pumped while an ACP open/resume call runs. */
internal data class CoordinatorIo(
    val started: StartedAgy,
    val hello: AgyHello?,
    val helloFailure: String?,
    val lines: ReceiveChannel<String>,
    val deferredLines: ArrayDeque<String>,
    val scope: CoroutineScope,
)

/** Result of lazily opening the agy session once Desktop sends `initialize` (or its first user message). */
internal data class LiveOpenResult(
    val session: AgySession?,
    val resumed: Boolean,
    val mcpApplied: Boolean,
    val unusable: String?,
)

/** Owns MCP server merging, bridge registration, and concurrent pumping while agy opens or updates MCP servers. */
internal class AgyMcpCoordinator(
    private val target: CoordinatorEnv,
    private val io: CoordinatorIo,
    private val mcpState: McpState,
    private val bridge: McpBridge,
    private val builtIns: List<AgyMcpSpec> = emptyList(),
) {
    private val encoder = AcpMcpEncoder()
    private val reader = AgyMcpReader(target.log)
    private val files = ClaudeMcpFiles(target.cwd, target.env, target.log)

    fun readInitializeSpecs(request: JsonObject): List<AgyMcpSpec> = reader.fromInitialize(request)

    fun readSetSpecs(request: JsonObject): List<AgyMcpSpec> = reader.fromSetServers(request)

    /** Merges Claude config-file MCP servers with [desktopSpecs] (Desktop wins on name collision), registers SDK
     *  servers on [bridge], and opens agy while pumping Desktop's `mcp_message` replies and agy updates. */
    suspend fun open(desktopSpecs: List<AgyMcpSpec>, onAgy: AgyMessageSink): LiveOpenResult {
        val conn = io.started.connection
        val hello = io.hello
        if (conn == null || hello == null) {
            return LiveOpenResult(null, false, false, io.helloFailure ?: io.started.reason)
        }
        val merged = mergeSpecs(files.load(target.strictMcp), desktopSpecs)
        registerSdk(merged)
        val encoded = encoder.encode(merged, bridge)
        val names = merged.map(AgyMcpSpec::name)
        val index = if (hello.append) target.skills.index() else null
        val opener = AgyOpener(conn, target.cwd.toString(), append = index)
        val openJob = io.scope.async {
            opener.openSession(target.claude.resume, hello, encoded, names, target.log)
        }
        val opened = awaitWhilePumping(openJob, conn, onAgy)
        val agySession = (opened.open as? AgyOpen.Ready)?.session
        if (agySession != null) {
            awaitWhilePumping(io.scope.async { prepare(agySession) }, conn, onAgy)
        }
        mcpState.setInitial(merged, opened.mcpApplied)
        val unusable = (opened.open as? AgyOpen.Unusable)?.reason
        return LiveOpenResult(agySession, opened.resumed, opened.mcpApplied, unusable)
    }

    /** Handles `mcp_set_servers`: adds any new servers via `session/resume` while reporting dropped servers as errors
     *  (agy cannot stop servers it already started). */
    suspend fun setServers(
        session: AgySession?,
        request: JsonObject,
        onAgy: AgyMessageSink,
    ): JsonObject {
        val diff = mcpState.diffForSet(mergeSpecs(readSetSpecs(request), emptyList()))
        if (diff.added.isEmpty()) {
            return mcpState.setServersResponse(emptyList(), diff.dropped, addError = null)
        }
        val agy = session ?: return mcpState.setServersResponse(
            diff.added.map(AgyMcpSpec::name),
            diff.dropped,
            addError = "agy is not running",
        )
        registerSdk(diff.added)
        val encoded = encoder.encode(diff.nextAll, bridge)
        val resumeJob = io.scope.async { agy.resumeWithMcp(target.cwd.toString(), encoded) }
        val refusal = awaitWhilePumping(resumeJob, io.started.connection, onAgy)
        if (refusal == null) mcpState.commitAdded(diff.added)
        return mcpState.setServersResponse(diff.added.map(AgyMcpSpec::name), diff.dropped, addError = refusal)
    }

    private fun mergeSpecs(fileSpecs: List<AgyMcpSpec>, desktopSpecs: List<AgyMcpSpec>): List<AgyMcpSpec> {
        val merged = LinkedHashMap<String, AgyMcpSpec>()
        fileSpecs.forEach { merged[it.name] = it }
        desktopSpecs.forEach { merged[it.name] = it }
        builtIns.forEach { merged[it.name] = it }
        return merged.values.toList()
    }

    private fun registerSdk(specs: List<AgyMcpSpec>) {
        val sdkNames = specs.filterIsInstance<AgyMcpSpec.Sdk>().map(AgyMcpSpec::name)
        if (sdkNames.isNotEmpty()) bridge.register(sdkNames)
    }

    private suspend fun prepare(session: AgySession) {
        val refusals = listOfNotNull(
            target.claude.model?.let { session.model(it) },
            target.claude.effort?.let { session.effort(it) },
            session.mode(target.claude.permissionMode),
        )
        refusals.forEach { target.log("[antigravity] $it\n") }
    }

    /** Awaits [job] while pumping Desktop `control_response` lines into [bridge] (so `mcp_message` works while
     *  `session/new` is still opening) and draining agy updates. */
    private suspend fun <T> awaitWhilePumping(
        job: Deferred<T>,
        conn: AcpConnection?,
        onAgy: AgyMessageSink,
    ): T {
        val state = PumpState(desktopClosed = false, agyClosed = conn == null)
        while (!job.isCompleted) {
            val step = nextPumpStep(job, conn, state)
            when (step) {
                is PumpStep.Done -> {
                    drainAgy(conn, onAgy)
                    return step.value
                }
                is PumpStep.Agy -> step.message?.let(onAgy::accept) ?: run { state.agyClosed = true }
                is PumpStep.Desktop -> step.line?.let(::handleDesktopLine) ?: run { state.desktopClosed = true }
            }
        }
        drainAgy(conn, onAgy)
        return job.await()
    }

    private suspend fun <T> nextPumpStep(job: Deferred<T>, conn: AcpConnection?, state: PumpState): PumpStep<T> =
        select {
            job.onAwait { PumpStep.Done(it) }
            if (!state.agyClosed && conn != null) {
                conn.incoming.onReceiveCatching { PumpStep.Agy(it.getOrNull()) }
            }
            if (!state.desktopClosed) {
                io.lines.onReceiveCatching { PumpStep.Desktop(it.getOrNull()) }
            }
        }

    private fun drainAgy(conn: AcpConnection?, onAgy: AgyMessageSink) {
        if (conn == null) return
        while (true) {
            val next = conn.incoming.tryReceive().getOrNull() ?: break
            onAgy.accept(next)
        }
    }

    private fun handleDesktopLine(line: String) {
        val obj = JsonScalars.objectOrNull(Json, line)
        val isBridgeReply = obj != null &&
            JsonScalars.str(obj, "type") == "control_response" &&
            bridge.answered(obj["response"] as? JsonObject)
        if (!isBridgeReply) io.deferredLines.addLast(line)
    }

    private data class PumpState(var desktopClosed: Boolean, var agyClosed: Boolean)

    private sealed class PumpStep<out T> {
        class Done<T>(val value: T) : PumpStep<T>()

        class Agy(val message: JsonObject?) : PumpStep<Nothing>()

        class Desktop(val line: String?) : PumpStep<Nothing>()
    }
}
