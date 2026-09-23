// NEW: v0.4.0 FEATURES.md §4 — the facts that tie a registered Claude Code session to the splice head
// it talks to: splice's launcher marks every process it starts with SPLICE=1 and points it at a local
// head through ANTHROPIC_BASE_URL. Both are read from /proc/<pid>/environ, which only this user's own
// processes expose. The file is STREAMED entry by entry: bytes are kept only while the entry can
// still be one of those two keys, so a foreign entry (a credential) is skipped to its NUL byte by
// byte and is never buffered and never decoded. A process splice did not launch is reported under
// "unknown head" whatever its base URL says.
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

    /** The head port when splice's launcher started this process against a local head, else null. */
    public fun spliceHeadPort(pid: Long): Int? {
        val markers = markers(pid)
        if (markers[SPLICE_MARKER] != "1") return null
        return markers[BASE_URL]?.let { localHead.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun markers(pid: Long): Map<String, String> {
        val scan = EnvironScan(wanted)
        Cancellables.runCatchingCancellable {
            Files.newInputStream(procRoot.resolve(pid.toString()).resolve("environ")).use(scan::read)
        }
        return scan.found
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

    fun read(input: InputStream) {
        val chunk = ByteArray(CHUNK)
        var n = input.read(chunk)
        while (n >= 0) {
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
