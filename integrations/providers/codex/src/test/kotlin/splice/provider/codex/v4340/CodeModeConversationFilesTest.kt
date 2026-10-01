// NEW: V4-340 — what the per-conversation code-mode store keeps on disk: one owner-only file per conversation
// in an owner-only directory, named by a hash of the conversation's key, restored whole at a restart, and
// dropped alone when it does not read back. The layout is asserted here as the operator would find it: the
// README's "What splice keeps on your disk" names the directory, and this names what is in it.
package splice.provider.codex.v4340

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.core.util.SecureFile
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecords
import splice.provider.codex.CodeModeRetention
import splice.provider.codex.CodeModeStateFiles
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodeModeStateWrite
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.codemode.CodeModeResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

class CodeModeConversationFilesTest {

    // Later than the fixture's real-time TTL; persistence tests must not expire on their own.
    private val futureRecordTs = 1_800_000_000_000L

    @TempDir
    lateinit var tempDir: Path

    private val logs = mutableListOf<String>()
    private val state by lazy { CodeModeStateFiles(tempDir.resolve("code-mode")) }

    private fun registry(
        retention: CodeModeRetention = CodeModeRetention(),
        writer: CodeModeStateWrite? = null,
    ) = CodexCodeModeRegistry(
        CodeModeBridgeConfig(
            { error("no script runs in a registry test") },
            CodeModeStateLocation(state.dir, tempDir.resolve("state.json")),
            retention = retention,
            log = LogSink { logs += it },
        ),
        Json { encodeDefaults = true },
        5.minutes,
        writer,
    )

    /** Admits one script of conversation [key] and completes it. */
    private fun CodexCodeModeRegistry.script(key: String, n: Int = 1, output: String = "done $key/$n"): CodeModeRecord {
        val record = CodeModeRecords.of(key, n)
        assertTrue(add(record), "script $n of $key was refused")
        complete(record, output)
        return record
    }

