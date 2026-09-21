// NEW: append-only JSONL sink (#924 Phase 3). PerfStats and CompactStats both append one JSON line
// per record and tail-read a bounded trailing window; the append call and the tail-reader were
// duplicated — and readJsonlTail lived in :compact, so :perf reached across a package boundary for
// it (a layer smell). One home, in core.
package splice.core.util

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** V4-133: a hook [JsonlSink.appendLine] calls just before an about-to-rotate write would REPLACE
 *  an existing rolled `.1` generation with a fresh one — the moment that generation's rows are
 *  gone for good. The default, [JsonlSink.NO_ARCHIVE], does nothing: every caller that does not
 *  pass one keeps today's exact behaviour (one generation, then discard). A caller that wants more
 *  than one generation of history — PerfStats' opt-in archive dir is the first — copies [rolled]
 *  out before returning. Exceptions are the caller's problem to avoid: [JsonlSink] runs this under
 *  the same best-effort guard as the append itself, so a broken hook degrades to the old discard
 *  rather than losing the row this append is for. */
public fun interface RotationArchive {
    public fun beforeReplace(rolled: Path)
}

/** The three rotation-policy values [JsonlSink.append]/[JsonlSink.rotateIfOver] need together —
 *  bundled so adding [archive] (V4-133) did not take [JsonlSink.rotateIfOver] past the
 *  LongParameterList wall's 6-parameter ceiling. */
private data class RotatePolicy(val maxBytes: Long, val rotate: Boolean, val archive: RotationArchive)

public object JsonlSink {
    private val locks = ConcurrentHashMap<Path, Any>()
    private val unrotated = AtomicInteger()

    /** The default for every caller that does not name an archive: discard the rolled generation,
     *  exactly as [JsonlSink] always has. */
    public val NO_ARCHIVE: RotationArchive = RotationArchive { }

    /**
     * Append [line], rotating one generation before [maxBytes] can grow without bound.
     *
     * IO-001: the [locks] map only serializes writers within THIS JVM; two head PROCESSES writing
     * the same file coordinate not at all otherwise, so both can read a stale size and overshoot
     * the cap. A cross-process [FileLock] on a sibling `.lock` file (the CredentialLock shape,
     * upstream/CredentialLock.kt) closes that gap.
     *
     * DR-178: that gap used to be closed with a plain blocking `channel.lock()`, justified here by
     * "the critical section is a stat + maybe-rename + append, held for microseconds". The hold is
     * microseconds only when no peer holds the lock — which is the one case the lock is not for.
     * The justification also had the AsyncFileIo argument backwards: running on that lane is the
     * reason a blocking wait is WORSE, not safer. FileIoTask states the contract — the single
     * `splice-file-io` daemon thread "must not block for long (it holds the one lane every other
     * writer queues behind)" — so a wedged writer or a second process parked every perf, usage,
     * compact and state write behind one advisory lock, with no bound at all.
     *
     * So: KeyStore.withStoreLock's bounded tryLock poll (SH-11, same module, same blocking shape),
     * with the opposite degrade. KeyStore FAILS LOUDLY because an unlocked read-modify-write of
     * keys.toml is the lost update it exists to prevent; this is an append-only best-effort
     * telemetry line whose callers already wrap it in runCatchingCancellable, so throwing would
     * just drop the row silently. On expiry it appends anyway, WITHOUT rotating — the rotate is
     * the stat-then-rename that actually needs cross-process exclusion, while the append itself is
     * O_APPEND. The cost is that the file may sit over [maxBytes] until an uncontended append
     * rotates it, which is the same bounded overshoot IO-001 already admits two processes cause,
     * and it is strictly above the status quo of parking the lane forever. [unrotatedAppends]
     * counts the degrade so it is observable rather than silent.
     */
    public fun appendLine(
        file: Path,
        line: String,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        archive: RotationArchive = NO_ARCHIVE,
    ) {
        val normalized = file.toAbsolutePath().normalize()
        synchronized(locks.computeIfAbsent(normalized) { Any() }) {
            val lockPath = normalized.resolveSibling("${normalized.fileName}.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = acquireBounded(channel)
                try {
                    append(file, line, RotatePolicy(maxBytes, rotate = lock != null, archive))
                } finally {
                    lock?.release()
                }
            }
        }
    }

    /** Appends that gave up on the cross-process lock and therefore skipped the rotation check.
     *  Mirrors [AsyncFileIo.droppedCount]: this lane cannot report through a log that it owns, so
     *  a counter is how the degrade stays visible. */
    public fun unrotatedAppends(): Int = unrotated.get()

