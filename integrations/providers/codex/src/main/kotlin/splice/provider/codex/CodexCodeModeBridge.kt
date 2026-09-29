// NEW: composes the opt-in Codex code-mode protocol bridge and its bounded collaborators.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.turn.GatewayCustomCall
import splice.core.util.LogSink
import splice.upstream.InterceptedRoundPost
import splice.upstream.RoundInterceptor
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val DEFAULT_MAX_SOURCE_CHARS: Int = 65_536
private const val DEFAULT_MAX_OUTPUT_CHARS: Int = 1_048_576

/** Opens the runtime for one head run. A runtime's close() is terminal and a head stop must close it
 *  (its workers are child JVMs), so a restarted head needs a new one (V4-107). */
public fun interface CodeModeRuntimes {
    public fun open(): CodeModeRuntime
}

/** The current head run's runtime: opened on the first script after a start, closed at head stop. */
internal class CodeModeRuntimeRun(private val runtimes: CodeModeRuntimes) {
    private val monitor = Any()
    private var current: CodeModeRuntime? = null

    fun runtime(): CodeModeRuntime = synchronized(monitor) { current ?: runtimes.open().also { current = it } }

    fun end() {
        val ending: CodeModeRuntime? = synchronized(monitor) { current.also { current = null } }
        ending?.close()
    }
}

// why: the head's records as stored, past which its least recently used conversations go (V4-337).
// Under the 128-record cap's worst case (128 records of the largest seen, 436 KB: ~55 MB), and ~3.6x
// the 8.8 MB its 128 records took live on 2026-09-26, which covered 93 minutes of 17 conversations.
private const val DEFAULT_RETAINED_BYTES: Long = 32L * 1024 * 1024

/** V4-337: what a head's code mode keeps. The unit is the conversation (a record's key): its records
 *  place in its history in order, each on top of the one before, so a record that goes takes every
 *  later one of its conversation with it. The bounds therefore take whole conversations, least
 *  recently used first, and never the conversation a new script belongs to. */
public data class CodeModeRetention(
    /** The most records one conversation keeps; past it, that conversation's own oldest finished one goes. */
    val perConversation: Int = 128,
    /** The most records the head keeps; a script that no finished record can make room for is refused. */
    val records: Int = 1024,
    /** The most the head's records take as stored (UTF-8 JSON). Never a reason to refuse a script. */
    val bytes: Long = DEFAULT_RETAINED_BYTES,
)

public data class CodeModeBridgeConfig(
    val runtimes: CodeModeRuntimes,
    val state: CodeModeStateLocation,
    /** V4-337: per conversation and by total size; was 128 records for the whole head. */
    val retention: CodeModeRetention = CodeModeRetention(),
    val ttl: Duration = 24.hours,
    val maxSourceChars: Int = DEFAULT_MAX_SOURCE_CHARS,
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    val maxCalls: Int = 64,
    val maxRounds: Int = 32,
    val clock: Clock = Clock.systemUTC(),
    /** Head-scoped sink for history-degradation lines; uninstalled it is a no-op. */
    val log: LogSink = LogSink { },
    /** A cell parked this long without client results is closed: its worker slot is the scarce
     *  resource, and a client that never returned (session abandoned, compacted, killed) never will.
     *  2026-09-07: four parked cells held all four slots and every new script failed for an hour. */
    val cellIdleTimeout: Duration = 30.minutes,
    /** At capacity the oldest parked cell at least this idle is evicted for the new script; a cell
     *  younger than this is presumed mid-call (a long Bash command, a permission prompt). */
    val cellEvictionFloor: Duration = 2.minutes,
)

/** V4-340: where a head keeps its code-mode records: one owner-only file per conversation in [dir]. [legacyFile]
 *  is the single file every daemon before V4-340 kept the whole head in: read once, on the first load, its
 *  conversations carried into [dir], and deleted. */
public data class CodeModeStateLocation(val dir: Path, val legacyFile: Path)

// why: how often a head's code-mode records are swept with no turn (V4-287). A record goes within
// this long of its ttl, and a parked cell within this long of cellIdleTimeout (30 min).
private val SWEEP_INTERVAL: Duration = 5.minutes