    private fun nameOf(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray())) + ".json"

    private fun CodexCodeModeRegistry.outputs(key: String): List<String?> = completed(key).map(CodeModeRecord::output)

    @Test
    fun `one conversation blocked on disk does not block another conversation`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hold = AtomicBoolean(false)
        val writes = AtomicInteger()
        val writer = CodeModeStateWrite { path, text ->
            writes.incrementAndGet()
            if (hold.get() && path.fileName.toString() == nameOf("alpha")) {
                blocked.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "alpha was not released" }
            }
            SecureFile.writeAtomic0600(path, text)
        }
        val registry = registry(writer = writer)
        val alpha = registry.script("alpha")
        registry.script("beta")
        assertEquals(1, registry.recordsFor("alpha").size, "alpha must survive beta admission")
        hold.set(true)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit { registry.complete(alpha, "done alpha again") }
            if (!blocked.await(5, TimeUnit.SECONDS)) first.get(1, TimeUnit.SECONDS)
            assertTrue(
                blocked.count == 0L,
                "alpha was not writing; calls=${writes.get()}, completed=${registry.completed("alpha").size}",
            )
            val second = threads.submit<Boolean> {
                val prior = registry.completed("beta")
                val added = registry.add(CodeModeRecords.of("beta", 2))
                prior.size == 1 && added
            }
            assertTrue(second.get(3, TimeUnit.SECONDS), "beta must read and persist while alpha writes")
            release.countDown()
            first.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
        assertEquals(1, registry.completed("alpha").size)
        assertEquals(1, registry.completed("beta").size)
    }

    @Test
    fun `parallel admissions of different conversations both survive on disk`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = CodeModeStateWrite { path, text ->
            if (path.fileName.toString() == nameOf("alpha")) {
                blocked.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            SecureFile.writeAtomic0600(path, text)
        }
        val registry = registry(writer = writer)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val alpha = CodeModeRecords.of("alpha", 1, updatedAt = futureRecordTs)
            val first = threads.submit<Boolean> { registry.add(alpha) }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val beta = CodeModeRecords.of("beta", 1, updatedAt = futureRecordTs)
            val second = threads.submit<Boolean> { registry.add(beta) }
            assertTrue(second.get(3, TimeUnit.SECONDS), "beta persists without waiting for alpha")
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
        assertEquals(1, registry.recordsFor("alpha").size)
        assertEquals(1, registry.recordsFor("beta").size, "beta remains live before a restart")
        val restored = registry()
        assertEquals(1, restored.recordsFor("alpha").size)
        assertEquals(1, restored.recordsFor("beta").size)
    }

    @Test
    fun `a newer generation of the same conversation cannot be overwritten by an older write`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstWrite = AtomicBoolean(false)
        val writer = CodeModeStateWrite { path, text ->
            if (path.fileName.toString() == nameOf("alpha") && firstWrite.compareAndSet(true, false)) {
                blocked.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            SecureFile.writeAtomic0600(path, text)
        }
        val registry = registry(writer = writer)
        val alpha = registry.script("alpha")
        firstWrite.set(true)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val older = threads.submit { registry.complete(alpha, "older") }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val newer = threads.submit { registry.complete(alpha, "newer") }
            release.countDown()
            older.get(5, TimeUnit.SECONDS)
            newer.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
        assertEquals(listOf("newer"), registry().outputs("alpha"), "disk retains the latest generation")
    }

    @Test
    fun `a restart restores every conversation whole`() {
        val first = registry()
        first.script("alpha", 1)
        first.script("alpha", 2)
        first.script("beta", 1)
        first.script("gamma", 1)

        val restored = registry()

        assertEquals(listOf("done alpha/1", "done alpha/2"), restored.outputs("alpha"))
        assertEquals(listOf("done beta/1"), restored.outputs("beta"))
        assertEquals(listOf("done gamma/1"), restored.outputs("gamma"))
        assertEquals(emptyList<String>(), logs, "a clean restart logs nothing about the store")
    }

    @Test
    fun `each conversation is one file named by the hash of its key, and no key is in a name`() {
        val registry = registry()
        registry.script("alpha")
        registry.script("beta")

        assertEquals(setOf(nameOf("alpha"), nameOf("beta")), state.files().map { it.fileName.toString() }.toSet())
        assertTrue(state.files().none { "alpha" in it.fileName.toString() || "beta" in it.fileName.toString() })
    }

    @Test
    fun `the files are 0600 in a 0700 directory`() {
        val registry = registry()
        registry.script("alpha")
        registry.script("beta")

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state.dir)))
        assertEquals(2, state.files().size)
        state.files().forEach { file ->
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "$file")
        }
    }

    @Test
    fun `a directory found open is made owner-only again by the next save`() {
        val registry = registry()
        registry.script("alpha")
        Files.setPosixFilePermissions(state.dir, PosixFilePermissions.fromString("rwxr-xr-x"))

        registry.script("beta")

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state.dir)))
    }

    @Test
    fun `a corrupt file drops only its conversation, says so, and is removed`() {
        val first = registry()
        listOf("alpha", "beta", "gamma").forEach { first.script(it) }
        Files.writeString(state.dir.resolve(nameOf("beta")), """{"records":[{"id":""")

        val restored = registry()

        assertEquals(listOf("done alpha/1"), restored.outputs("alpha"))
        assertEquals(emptyList<String>(), restored.outputs("beta"), "the corrupt file's conversation is gone")
        assertEquals(listOf("done gamma/1"), restored.outputs("gamma"))
        assertFalse(Files.exists(state.dir.resolve(nameOf("beta"))), "the unreadable file is removed")
        assertEquals(1, logs.count { nameOf("beta") in it && "unreadable" in it }, "$logs")
        assertEquals(2, state.files().size)
    }

    @Test
    fun `a file that holds another conversation than its name says is dropped, never loaded under the wrong name`() {
        val first = registry()
        first.script("alpha")
        first.script("beta")
        Files.copy(state.dir.resolve(nameOf("alpha")), state.dir.resolve(nameOf("beta")), REPLACE_EXISTING)

        val restored = registry()

        assertEquals(listOf("done alpha/1"), restored.outputs("alpha"))
        assertEquals(emptyList<String>(), restored.outputs("beta"))
        assertTrue(logs.any { nameOf("beta") in it && "exactly the one conversation" in it }, "$logs")
    }

    @Test
    fun `a file that is not one of the store's is left alone`() {
        val first = registry()
        first.script("alpha")
        val stranger = state.dir.resolve("notes.txt")
        Files.writeString(stranger, "an operator's note")

        val restored = registry()

        assertEquals(listOf("done alpha/1"), restored.outputs("alpha"))
        assertEquals("an operator's note", Files.readString(stranger))
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `a conversation with no record and no marker left has no file`() {
        val registry = registry(CodeModeRetention(records = 1))
        registry.script("alpha")
        registry.script("beta")
        registry.script("gamma")

        assertEquals(
            setOf(nameOf("beta"), nameOf("gamma")),
            state.files().map { it.fileName.toString() }.toSet(),
            "alpha's record went, then its marker went with the history's limit, and its file with it",
        )
    }

    @Test
    fun `a restart after a conversation went does not bring it back`() {
        val registry = registry(CodeModeRetention(records = 1))
        registry.script("alpha")
        registry.script("beta")
        registry.script("gamma")

        val restored = registry(CodeModeRetention(records = 1))

        assertEquals(emptyList<String>(), restored.outputs("alpha"))
        assertEquals(emptyList<String>(), restored.outputs("beta"))
        assertEquals(listOf("done gamma/1"), restored.outputs("gamma"))
    }
}
