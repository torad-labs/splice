package splice.head.perf

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import splice.core.util.ElapsedClock
import splice.core.util.PerfWorkBudget
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.abs

/** Owner methods run under RequestEvidenceFiles' admission monitor; only commit performs file I/O. */
internal class RequestEvidenceCoverage(
    private val directory: Path,
    private val clock: WallClock,
    private val elapsed: ElapsedClock,
) : AutoCloseable {
    private val active = LinkedHashMap<String, EvidenceProducer>()
    private val intervals = ArrayList<RequestEvidenceInterval>()
    private val heads = LinkedHashSet<String>()
    private val lossEpochs = LinkedHashMap<String, Long>()
    private val retentionEpochs = LinkedHashMap<String, Long>()
    private var lease: PerfWorkBudget.Lease? = null
    private var lock: FileChannel? = null
    private var restored = false
    private var scopeComplete = true
    private var lastAsOf = 0L
    private val retiredHeads = LinkedHashSet<String>()
    private val prefixes = RequestEvidencePrefixes()
    private val expected = LinkedHashSet<String>()

    @Synchronized
    fun start(head: String, bootId: String): Boolean {
        val invalid = bootId.length > RequestEvidenceProofFormat.MAX_PROOF_BOOT_CHARS || head in active
        val full = heads.size >= RequestEvidenceProofFormat.MAX_HEADS && head !in heads
        if (invalid || full) {
            scopeComplete = false
            return false
        }
        if (lease == null) {
            lease = PerfWorkBudget.acquire(STATE_BYTES) ?: return false
            checkNotNull(lease).retain(this, STATE_BYTES)
        }
        heads += head
        active[head] = EvidenceProducer(bootId, clock(), elapsed())
        return true
    }

    @Synchronized
    fun fault(head: String?) {
        val selected = active.filterKeys { head == null || it == head }
        for ((name, producer) in selected) {
            lossEpochs[name] = (lossEpochs[name] ?: 0L) + 1L
            intervals.removeAll { it.head == name && it.bootId == producer.bootId }
            producer.fromMs = clock()
            producer.monotonicMs = elapsed()
            producer.faulted = true
        }
    }

    @Synchronized
    fun retired(file: Path) {
        if (lease == null) return
        expected.remove(file.fileName.toString())
        val head = RequestEvidenceFormat.headOf(file.fileName.toString())
        val selected = head?.let(::listOf) ?: heads.toList()
        if ((heads + selected).size > RequestEvidenceProofFormat.MAX_HEADS) {
            scopeComplete = false
            fault(null)
            return
        }
        heads.addAll(selected)
        selected.forEach { retentionEpochs[it] = (retentionEpochs[it] ?: 0L) + 1L }
        if (head == null) retiredHeads.addAll(heads) else retiredHeads += head
        intervals.removeAll { head == null || it.head == head }
        fault(head)
    }

    @Synchronized
    fun stop(head: String) { active[head]?.stopMs = clock() }
    fun appended(path: Path, line: String) {
        if (lease != null) {
            expected += path.fileName.toString()
            prefixes.appended(path, line)
        }
    }

    @Synchronized
    fun enabled(): Boolean = active.isNotEmpty()

    @Synchronized
    fun boundary(): Long {
        val now = clock()
        for ((head, producer) in active) {
            val drift = abs((now - producer.fromMs) - (elapsed() - producer.monotonicMs))
            if (now < producer.fromMs || drift > CLOCK_DRIFT_MS) fault(head)
        }
        return now
    }

    fun commit(untilMs: Long, files: List<Path>) {
        open()
        restore()
        synchronized(this) {
            val owners = files.mapNotNull { RequestEvidenceFormat.headOf(it.fileName.toString()) }.toSet()
            require((heads + owners).size <= RequestEvidenceProofFormat.MAX_HEADS)
            heads.addAll(owners)
        }
        val available = files.map { it.fileName.toString() }.toSet()
        expected.filter { it !in available }.forEach { retired(directory.resolve(it)) }
        val captured = prefixes.capture(files)
        val proof = snapshot(untilMs, captured)
        val encoded = Json.encodeToString(proof)
        if (encoded.length * UTF8_BYTES > RequestEvidenceProofFormat.MAX_BYTES) {
            throw IOException(
                "evidence proof size limit",
            )
        }
        SecureFile.writeAtomic0600(directory.resolve(RequestEvidenceProofFormat.COVERAGE_FILE), encoded)
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        synchronized(this) { active.entries.removeIf { (it.value.stopMs ?: Long.MAX_VALUE) <= untilMs } }
    }

    @Synchronized
    private fun snapshot(untilMs: Long, captured: List<RequestEvidencePrefix>): RequestEvidenceProof {
        if (untilMs < lastAsOf) {
            intervals.clear()
            fault(null)
        }
        intervals.removeAll { it.head in retiredHeads }
        retiredHeads.clear()
        for ((head, producer) in active) {
            if (producer.faulted) intervals.removeAll { it.head == head && it.bootId == producer.bootId }
            if (untilMs > producer.fromMs) replace(head, producer, minOf(untilMs, producer.stopMs ?: untilMs))
        }
        lastAsOf = untilMs
        return RequestEvidenceProof(
            asOfMs = untilMs,
            heads = heads.toList(),
            intervals = intervals.toList(),
            prefixes = captured,
            lossEpochs = lossEpochs.toMap(),
            retentionEpochs = retentionEpochs.toMap(),
            scopeComplete = scopeComplete,
        )
    }

    private fun replace(head: String, producer: EvidenceProducer, untilMs: Long) {
        intervals.removeAll { it.head == head && it.bootId == producer.bootId && it.fromMs == producer.fromMs }
        intervals += RequestEvidenceInterval(head, producer.bootId, producer.fromMs, untilMs)
        while (intervals.size > RequestEvidenceProofFormat.MAX_INTERVALS) intervals.removeAt(0)
        producer.faulted = false
    }

    private fun open() {
        if (lock != null) return
        if (SecureFile.ownerOnlyDirectory(
                directory,
            ) != null
        ) {
            throw IOException("evidence coverage directory permissions")
        }
        val opened = FileChannel.open(
            directory.resolve("coverage.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        try {
            val held = try { opened.tryLock() != null } catch (_: OverlappingFileLockException) { false }
            if (!held) throw IOException("evidence coverage writer busy")
            lock = opened
        } finally {
            if (lock !== opened) opened.close()
        }
    }

    private fun restore() {
        if (restored) return
        val path = directory.resolve(RequestEvidenceProofFormat.COVERAGE_FILE)
        if (Files.exists(path)) {
            val bytes = Files.newInputStream(path).use { it.readNBytes(RequestEvidenceProofFormat.MAX_BYTES + 1) }
            if (bytes.size > RequestEvidenceProofFormat.MAX_BYTES) throw IOException("evidence proof size limit")
            val old = RequestEvidenceProofFormat.decode(bytes)
            require(old.schemaVersion == 1 && old.intervals.size <= RequestEvidenceProofFormat.MAX_INTERVALS)
            restore(old)
        }
        restored = true
    }

    private fun restore(old: RequestEvidenceProof) {
        synchronized(this) {
            require((heads + old.heads).size <= RequestEvidenceProofFormat.MAX_HEADS)
            heads.addAll(old.heads)
            old.lossEpochs.forEach { (head, count) -> lossEpochs[head] = count + (lossEpochs[head] ?: 0L) }
            old.retentionEpochs.forEach { (head, count) ->
                retentionEpochs[head] = count + (retentionEpochs[head] ?: 0L)
            }
            intervals.addAll(old.intervals)
            expected.addAll(old.prefixes.map { it.fileName })
            lastAsOf = old.asOfMs
        }
    }

    @Synchronized
    override fun close() {
        lock?.close()
        lock = null
        prefixes.clear()
        expected.clear()
        active.clear()
        intervals.clear()
        heads.clear()
        lossEpochs.clear()
        retentionEpochs.clear()
        retiredHeads.clear()
        PerfWorkBudget.release(this)
        lease = null
    }
}

private data class EvidenceProducer(
    val bootId: String,
    var fromMs: Long,
    var monotonicMs: Long,
    var stopMs: Long? = null,
    var faulted: Boolean = false,
)

// Reserve 512 KiB for producer intervals, head membership and loss epochs until the evidence owner closes.
private const val STATE_BYTES = 512L * 1_024

// Invalidate coverage when wall and monotonic progress disagree by more than one second instead of filling a clock gap.
private const val CLOCK_DRIFT_MS = 1_000L

// Three UTF-8 bytes per UTF-16 code unit conservatively bounds the encoded receipt before publication.
private const val UTF8_BYTES = 3L