    /** tryLock poll to [LOCK_WAIT_MS]; null means the budget is spent and the caller appends
     *  UNROTATED. A same-JVM overlap throws instead of queueing, so held-is-held either way —
     *  KeyStore.acquireBounded says the same thing about the same primitive. */
    private fun acquireBounded(channel: FileChannel): FileLock? {
        val deadline = System.currentTimeMillis() + LOCK_WAIT_MS
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (ignored: OverlappingFileLockException) {
                null // held by this JVM via another channel — same contention, same poll
            }
            if (lock != null) return lock
            if (System.currentTimeMillis() >= deadline) {
                unrotated.incrementAndGet()
                return null
            }
            Thread.sleep(LOCK_POLL_MS)
        }
    }

    /**
     * DR-186 / V4-45: four decisions, four members. The durability change (the forced write below)
     * pushed the single-method form past CyclomaticComplexMethod, and the remedy is the gate's own
     * — decompose, never raise the threshold and never suppress. THE ORDER IS THE CONTRACT and is
     * unchanged: size, then rotate, then HEAL, then write. The heal must happen BEFORE the row
     * lands, because that is the 2026-08-25 ENOSPC fix — a heal after the write fuses the fragment
     * with the row that followed it and costs both.
     */
    private fun append(file: Path, line: String, policy: RotatePolicy) {
        val encoded = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        val currentSize = if (Files.exists(file)) Files.size(file) else 0L
        val rotated = rotateIfOver(file, currentSize, encoded.size, policy)
        writeForced(file, healedBytes(file, encoded, currentSize, rotated))
    }

    /** Rolls one generation when this row would take the file past [RotatePolicy.maxBytes], and
     *  answers whether it did — which [healedBytes] needs, since a freshly rotated file has no
     *  tail to heal. A caller that lost the cross-process lock passes [RotatePolicy.rotate] false
     *  and appends unrotated; see [appendLine]'s DR-178 note for why that degrade beats parking
     *  the lane.
     *
     *  V4-133: an existing `.1` is handed to [RotatePolicy.archive] BEFORE the move replaces it —
     *  the one moment its rows are still on disk anywhere. Best-effort: a throwing hook degrades
     *  to the discard every caller already lived with, never to a lost or corrupted append. */
    private fun rotateIfOver(file: Path, currentSize: Long, encodedSize: Int, policy: RotatePolicy): Boolean {
        val rotated = policy.rotate && currentSize > 0 && currentSize + encodedSize > policy.maxBytes
        if (rotated) {
            val rolled = file.resolveSibling("${file.fileName}.1")
            if (Files.exists(rolled)) {
                // A throwing hook degrades to today's discard; it must never block or corrupt the append.
                Cancellables.runCatchingCancellable { policy.archive.beforeReplace(rolled) }
            }
            Files.move(file, rolled, StandardCopyOption.REPLACE_EXISTING)
        }
        return rotated
    }

    /**
     * [encoded], preceded by the newline a torn tail is missing.
     *
     * A torn tail is healed BEFORE the row lands. 2026-08-25 01:23 the disk filled mid-append
     * (ENOSPC): 230 bytes of one perf row were written with no newline, the next row was appended
     * straight onto them, and every reader lost BOTH — the whole row that followed the short write
     * was fused into the fragment. One byte read per append buys the guarantee that a short write
     * costs exactly the row it interrupted.
     */
    private fun healedBytes(file: Path, encoded: ByteArray, currentSize: Long, rotated: Boolean): ByteArray {
        val torn = !rotated && currentSize > 0 && lastByte(file) != NEWLINE_BYTE
        return if (torn) byteArrayOf(NEWLINE_BYTE) + encoded else encoded
    }

    /**
     * FORCED, not merely written.
     *
     * V4-45: Files.write returns once the bytes are in the page cache, and under delayed allocation
     * the file LENGTH can be committed before the data blocks are — so a HOST crash, freeze or
     * power loss leaves the file longer than its contents and the tail reads as a run of NULs. That
     * is the measured signature: runs of multi-hundred zeros inside otherwise valid JSONL, on
     * exactly the three oldest and largest perf files, which are the only ones alive across enough
     * of this box's documented freezes. force(true) flushes data AND metadata, so length and bytes
     * land together or neither does.
     *
     * THIS IS NOT A PROCESS-DEATH DEFENCE and must not be read as one: the page cache outlives a
     * SIGKILL and the kernel still writes it back, so a daemon restart CANNOT produce this damage.
     * Only losing the machine can.
     *
     * force(true) rather than DSYNC, deliberately: DSYNC covers only the data of each write, while
     * the rotation in [rotateIfOver] is a Files.move — a metadata operation whose durability is
     * part of the same question. force(true) is the one option that covers both. Known limit,
     * stated rather than implied: the DIRECTORY entry for a rotation is not itself forced, so a
     * crash in the window between the move and the next append can lose the rename, never the
     * bytes.
     *
     * The loop is load-bearing: `channel.write` is not obliged to consume the buffer, and the
     * `Files.write` this replaced hid that. JsonlSinkDurabilityTest drives it.
     */
    private fun writeForced(file: Path, bytes: ByteArray) {
        FileChannel.open(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun lastByte(file: Path): Byte =
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            if (size <= 0L) return@use NEWLINE_BYTE
            val buf = ByteBuffer.allocate(1)
            ch.position(size - 1)
            if (ch.read(buf) < 1) return@use NEWLINE_BYTE
            buf.flip()
            buf.get()
        }

    /**
     * Read the trailing [maxBytes] of [file] as UTF-8 lines. If the file is larger, the first
     * (possibly partial) line in the window is dropped so every returned line is complete.
     */
    public fun readTail(file: Path, maxBytes: Int): List<String> = readTailAt(file, maxBytes).lines

    /**
     * [readTail]'s lines, plus [TailAt.completeEnd] — the absolute BYTE offset just past the last
     * complete line returned. DR-135: `splice logs --follow` re-baselined a discontinuity from a
     * size STAT taken before this read, which broke the contract in both directions. The trailing
     * tear dropped below is unprinted, so a stat baseline advanced past bytes the operator never
     * saw (the head of that line was lost forever); and because the stat was taken FIRST, a line
     * appended between it and the read was printed here and printed AGAIN by the next delta.
     * One read now yields both the text and the offset, so nothing can be appended between them.
     *
     * The offset is computed on the RAW BYTES, never on the decoded string: a char index would
     * desynchronise the caller's baseline on any multibyte line.
     */
    public fun readTailAt(file: Path, maxBytes: Int): TailAt =
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val size = ch.size()
            if (size <= 0L) return@use TailAt(emptyList(), 0L)
            val readFrom = (size - maxBytes.toLong()).coerceAtLeast(0L)
            val len = (size - readFrom).toInt()
            val buf = ByteBuffer.allocate(len)
            ch.position(readFrom)
            while (buf.hasRemaining()) {
                if (ch.read(buf) < 0) break
            }
            buf.flip()
            val raw = ByteArray(buf.remaining()).also { buf.get(it) }
            val lastNewline = raw.indexOfLast { it == NEWLINE_BYTE }
            val completeEnd = if (lastNewline < 0) readFrom else readFrom + lastNewline + 1
            TailAt(linesOf(raw, readFrom), completeEnd)
        }

    private fun linesOf(raw: ByteArray, readFrom: Long): List<String> {
        val text = String(raw, StandardCharsets.UTF_8)
        // Mid-file start: drop the leading partial line (no newline at all -> nothing complete).
        val complete = if (readFrom > 0L) text.substringAfter('\n', missingDelimiterValue = "") else text
        // Trailing tear: a torn write (disk-full mid-append) leaves bytes with no closing newline;
        // every well-formed record ends in '\n', so drop a non-newline-terminated trailing remnant.
        val whole = if (complete.endsWith('\n')) complete else complete.substringBeforeLast('\n', "")
        return whole.lineSequence().filter { it.isNotEmpty() }.toList()
    }

    // why: PerfStats (V4-133) takes this as its own default so a caller that wants a SMALL cap for
    // a test does not have to duplicate the number — one source, the same law const-single-source
    // enforces for every other repeated constant.
    public const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
    private const val NEWLINE_BYTE = '\n'.code.toByte()

    // DR-178: three orders of magnitude above a healthy hold (stat + maybe-rename + append) and far
    // below the multi-second parking that harms the lane. Deliberately much tighter than
    // KeyStore's 5s, because what waits here is not one CLI command but every other writer in the
    // process. Poll interval matches KeyStore's.
    private const val LOCK_WAIT_MS = 250L
    private const val LOCK_POLL_MS = 25L
}

/** [JsonlSink.readTailAt]'s result: the complete lines in the trailing window, and the absolute
 *  byte offset just past the last of them — the baseline a --follow caller adopts so it neither
 *  re-prints nor skips. [completeEnd] is the window start when the window holds no newline at all. */
public data class TailAt(val lines: List<String>, val completeEnd: Long)
