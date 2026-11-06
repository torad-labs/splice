package splice.accounts.pool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock

/** Bounded names projected only when the existing Accounts operation already has its authoritative settled rows. */
public class AccountNameSnapshots(private val clock: WallClock = WallClock(System::currentTimeMillis)) {
    @Volatile
    private var current: AccountNameSnapshot? = null

    public fun current(): AccountNameSnapshot? = current?.takeIf {
        clock() - it.sampledAtMs in 0..MAX_AGE_MS
    }

    internal fun publish(rows: List<JsonObject>) {
        val lease = PerfWorkBudget.acquire(WORK_BYTES)
        if (lease == null) { current = null; return }
        var retained = false
        try {
            val projection = NameProjection()
            if (!projection.add(rows)) { current = null; return }
            val snapshot = AccountNameSnapshot(clock(), projection.values)
            lease.retain(snapshot, projection.bytes)
            retained = true
            current = snapshot
        } finally {
            if (!retained) lease.close()
        }
    }
}

/** Keep this snapshot alive while resolving names. No auth, quota, filesystem, or provider calls occur here. */
public class AccountNameSnapshot internal constructor(
    public val sampledAtMs: Long,
    private val values: Map<String, Map<String, AccountNameAlias>>,
) {
    public fun find(head: String, recorded: String): AccountNameAlias? = values[head]?.get(recorded)
}

public data class AccountNameAlias(public val ref: String, public val label: String)

private class NameProjection {
    val values = LinkedHashMap<String, MutableMap<String, AccountNameAlias>>()
    var bytes = SNAPSHOT_OVERHEAD

    fun add(rows: List<JsonObject>): Boolean = rows.size <= MAX_ROWS && rows.all(::row)

    private fun row(row: JsonObject): Boolean {
        val ref = JsonScalars.str(row, "ref") ?: return true
        val label = JsonScalars.str(row, "display_name")
        val heads = row["heads"] as? JsonArray
        if (label == null || heads == null) return false
        val valid = listOf(
            heads.size <= MAX_ACCOUNT_NAME_HEADS,
            ref.length <= MAX_TEXT,
            label.length <= MAX_TEXT,
        ).all { it }
        return valid && heads.all { headValue ->
            val head = JsonScalars.str(headValue)
            head != null && head.length <= MAX_TEXT && aliases(row, head, ref, label)
        }
    }

    private fun aliases(row: JsonObject, head: String, ref: String, label: String): Boolean {
        val pool = JsonScalars.str(row["account_labels"] as? JsonObject, head)
        val place = JsonScalars.str(row["login_place"] as? JsonObject, "id")
        val single = "primary".takeIf { JsonScalars.str(row, "single_login") == "true" && pool == null }
        if (listOfNotNull(pool, place).any { it.length > MAX_TEXT }) return false
        val aliases = listOfNotNull(ref, pool, single, place, place?.let { "native:$it" })
        return aliases.all { key ->
            val size = ENTRY_BYTES + ACCOUNT_ALIAS_CHAR_BYTES * (head.length + key.length + ref.length + label.length)
            if (bytes + size > RETAINED_BYTES) {
                false
            } else {
                bytes += size
                values.getOrPut(head) { LinkedHashMap() }.putIfAbsent(key, AccountNameAlias(ref, label))
                true
            }
        }
    }
}

// Project at most sixty-four authoritative account rows so name resolution cannot retain an unlimited roster.
private const val MAX_ROWS = 64

// Limit aliases to thirty-two heads per row before expanding the account-name lookup map.
private const val MAX_ACCOUNT_NAME_HEADS = 32

// Reject account labels and lookup keys above 256 characters before retaining a snapshot.
private const val MAX_TEXT = 256

// Expire name snapshots after one minute so attribution does not silently use stale settled account names.
private const val MAX_AGE_MS = 60_000L

// Reserve 512 KiB while constructing the bounded alias map and replacing the previous snapshot.
private const val WORK_BYTES = 512L * 1_024

// Limit the published alias map to 256 KiB, leaving construction scratch outside retained state.
private const val RETAINED_BYTES = 256L * 1_024

// Charge 512 bytes per alias-map entry for key, value and collection ownership before text charges.
private const val ENTRY_BYTES = 512L

// Charge four bytes per alias character for retained string content and copied lookup-key content.
private const val ACCOUNT_ALIAS_CHAR_BYTES = 4L

// Charge 4 KiB for the snapshot owner and its maps before adding alias entries.
private const val SNAPSHOT_OVERHEAD = 4_096L
