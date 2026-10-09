// NEW: bounded restart attribution retains identities, never perf rows or credential material.
package splice.app.sources

import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.app.sources.PerfAccountRows.Generation
import splice.usage.perf.PerfRow
import java.io.IOException
import java.nio.file.Path

// why: matches the live carrying/session registries, while targeted seeds preserve requested older sessions.
private const val ACCOUNT_SESSIONS = 4096

// why: bounds session/account strings and map ownership independently of source history size.
private const val ACCOUNT_INDEX_BYTES = 4L * 1024 * 1024

// why: the existing perf cache's generation ceiling also caps one account-only seed's source opens.
private const val ACCOUNT_SOURCE_GENERATIONS = 64

// why: covers the fixed 64 KiB line reader, 8 KiB digest reader, maps and parser bookkeeping.
private const val INDEX_OVERHEAD_BYTES = 96L * 1024

// why: covers map/node/scalar objects and String headers, apart from their charged character arrays.
private const val ACCOUNT_ENTRY_BYTES = 512L

/** Seed a requested session set once, newest generations first. Identical requests decode no history;
 * product receipts authorize only new spans. Clipped history remains distinct from no recorded account. */
internal class PerfSessionAccountIndex(
    private val limitSessions: Int = ACCOUNT_SESSIONS,
    private val limitBytes: Long = ACCOUNT_INDEX_BYTES,
    private val scanBytes: Long = PERF_CACHE_BYTES,
    private val scanGenerations: Int = ACCOUNT_SOURCE_GENERATIONS,
) {
    data class Snapshot(val accounts: Map<String, String>, val complete: Boolean, val indexed: Set<String>)
    private data class Known(val ts: Long, val account: String?, val priority: Int)

    private val latest = LinkedHashMap<String, Known>()
    private var entryBytes = 0L
    private var requested = emptySet<String>()
    private var requestedBytes = 0L
    private var archiveDigest: ByteArray? = null
    private var live = emptyList<Generation>()
    private var complete = true
    private var priority = 0
    private val rows = PerfAccountRows(scanBytes)

    val retainedBytes: Long
        get() = INDEX_OVERHEAD_BYTES + entryBytes + requestedBytes + live.sumOf(Generation::bytes)
    val retainedSessions: Int get() = requested.size

    init {
        require(limitSessions > 0 && limitBytes >= INDEX_OVERHEAD_BYTES)
        require(scanBytes > 0 && scanGenerations > 0)
    }

    fun accounts(
        sessions: Set<String>,
        archives: List<Path>,
        current: List<Path>,
        decode: PerfLineDecode,
    ): Snapshot {
        require(current.size <= 2) { "only the two live generations retain read cursors" }
        select(sessions)
        val indexed = PerfLineDecode { raw -> decode.decode(raw).also(::rememberLine) }
        repeat(2) {
            try {
                refresh(archives, current, indexed)
                return snapshot()
            } catch (_: IOException) {
                // Unreadable or racing history cannot authorize either an identity or an absence claim.
                clear()
                complete = false
            }
        }
        return snapshot()
    }

    fun account(session: String, archives: List<Path>, current: List<Path>, decode: PerfLineDecode): String? =
        accounts(setOf(session), archives, current, decode).accounts[session]

    private fun select(sessions: Set<String>) {
        val ids = linkedSetOf<String>()
        var bytes = INDEX_OVERHEAD_BYTES
        (sessions + requested).forEach { id ->
            val charge = ACCOUNT_ENTRY_BYTES + id.length * PERF_CHAR_BYTES
            val room = ids.size < limitSessions && bytes + charge <= limitBytes / 2
            if (id.isNotBlank() && room) {
                ids.add(id)
                bytes += charge
            }
        }
        if (ids != requested) {
            clear()
            requested = ids
            requestedBytes = bytes - INDEX_OVERHEAD_BYTES
        }
    }

    private fun snapshot(): Snapshot = Snapshot(
        latest.mapNotNull { (id, known) -> known.account?.let { id to it } }.toMap(),
        complete,
        requested,
    )

    private fun refresh(archives: List<Path>, current: List<Path>, decode: PerfLineDecode) {
        val archived = rows.fingerprint(archives)
        val next = current.map { path -> Generation(path, rows.version(path)) }
        val pairs = next.zip(live)
        val sameSources = archived.contentEquals(archiveDigest) && pairs.size == next.size
        val continues = sameSources && continuing(pairs)
        if (continues) {
            live = pairs.mapIndexed { index, (after, before) ->
                priority = next.size - index - 1
                if (after.version == before.version) before else rows.scan(after, before, before.complete, decode)
            }
        } else {
            seed(archives, next, decode)
        }
        if (next.any { rows.version(it.path) != it.version } || !rows.fingerprint(archives).contentEquals(archived)) {
            throw IOException("perf sources changed during account indexing")
        }
        archiveDigest = archived
    }

    private fun continuing(pairs: List<Pair<Generation, Generation>>): Boolean {
        val growth = pairs.sumOf { (after, before) ->
            ((after.version?.size ?: 0L) - (before.version?.size ?: 0L)).coerceAtLeast(0L)
        }
        return growth <= scanBytes && pairs.all { (after, before) -> rows.appendable(before, after) }
    }

    private fun seed(archives: List<Path>, next: List<Generation>, decode: PerfLineDecode) {
        clear()
        live = next
        var remaining = scanBytes
        var opened = 0
        val scanned = mutableMapOf<Path, Generation>()
        val ordered = next.asReversed().asSequence() +
            archives.asReversed().asSequence().map { Generation(it, rows.version(it)) }
        ordered.forEachIndexed { index, source ->
            priority = index
            val size = source.version?.size ?: return@forEachIndexed
            if (opened >= scanGenerations || remaining == 0L) {
                if (size > 0L) complete = false
                return@forEachIndexed
            }
            val span = minOf(size, remaining)
            val start = size - span
            if (start > 0L) complete = false
            val cursor = rows.scan(source, null, start, decode)
            if (next.any { it.path == source.path }) scanned[source.path] = cursor
            remaining -= span
            opened++
        }
        live = next.map { scanned[it.path] ?: it }
    }

    private fun rememberLine(line: PerfCachedLine) {
        val row = line.row?.takeUnless { line.probe } ?: return
        val session = row.transcript.sessionId?.takeIf { it in requested } ?: return
        val account = row.facts.account?.takeUnless { it.isBlank() || it == "?" || it == OWN_SIGN_IN_LABEL } ?: return
        remember(row, session, account)
    }

    private fun remember(row: PerfRow, session: String, account: String) {
        val previous = latest[session]
        if (previous != null && older(previous, row)) return
        val base = ACCOUNT_ENTRY_BYTES + session.length * PERF_CHAR_BYTES
        val prior = previous?.let { base + (it.account?.length ?: 0) * PERF_CHAR_BYTES } ?: 0L
        val charge = base + account.length * PERF_CHAR_BYTES
        val fits = retainedBytes - prior + charge <= limitBytes
        latest[session] = Known(row.ts, account.takeIf { fits }, priority)
        entryBytes += (if (fits) charge else base) - prior
        if (!fits) complete = false
    }

    private fun older(previous: Known, row: PerfRow): Boolean =
        if (previous.ts == row.ts) previous.priority < priority else previous.ts > row.ts

    private fun clear() {
        latest.clear()
        entryBytes = 0L
        archiveDigest = null
        live = emptyList()
        complete = true
    }
}