/** Codex-only protocol bridge. It schedules client tools but never executes them. [sweepInterval] is
 *  how often its records are swept on a timer (V4-287), shorter only in a test. */
public class CodexCodeModeBridge(
    private val config: CodeModeBridgeConfig,
    sweepInterval: Duration = SWEEP_INTERVAL,
) {
    public data class Turn(
        val sessionId: String,
        val conversationKey: String,
        val model: String,
        val tools: Set<String>,
        val toolResults: List<CodeModeResult> = emptyList(),
        /** V4-179: per code-mode result id, the follow-up wire items its images render to (the
         *  ordinary dialect policy, rendered once by CodexCodeModeTurnBuilder). The script never
         *  sees these; the record persists them and the history replays them. */
        val toolMedia: Map<String, List<JsonElement>> = emptyMap(),
        /** V4-179: the same results rendered with the V4-178 markers, for replay identity against a
         *  record the previous daemon wrote (see CodexCodeModeValidation.conflicts). */
        val legacyResults: List<CodeModeResult> = emptyList(),
        /** V4-388: each client tool's description, for the cell's `ALL_TOOLS`. */
        val descriptions: Map<String, String> = emptyMap(),
    )

    private val json = Json { encodeDefaults = true }
    private val wire = CodexCodeModeWire(json, config.log)
    private val registry = CodexCodeModeRegistry(config, json, sweepInterval)
    private val validation = CodexCodeModeValidation(config)
    private val machine = CodexCodeModeMachine(config, registry, validation)
    private val run = CodeModeRuntimeRun(config.runtimes)
    private val driver = CodexCodeModeDriver(config, run, registry, wire, validation, machine)
    private val resume = CodexCodeModeResume(registry, wire, validation, machine, driver)
    private val controller = CodexCodeModeTurn(registry, wire, driver, resume, machine, config.log)

    init {
        require(config.retention.perConversation > 0) { "code-mode retention.perConversation must be positive" }
        require(config.retention.records > 0) { "code-mode retention.records must be positive" }
        require(config.retention.bytes > 0) { "code-mode retention.bytes must be positive" }
        require(config.ttl.isPositive()) { "code-mode ttl must be positive" }
        require(config.cellIdleTimeout.isPositive()) { "code-mode cellIdleTimeout must be positive" }
        require(config.cellEvictionFloor.isPositive()) { "code-mode cellEvictionFloor must be positive" }
        require(sweepInterval.isPositive()) { "code-mode sweepInterval must be positive" }
        require(config.maxSourceChars > 0) { "code-mode maxSourceChars must be positive" }
        require(config.maxOutputChars > 0) { "code-mode maxOutputChars must be positive" }
        require(config.maxCalls > 0) { "code-mode maxCalls must be positive" }
        require(config.maxRounds > 0) { "code-mode maxRounds must be positive" }
    }

    public fun interceptor(
        turn: Turn,
        initialOuter: GatewayCustomCall? = null,
        disableParallel: Boolean,
    ): RoundInterceptor {
        val admitted = turn.copy(
            toolResults = turn.toolResults.map(validation::admit),
            legacyResults = turn.legacyResults.map(validation::admit),
        )
        return RoundInterceptor { bodyJson, sink, post ->
            // Every round a code-mode turn posts leaves through here, so the wire projection has one seam.
            val upstream = InterceptedRoundPost { body -> post(wire.upstream(body)) }
            controller.run(CodeModeRunInput(admitted, initialOuter, disableParallel, bodyJson, sink, upstream))
        }
    }

    public fun injectTool(request: JsonObject, clientTools: Set<String>): JsonObject =
        wire.injectTool(request, clientTools)

    public fun onHeadStop() {
        registry.onHeadStop()
        // The runtime owns child JVM worker processes; a head stop is the one production path that
        // releases them, so it is closed here and nowhere else (AutoCloseableClosedLawTest). The
        // provider outlives the stop (Provider.onHeadStop), so the next start opens a fresh runtime.
        run.end()
    }
}
