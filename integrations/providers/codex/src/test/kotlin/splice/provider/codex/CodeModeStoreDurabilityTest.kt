package splice.provider.codex

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeStateDelta
import splice.provider.codex.state.CodeModeStateJournal
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class CodeModeStoreDurabilityTest {
    @TempDir
    lateinit var dir: Path

    private val codec = Json { encodeDefaults = true }
    private val location get() = CodeModeStateLocation(dir.resolve("state"), dir.resolve("legacy.json"))
    private fun store(
        writer: CodeModeStateWrite = CodeModeStateWrite { path, text -> CodeModeStateJournal.write(path, text) },
    ) = CodexCodeModeStore(location, codec, {}, writer = writer)

    @Test
    fun `the older directory reader leaves every new journal byte untouched`() {
        val record = CodeModeRecords.of("alpha", 1)
        val store = store()
        store.load()
        store.save(listOf(record), emptyList())
        record.origin.source = "new source"
        store.save(listOf(record), emptyList(), changedRecord = record)
        val file = CodeModeStateFiles(location.dir).files().single()
        val before = Files.readAllBytes(file)
        runOlderDirectoryReader()
        assertTrue(Files.exists(file), "downgrade deleted the new journal")
        assertTrue(before.contentEquals(Files.readAllBytes(file)))
        assertEquals(record.origin.source, store().load().records.single().source)
    }

    @Test
    fun `upgrade downgrade and upgrade selects the newest record or expiry and prefers new format on ties`() {
        val record = CodeModeRecords.of("alpha", 1).snapshot()
        val scenarios = listOf(
            CodeModePersistedState(records = listOf(record.copy(source = "older jar", updatedAt = 30))) to
                CodeModePersistedState(records = listOf(record.copy(source = "new jar", updatedAt = 20))),
            CodeModePersistedState(records = listOf(record.copy(source = "older jar", updatedAt = 20))) to
                CodeModePersistedState(records = listOf(record.copy(source = "new jar", updatedAt = 30))),
            CodeModePersistedState(records = listOf(record.copy(source = "older jar", updatedAt = 30))) to
                CodeModePersistedState(records = listOf(record.copy(source = "new jar", updatedAt = 30))),
            CodeModePersistedState(expired = listOf(CodeModeExpiredSnapshot("alpha", "gone", setOf(record.id), 40))) to
                CodeModePersistedState(records = listOf(record.copy(source = "new jar", updatedAt = 30))),
            CodeModePersistedState(records = listOf(record.copy(source = "older jar", updatedAt = 30))) to
                CodeModePersistedState(
                    expired = listOf(CodeModeExpiredSnapshot("alpha", "gone", setOf(record.id), 40)),
                ),
        )
        val expected = listOf("older jar", "new jar", "new jar", null, null)
        scenarios.forEachIndexed { index, (old, new) ->
            val seed = store().also { it.load() }
            seed.save(listOf(CodeModeRecords.of("alpha", 1)), emptyList())
            val stem = CodeModeStateFiles(location.dir).files().single().fileName.toString().substringBefore('.')
            val oldFile = location.dir.resolve("$stem.json")
            val newFile = location.dir.resolve("$stem.jsonl")
            Files.writeString(oldFile, codec.encodeToString(old) + "\n")
            Files.writeString(newFile, codec.encodeToString(new) + "\n")
            val restored = store().load()
            assertEquals(expected[index], restored.records.singleOrNull()?.source, "round trip $index")
            if (expected[index] == null) assertEquals("gone", restored.expired.single().lastDigest)
            assertFalse(Files.exists(oldFile), "selected state must migrate before deleting the older format")
            assertEquals(
                codec.encodeToString(restored),
                codec.encodeToString(CodeModeStateJournal.read(newFile, codec)),
            )
        }
    }

    @Test
    fun `different states with equal record clocks retain the file actually written later`() {
        val record = CodeModeRecords.of("alpha", 1)
        val seed = store().also { it.load() }
        seed.save(listOf(record), emptyList())
        val stem = CodeModeStateFiles(location.dir).files().single().fileName.toString().substringBefore('.')
        val oldFile = location.dir.resolve("$stem.json")
        val newFile = location.dir.resolve("$stem.jsonl")
        Files.writeString(
            oldFile,
            codec.encodeToString(
                CodeModePersistedState(records = listOf(record.snapshot().copy(source = "later old jar"))),
            ),
        )
        Files.writeString(
            newFile,
            codec.encodeToString(
                CodeModePersistedState(records = listOf(record.snapshot().copy(source = "earlier new jar"))),
            ),
        )
        Files.setLastModifiedTime(oldFile, FileTime.fromMillis(20))
        Files.setLastModifiedTime(newFile, FileTime.fromMillis(10))
        assertEquals("later old jar", store().load().records.single().source)
    }

    @Test
    fun `a refused legacy migration preserves its file and an equal-state retry finishes migration`() {
        val record = CodeModeRecords.of("alpha", 1)
        val seed = store().also { it.load() }
        seed.save(listOf(record), emptyList())
        val original = CodeModeStateFiles(location.dir).files().single()
        val oldFile = original.resolveSibling(original.fileName.toString().substringBefore('.') + ".json")
        if (original != oldFile) Files.move(original, oldFile)
        var refuse = true
        val migrating = store(
            CodeModeStateWrite { path, text ->
                if (refuse) throw IOException("synthetic refused migration")
                CodeModeStateJournal.write(path, text)
            },
        )
        assertEquals(record.id, migrating.load().records.single().id)
        assertTrue(Files.exists(oldFile))
        refuse = false
        migrating.save(listOf(record), emptyList(), changedRecord = record)
        assertFalse(Files.exists(oldFile))
        assertEquals(record.id, store().load().records.single().id)
        assertTrue(migrating.pendingKeys.isEmpty())
    }

    @Test
    fun `a failed boot migration stays read only after another conversation writes`() {
        val now = 1_790_000_000_000L
        val records = listOf("alpha", "beta").map { key ->
            CodeModeRecords.of(key, 1, now).apply {
                phase = CodeModePhase.COMPLETED
                progress.output = "completed $key"
            }
        }
        store().also { it.load() }.save(records, emptyList())
        val files = CodeModeStateFiles(location.dir).files().associateBy {
            CodeModeStateJournal.read(it, codec).records.single().key
        }
        val newFile = files.getValue("alpha")
        val oldFile = newFile.resolveSibling(newFile.fileName.toString().substringBefore('.') + ".json")
        Files.move(newFile, oldFile)
        var blocked = true
        var writes = 0
        var forces = 0
        val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        val config = CodeModeBridgeConfig({ error("no worker is needed") }, location, clock = clock)
        val registry = CodexCodeModeRegistry(
            config,
            codec,
            1.hours,
            writer = CodeModeStateWrite { path, text ->
                writes++
                if (blocked && path == newFile) throw IOException("synthetic migration disk full")
                CodeModeStateJournal.write(path, text) { _, channel ->
                    forces++
                    channel.force(true)
                }
            },
        )
        blocked = false
        registry.complete(registry.recordsFor("beta").single(), "updated beta")
        val beforeWrites = writes
        val beforeForces = forces
        assertEquals(null, registry.owner("alpha", "", emptySet(), emptySet()))
        assertEquals(listOf(records.first().id), registry.recordsFor("alpha").map { it.id })
        val alpha = registry.completed("alpha").single()
        assertFalse(registry.expiredHistory("alpha", "", emptySet()))
        assertTrue(registry.resultOwners("alpha", emptySet()).unknown.isEmpty())
        assertEquals(beforeWrites, writes, "read-only lookups must not retry a boot migration")
        assertEquals(beforeForces, forces, "read-only lookups must not force a boot migration")
        assertTrue(Files.exists(oldFile), "only the next real alpha write may migrate its file")
        registry.complete(alpha, "updated alpha")
        assertFalse(Files.exists(oldFile))
        assertEquals("updated alpha", store().load().records.single { it.key == "alpha" }.output)
    }

    @Test
    fun `terminal disposal retains a failed completion until its forced retry settles`() {
        val blocked = AtomicBoolean()
        val record = CodeModeRecords.of("alpha", 1)
        val store = store(retryWriter(blocked))
        store.load()
        store.save(listOf(record), emptyList())
        record.progress.output = "final completion not yet forced"
        record.phase = CodeModePhase.COMPLETED
        blocked.set(true)
        assertThrows<CodeModePersistenceException> { store.save(listOf(record), emptyList(), changedRecord = record) }
        assertFalse(store.release(), "an unsaved completion cannot lose its retry index")
        val records = mutableListOf(record)
        val sweep = terminalSweep(store, records, mutableListOf())
        assertFalse(sweep.isCancelled)
        assertEquals(listOf(record), records, "failed terminal save keeps the live completion")
        blocked.set(false)
        awaitCancelled(sweep)
        assertTrue(records.isEmpty(), "hot ownership ends only after persistence settles")
        assertEquals(record.progress.output, store().load().records.single().output)
        assertTrue(store.settled)
    }

    @Test
    fun `terminal disposal retries a failed purge instead of restoring its old record`() {
        val blocked = AtomicBoolean()
        val record = CodeModeRecords.of("alpha", 1)
        val store = store(retryWriter(blocked))
        store.load()
        store.save(listOf(record), emptyList())
        val marker = CodeModeExpiredSnapshot("alpha", "expired-first", setOf(record.id), record.progress.updatedAt)
        val markers = mutableListOf(marker)
        blocked.set(true)
        assertThrows<CodeModePersistenceException> { store.save(emptyList(), markers) }
        assertFalse(store.release(), "failed purge retains the durable index until disk agrees")
        val sweep = terminalSweep(store, mutableListOf(), markers)
        assertFalse(sweep.isCancelled)
        assertEquals(listOf(marker), markers, "terminal retries do not expire the unforced marker")
        blocked.set(false)
        awaitCancelled(sweep)
        val restored = store().load()
        assertTrue(restored.records.isEmpty())
        assertEquals(listOf(marker), restored.expired)
        assertTrue(markers.isEmpty(), "durable expiry evidence no longer needs hot owners")
    }

    private fun retryWriter(blocked: AtomicBoolean): CodeModeStateWrite = CodeModeStateWrite { path, text ->
        if (blocked.get()) throw IOException("synthetic terminal force refused")
        CodeModeStateJournal.write(path, text)
    }

    private fun terminalSweep(
        store: CodexCodeModeStore,
        records: MutableList<CodeModeRecord>,
        markers: MutableList<CodeModeExpiredSnapshot>,
    ): ScheduledFuture<*> {
        val monitor = ReentrantLock()
        val timed = CodeModeTimedSweep(
            monitor,
            records,
            CodeModeExpiredHistory(markers, null),
            { error("terminal retry must not run TTL housekeeping") },
            store,
            CodeModeBridgeConfig({ error("terminal retry never starts a worker") }, location),
            50.milliseconds,
        )
        monitor.withLock { timed.arm() }
        val future = CodeModeTimedSweep::class.java.getDeclaredField("running")
            .apply { isAccessible = true }.get(timed) as ScheduledFuture<*>
        timed.finish {}
        return future
    }

    private fun awaitCancelled(sweep: ScheduledFuture<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!sweep.isCancelled) {
            check(System.nanoTime() < deadline) { "terminal retry did not settle before its deadline" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
    }

    // Frozen discovery and corruption disposition from c36070bd0^ CodexCodeModeStore.
    // Its full-cell decoder has a mandatory records field even when unknown keys are ignored.
    private fun runOlderDirectoryReader() {
        val oldCodec = Json { ignoreUnknownKeys = true }
        val files = Files.list(location.dir).use { entries ->
            entries.filter { Regex("[0-9a-f]{64}\\.json").matches(it.fileName.toString()) }.toList()
        }
        files.forEach { file ->
            try {
                val lines = Files.readAllLines(file)
                assertTrue(oldCodec.decodeFromString<CodeModePersistedState>(lines.first()).records.isNotEmpty())
                lines.drop(1).forEach { line ->
                    assertEquals("alpha", oldCodec.decodeFromString<CodeModeStateDelta>(line).key)
                }
            } catch (_: IllegalArgumentException) {
                Files.delete(file)
            }
        }
    }

    @Test
    fun `a complete append with a refused force cannot survive source rollback and a later save`() {
        var refuse = false
        val writer = CodeModeStateWrite { path, text ->
            if (refuse) {
                check(text.startsWith("{\"key\":"))
                Files.writeString(path, text + "\n", APPEND)
                throw IOException("synthetic force refused after complete newline")
            }
            CodeModeStateJournal.write(path, text)
        }
        val record = CodeModeRecords.of("alpha", 1).apply { origin.source = "a;" }
        val store = store(writer)
        store.load()
        store.save(listOf(record), emptyList())
        record.origin.source = "a;b;"
        refuse = true
        assertThrows<CodeModePersistenceException> { store.save(listOf(record), emptyList(), changedRecord = record) }
        record.origin.source = "a;"
        record.error = "lost"
        refuse = false
        store.save(listOf(record), emptyList(), changedRecord = record)
        val restored = store().load().records.single()
        assertEquals("a;", restored.source, "failed append must not become durable through a later field patch")
        assertEquals("lost", restored.error)
    }

    @Test
    fun `a retry repairs uncertain disk even when every live field equals the last successful state`() {
        var refuse = false
        val record = CodeModeRecords.of("alpha", 1).apply { origin.source = "a;" }
        val store = store(
            CodeModeStateWrite { path, text ->
                if (refuse) {
                    Files.writeString(path, text + "\n", APPEND)
                    throw IOException("synthetic force refused")
                }
                CodeModeStateJournal.write(path, text)
            },
        )
        store.load()
        store.save(listOf(record), emptyList())
        record.origin.source = "a;b;"
        refuse = true
        assertThrows<CodeModePersistenceException> { store.save(listOf(record), emptyList(), changedRecord = record) }
        record.origin.source = "a;"
        refuse = false
        store.save(listOf(record), emptyList(), retryOnly = true)
        assertEquals("a;", store().load().records.single().source)
        assertTrue(store.pendingKeys.isEmpty())
    }

    @Test
    fun `a detached changed record cannot resurrect after its durable purge`() {
        val first = CodeModeRecords.of("alpha", 1)
        val second = CodeModeRecords.of("alpha", 2)
        val store = store()
        store.load()
        store.save(listOf(first, second), emptyList())
        store.save(listOf(second), emptyList())
        first.progress.output = "late callback"
        store.save(listOf(second), emptyList(), dirtyKeys = setOf("alpha"), changedRecord = first)
        assertEquals(listOf(second.id), store().load().records.map { it.id })
    }

    @Test
    fun `a failed purge followed by a changed insertion retries the whole current conversation`() {
        var refuse = false
        val first = CodeModeRecords.of("alpha", 1)
        val second = CodeModeRecords.of("alpha", 2)
        val store = store(
            CodeModeStateWrite { path, text ->
                if (refuse) throw IOException("synthetic refused purge")
                CodeModeStateJournal.write(path, text)
            },
        )
        store.load()
        store.save(listOf(first), emptyList())
        val marker = CodeModeExpiredSnapshot("alpha", "expired-first", setOf(first.id), first.progress.updatedAt)
        refuse = true
        assertThrows<CodeModePersistenceException> { store.save(emptyList(), listOf(marker)) }
        refuse = false
        store.save(listOf(second), listOf(marker), changedRecord = second)
        assertEquals(listOf(second.id), store().load().records.map { it.id })
        assertFalse(store.pendingKeys.contains("alpha"))
    }
}
