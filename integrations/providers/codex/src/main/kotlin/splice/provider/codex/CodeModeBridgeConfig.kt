package splice.provider.codex

import kotlinx.serialization.json.JsonElement
import splice.core.util.LogSink
import splice.provider.codex.state.lease.CodeModeCellLease
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeRuntime
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

// why: the legacy bridge bounds a single source independently of the native protocol's frame limit.
private const val DEFAULT_MAX_SOURCE_CHARS: Int = 65_536

// why: client-visible legacy output stays within the existing one-megacharacter bridge contract.
private const val DEFAULT_MAX_OUTPUT_CHARS: Int = 1_048_576

/** Opens the runtime for one head run. A runtime's close() is terminal and a head stop must close it
 *  (its workers are child JVMs), so a restarted head needs a new one (V4-107). */
public fun interface CodeModeRuntimes {
    public fun open(): CodeModeRuntime
}

// Decoded JSON, strings and transient serialization buffers need several times the stored bytes.
private const val RETAINED_HEAP_DIVISOR: Long = 4

// A share of a large heap still let a 32 GB JVM keep 8 GB of history: the live daemon held 1,470,519 replay
// anchors on 21,409 records (about 300 MB of objects) on Oct 20. A fixed ceiling keeps code mode inside a few GB.
private const val RETAINED_BYTES_CEILING: Long = 256L * 1024 * 1024

/** V4-337: what a head's code mode keeps. The unit is the conversation (a record's key): its records
 *  place in its history in order, each on top of the one before, so a record that goes takes every
 *  later one of its conversation with it. The bounds therefore take whole conversations, least
 *  recently used first, and never the conversation a new script belongs to. */
public data class CodeModeRetention(
    /** An optional explicit conversation count bound. Null leaves retention to bytes and TTL. */
    val perConversation: Int? = null,
    /** An optional explicit head count bound. No fixed count restricts a head by default. */
    val records: Int? = null,
    /** The most the head's records take as stored (UTF-8 JSON). Never a reason to refuse a script. */
    // A boot-time share of the actual JVM heap, with room for decoded objects and transient wire buffers.
    val bytes: Long = minOf(Runtime.getRuntime().maxMemory() / RETAINED_HEAP_DIVISOR, RETAINED_BYTES_CEILING),
    /** How long a record may sit untouched before a sweep drops it. */
    val ttl: Duration = 24.hours,
)

/** The per-script bounds code mode enforces: source and output size, plus optional call and round counts;
 *  protocol frame bytes still bound every step. */
public data class CodeModeScriptBounds(
    val maxSourceChars: Int = DEFAULT_MAX_SOURCE_CHARS,
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS,
    val maxCalls: Int? = null,
    val maxRounds: Int? = null,
)

public data class CodeModeBridgeConfig(
    val runtimes: CodeModeRuntimes,
    val state: CodeModeStateLocation,
    /** Heap-derived byte retention with optional explicit conversation and head count bounds. */
    val retention: CodeModeRetention = CodeModeRetention(),
    val bounds: CodeModeScriptBounds = CodeModeScriptBounds(),
    val clock: Clock = Clock.systemUTC(),
    /** Head-scoped sink for history-degradation lines; uninstalled it is a no-op. */
    val log: LogSink = LogSink { },
    val cellLease: CodeModeCellLease = CodeModeCellLease(),
)

/** Asked at each code-mode save, so the console switch acts on the next one with no restart. */
public fun interface CodeModeJournaling {
    public fun now(): Boolean
}

/** V4-340: where a head keeps its code-mode records: one owner-only file per conversation in [dir]. [legacyFile]
 *  is the single file every daemon before V4-340 kept the whole head in: read once, on the first load, its
 *  conversations carried into [dir], and deleted. */
public data class CodeModeStateLocation(
    val dir: Path,
    val legacyFile: Path,
    /** Whether a save reaches the disk, asked at each save: off keeps the journal in memory only. */
    val journaling: CodeModeJournaling = CodeModeJournaling { true },
    /** Whether an earlier turn's reasoning rides back, asked at each post: replay_reasoning. */
    val replayReasoning: CodeModeReplayReasoning = CodeModeReplayReasoning { true },
)

/** V4-179: the two renderings of a turn's code-mode results that the script itself never sees. */
public data class CodeModeResultRendering(
    /** Per code-mode result id, the follow-up wire items its images render to (the ordinary dialect policy,
     *  rendered once by CodexCodeModeTurnBuilder). The record persists them and the history replays them. */
    val media: Map<String, List<JsonElement>> = emptyMap(),
    /** The same results rendered with the V4-178 markers, for replay identity against a record the previous
     *  daemon wrote (see CodexCodeModeValidation.conflicts). */
    val legacy: List<CodeModeResult> = emptyList(),
    /** Sole-marker typed candidates; admission still requires scoped accepted state and a verified prefix. */
    val clearedCallbacks: Map<String, CodeModeCall> = emptyMap(),
) {
    internal val verifiedCleared: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** Admission still sees all media. Only proved cleared echoes defer to the record's accepted media. */
    internal val replayMedia: Map<String, List<JsonElement>>
        get() {
            val cleared = verifiedCleared.values.flatten().toSet()
            return if (cleared.isEmpty()) media else media.filterKeys { it !in cleared }
        }
}
