// NEW: v0.4.0 FEATURES.md §4 — how a registered Claude Code session joins the splice head it talks to: by
// the LAUNCH splice made. The launcher declares every session it starts (pid, process birth, head, base URL)
// in the owner-only launch roster (LaunchOwners), and a pid joins a head only when its record is there, its birth
// matches the live process, and the head owning the declared port is the head declared. Nothing is inferred
// from a process environment any more. The environment is read for ONE fact, and only when there is no launch
// record: a readable environment without SPLICE=1 is a session splice never launched (DIRECT, V4-293). The
// file is STREAMED entry by entry: bytes are kept only while the entry can still be one of the two keys, so a
// foreign entry (a credential) is skipped to its NUL byte by byte and is never buffered and never decoded.
// Everything else — unreadable, empty, a splice launch with no record — is UNKNOWN ("unknown head").
package splice.sessions.registry

import splice.core.config.StatePaths
import splice.core.process.LaunchOwners
import splice.core.util.Cancellables
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val SPLICE_MARKER = "SPLICE"
private const val CHUNK = 8192
private const val NUL: Byte = 0

public class ProcessEnvironment(
    private val procRoot: Path = Paths.get("/proc"),
    private val owners: LaunchOwners = LaunchOwners(StatePaths().stateDir),
) {
    private val localHead = Regex("^https?://127\\.0\\.0\\.1:(\\d+)")
    private val wanted = listOf("$SPLICE_MARKER=").map { it.toByteArray() }

    /** How [pid] reaches its provider: the head its launch declared, when [headOf] still names that head on the
     *  declared port; DIRECT for a readable environment of a process splice never launched; else UNKNOWN. */
    public fun route(pid: Long, headOf: HeadOfPort): SessionRoute {
        val launch = owners.read(pid) ?: return unlaunched(pid)
        if (launch.kind != "session") return SessionRoute.Unknown
        val port = localHead.find(launch.baseUrl)?.groupValues?.get(1)?.toIntOrNull()
        val head = port?.let(headOf::invoke)
        return if (head == launch.head) SessionRoute.Head(head) else SessionRoute.Unknown
    }

    /** No launch record: a session whose environment was read and carries no SPLICE=1 is Direct, and a splice
     *  launch nothing recorded (or one whose environment could not be read) is Unknown. */
    private fun unlaunched(pid: Long): SessionRoute {
        val read = markers(pid) ?: return SessionRoute.Unknown
        return if (read[SPLICE_MARKER] == "1") SessionRoute.Unknown else SessionRoute.Direct
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
