// NEW: v0.4.0 FEATURES.md §4 — the facts that tie a registered Claude Code session to the splice head
// it talks to: splice's launcher marks every process it starts with SPLICE=1 and points it at a local
// head through ANTHROPIC_BASE_URL. Both are read from /proc/<pid>/environ, which only this user's own
// processes expose. The file is STREAMED entry by entry: bytes are kept only while the entry can
// still be one of those two keys, so a foreign entry (a credential) is skipped to its NUL byte by
// byte and is never buffered and never decoded. The reading is a SessionRoute, decided here because
// only here is "the environment was read" known apart from "it could not be": a readable environment
// without SPLICE=1 is DIRECT whatever its base URL says, a splice launch whose local port a head owns
// is that HEAD, and everything else — unreadable, empty, or a splice launch no head can be found for —
// is UNKNOWN.
package splice.sessions.registry

import splice.core.util.Cancellables
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val SPLICE_MARKER = "SPLICE"
private const val BASE_URL = "ANTHROPIC_BASE_URL"
private const val CHUNK = 8192
private const val NUL: Byte = 0

public class ProcessEnvironment(private val procRoot: Path = Paths.get("/proc")) {
    private val localHead = Regex("^https?://127\\.0\\.0\\.1:(\\d+)")
    private val wanted = listOf("$SPLICE_MARKER=", "$BASE_URL=").map { it.toByteArray() }

    /** How [pid] reaches its provider; [headOf] names the head listening on the local port it read. */
    public fun route(pid: Long, headOf: HeadOfPort): SessionRoute {
        val markers = markers(pid) ?: return SessionRoute.Unknown
        if (markers[SPLICE_MARKER] != "1") return SessionRoute.Direct
        val port = markers[BASE_URL]?.let { localHead.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return port?.let(headOf::invoke)?.let(SessionRoute::Head) ?: SessionRoute.Unknown
    }

    /** The wanted entries, or null when the environment was not READ: the file could not be opened or
     *  failed mid-stream (the entries seen so far are not the environment), or it held no byte at all
     *  (the kernel's answer for a zombie), which is no evidence the process lacks SPLICE=1. */
    private fun markers(pid: Long): Map<String, String>? {
        val scan = EnvironScan(wanted)
        val read = Cancellables.runCatchingCancellable {
            Files.newInputStream(procRoot.resolve(pid.toString()).resolve("environ")).use(scan::read)
        }
        return scan.found.takeIf { read.isSuccess && scan.bytes > 0 }
    }
}

/** One pass over a NUL-separated environment block. An entry is buffered only while every byte so
 *  far is a prefix of a wanted key (after the key fully matched, the value follows); the first
 *  diverging byte drops the buffer and the rest of that entry is discarded unread. */
private class EnvironScan(private val wanted: List<ByteArray>) {
    private val entry = ByteArrayOutputStream()
    private var matching: List<ByteArray> = wanted

    /** The wanted entries seen so far, key to value. */
    val found: MutableMap<String, String> = mutableMapOf()

    /** Every byte the environment held, wanted or not: zero is an environment that was not there. */
    var bytes: Long = 0L
        private set

    fun read(input: InputStream) {
        val chunk = ByteArray(CHUNK)
        var n = input.read(chunk)
        while (n >= 0) {
            bytes += n
            for (i in 0 until n) byte(chunk[i])
            n = input.read(chunk)
        }
        byte(NUL)
    }

    private fun byte(b: Byte) {
        if (b == NUL) {
            if (matching.isNotEmpty() && entry.size() > 0) emit()
            entry.reset()
            matching = wanted
            return
        }
        if (matching.isEmpty()) return
        val at = entry.size()
        matching = matching.filter { key -> at >= key.size || key[at] == b }
        if (matching.isEmpty()) entry.reset() else entry.write(b.toInt())
    }

    private fun emit() {
        val text = entry.toString(Charsets.UTF_8)
        if (matching.any { it.size <= entry.size() }) found[text.substringBefore('=')] = text.substringAfter('=')
    }
}
