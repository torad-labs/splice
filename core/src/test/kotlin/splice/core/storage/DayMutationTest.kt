package splice.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DayMutationTest {
    @Test
    fun `a foreign process excludes purge and a released store lock permits it`(@TempDir dir: Path) {
        val index = Files.writeString(dir.resolve("synthetic-2026-09-18.jsonl"), "{}\n")
        val pack = Files.writeString(dir.resolve("${index.fileName}.bodies"), "synthetic")
        val child = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp",
            System.getProperty("java.class.path"),
            ForeignDayLockHolder::class.java.name,
            dir.resolve("synthetic.days.lock").toString(),
        ).redirectErrorStream(true).start()
        val files = DayFiles(dir, "synthetic")
        try {
            assertEquals("locked", child.inputStream.bufferedReader().readLine())
            val refused = files.purge()
            assertTrue(refused is DayPurge.Unlisted)
            assertTrue((refused as DayPurge.Unlisted).failure.message.orEmpty().contains("day mutation lock timed out"))
            assertTrue(Files.exists(index))
            assertTrue(Files.exists(pack))
        } finally {
            child.outputStream.close()
            child.waitFor(5, TimeUnit.SECONDS)
            child.destroyForcibly()
        }
        assertEquals(0, child.exitValue())
        val purged = files.purge()
        assertTrue(purged is DayPurge.Listed && purged.failed.isEmpty())
        assertEquals(0, files.inventory(7).days)
    }

    @Test
    fun `a foreign directory fence excludes mutations from every head`(@TempDir dir: Path) {
        val index = Files.writeString(dir.resolve("synthetic-2026-09-18.jsonl"), "{}\n")
        val child = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"),
            "-cp",
            System.getProperty("java.class.path"),
            ForeignDayLockHolder::class.java.name,
            dir.resolve("directory.days.lock").toString(),
        ).redirectErrorStream(true).start()
        try {
            assertEquals("locked", child.inputStream.bufferedReader().readLine())
            val refused = DayFiles(dir, "synthetic").purge()
            assertTrue(refused is DayPurge.Unlisted, "a different head cannot pass the shared body-budget fence")
            assertTrue(Files.exists(index), "the directory fence preserves the other head's day")
        } finally {
            child.outputStream.close()
            child.waitFor(5, TimeUnit.SECONDS)
            child.destroyForcibly()
        }
        assertEquals(0, child.exitValue())
        assertTrue(DayFiles(dir, "synthetic").purge() is DayPurge.Listed)
    }

    @Test
    fun `purge cannot unlink a companion between record encoding and index publication`(@TempDir dir: Path) {
        val days = ActivityDays(dir, "synthetic", 7, WallClock { 1_789_725_600_000L })
        val encoded = CountDownLatch(1)
        val publish = CountDownLatch(1)
        days.append(
            DayRecord { file ->
                Files.writeString(file.resolveSibling("${file.fileName}.bodies"), "synthetic payload")
                encoded.countDown()
                check(publish.await(5, TimeUnit.SECONDS))
                "{\"body\":\"synthetic reference\"}\n".toByteArray()
            },
        )
        assertTrue(encoded.await(5, TimeUnit.SECONDS))
        val entered = CountDownLatch(1)
        val files = DayFiles(dir, "synthetic")
        val purge = CompletableFuture.supplyAsync {
            entered.countDown()
            files.purge()
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { purge.get(200, TimeUnit.MILLISECONDS) }
        } finally {
            publish.countDown()
            assertTrue(AsyncFileIo.drain())
            assertTrue(purge.get(5, TimeUnit.SECONDS) is DayPurge.Listed)
        }
        assertEquals(0L, files.inventory(7).rows)
        assertEquals(0, files.inventory(7).days)
    }
}

/** A synthetic second JVM holds exactly the stable lock used by the day-store mutators. */
object ForeignDayLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                println("locked")
                System.out.flush()
                System.`in`.read()
            }
        }
    }
}
