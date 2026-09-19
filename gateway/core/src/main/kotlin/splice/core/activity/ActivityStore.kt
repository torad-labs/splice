// NEW: V4-130, FEATURES.md 6 — the activity label history: every label the proxy composed for a
// session (ActivityLabel answers Claude Code's 30-second "Describe your most recent action" side query
// locally), in `activity-<day>.jsonl` through ActivityDays. Metadata only: the label is the 3-5 word
// phrase already shown in the client's own status line, never conversation text.
//
// THE UPSTREAM COUNTER. ActivityLabel matches the side query by its verbatim opening sentence, so a
// Claude Code that rewords the prompt sends its label query upstream as an ordinary turn and this store
// stays empty. An empty view must be able to say WHY, so a request that looks like the side query but
// did not match (ActivityLabel.looksLikeSideQuery) is recorded here as an `upstream` row: a history
// with upstream rows and no labels names a client mismatch instead of reading as an idle session.
//
// THE PER-HEAD SWITCH is the activityStoreHeads knob: `*` stores every head, an empty value stores
// none, otherwise a comma-separated list of head keys. A head outside the switch records neither
// labels nor upstream rows, so its empty view means "not stored", which the knob's value states.
package splice.core.activity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.WallClock
import java.nio.file.Path

/** The day-file prefix activity rows are written under. */
internal const val ACTIVITY_PREFIX: String = "activity"

/** The activityStoreHeads value that stores every head. */
public const val ALL_HEADS: String = "*"

/** One row of a session's activity: a composed [label], or ([upstream]) a label query that went to
 *  the model because it did not match the side query's opening. */
public data class ActivityRow(
    val at: Long,
    val session: String,
    val head: String,
    val label: String?,
    val upstream: Boolean,
)

/** Which heads store activity, parsed once from the activityStoreHeads knob value. */
public class ActivityHeads(value: String) {
    private val all = value.trim() == ALL_HEADS
    private val heads = value.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != ALL_HEADS }.toSet()

    public fun stores(head: String): Boolean = all || head in heads
}

/** The console's two activity stores, opened once by the daemon under [activityDir] (the state dir's
 *  ACTIVITY_DIRECTORY) with the activityRetentionDays and activityStoreHeads knob values. The writer
 *  (the console publisher) and the readers (the sessions routes) hold this one instance. */
public class ActivityStores(
    activityDir: Path,
    retentionDays: Int,
    storeHeads: String,
    clock: WallClock = WallClock(System::currentTimeMillis),
) {
    public val edges: MessageEdgeStore = MessageEdgeStore(ActivityDays(activityDir, EDGES_PREFIX, retentionDays, clock))
    public val activity: ActivityStore =
        ActivityStore(ActivityDays(activityDir, ACTIVITY_PREFIX, retentionDays, clock), ActivityHeads(storeHeads))
}

public class ActivityStore(private val days: ActivityDays, private val heads: ActivityHeads) {
    private val json = Json { ignoreUnknownKeys = true }

    public fun label(session: String, head: String, label: String, at: Long) {
        if (heads.stores(head)) days.append(row(at, session, head, "label", JsonPrimitive(label)))
    }

    public fun upstream(session: String, head: String, at: Long) {
        if (heads.stores(head)) days.append(row(at, session, head, "upstream", JsonPrimitive(true)))
    }

    /** Every retained row for [session], oldest first. */
    public fun rows(session: String): List<ActivityRow> =
        days.lines().mapNotNull(::parse).filter { it.session == session }.toList()

    /** One row: the three keys every row has, and the one [key] that says what kind of row it is. */
    private fun row(at: Long, session: String, head: String, key: String, value: JsonPrimitive): String =
        buildJsonObject {
            put("at", at)
            put("session", session)
            put("head", head)
            put(key, value)
        }.toString()

    private fun parse(line: String): ActivityRow? {
        val row = Cancellables
            // ast-grep-ignore: kt-no-silent-result-collapse -- a torn or foreign line in a day file is not a row; it is left out of the view
            .runCatchingCancellable { json.parseToJsonElement(line).jsonObject }
            .getOrNull() ?: return null
        return rowOf(row)
    }

    private fun rowOf(row: JsonObject): ActivityRow? {
        val at = JsonScalars.long(row, "at")
        val session = JsonScalars.str(row, "session")
        if (at == null || session == null) return null
        return JsonScalars.str(row, "head")?.let { head ->
            ActivityRow(at, session, head, JsonScalars.str(row, "label"), row["upstream"] == JsonPrimitive(true))
        }
    }
}
