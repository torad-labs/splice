// NEW: Oct 10, 2026 — the one place that says which files in the state directory are a head's request
// records: the live generation, the rolled one beside it, and the archived generations.
//
// WHY IT IS SHARED. Three readers answer questions about the same files — the kept-data inventory, the
// history window's per-day table, and the control plane's row reader — and each one used to carry its
// own suffix strings. A suffix that changed in the writer and not in one of them does not fail: it
// quietly reads less history than exists, which is the same class of silence that made the perf
// archive's own wall necessary (kt-perf-history-archived).
package splice.core.perf

/** Which files are a head's request records, by name. */
public object PerfFiles {
    /** The live generation every head appends to. The one rolled generation JsonlSink keeps beside
     *  it is this name and `.1`, spelled from this one rather than declared twice. */
    public const val LIVE_SUFFIX: String = "-perf.jsonl"

    /** What an archived generation's name carries before its rotation stamp. */
    public const val ARCHIVED_INFIX: String = "-perf.jsonl-"

    /** A live or rolled generation. */
    public fun isLive(name: String): Boolean = ofSomeHead(name, LIVE_SUFFIX) || ofSomeHead(name, "$LIVE_SUFFIX.1")

    /** An archived generation of some head, judged by the stamp its own live name would carry, so
     *  another head's archive, a stray file and a hand-renamed copy are all refused. */
    public fun isArchived(name: String): Boolean {
        val split = name.lastIndexOf(ARCHIVED_INFIX)
        if (split <= 0) return false
        val liveName = name.substring(0, split) + LIVE_SUFFIX
        return PerfArchiveName(liveName).rotatedAt(name) != null
    }

    /** Whether [name] is a request record at all, live or archived. */
    public fun isRecord(name: String): Boolean = isLive(name) || isArchived(name)

    /** A file named for a head plus [suffix]. The length test keeps a bare suffix out: a file with
     *  no head name in front of it belongs to no head. */
    private fun ofSomeHead(name: String, suffix: String): Boolean =
        name.endsWith(suffix) && name.length > suffix.length
}
