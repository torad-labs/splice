package splice.core.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path

class SessionRegistryLinkTest {

    @Test
    fun `a destination collision performs no partial migration`(@TempDir tmp: Path) {
        val local = tmp.resolve("local-sessions")
        val global = tmp.resolve("global-sessions")
        Files.createDirectories(local)
        Files.createDirectories(global)
        Files.writeString(local.resolve("a.json"), "local-a")
        Files.writeString(local.resolve("z.key"), "local-z")
        Files.writeString(global.resolve("z.key"), "global-z")

        SessionRegistryLink().link(global, local)

        assertTrue(Files.isDirectory(local), "a refused migration must retain the real local directory")
        assertEquals("local-a", Files.readString(local.resolve("a.json")))
        assertEquals("local-z", Files.readString(local.resolve("z.key")))
        assertFalse(Files.exists(global.resolve("a.json")), "nothing may move before collision preflight passes")
        assertEquals("global-z", Files.readString(global.resolve("z.key")))
    }

    @Test
    fun `a later transfer failure rolls back earlier transfers`(@TempDir tmp: Path) {
        val local = tmp.resolve("local-sessions")
        val global = tmp.resolve("global-sessions")
        Files.createDirectories(local)
        Files.createDirectories(global)
        Files.writeString(local.resolve("a.json"), "local-a")
        Files.writeString(local.resolve("z.key"), "local-z")
        val failingFs = object : SessionRegistryFs {
            override fun move(source: Path, target: Path, vararg options: CopyOption): Path {
                if (source.fileName.toString() == "z.key") throw IOException("injected transfer failure")
                return Files.move(source, target, *options)
            }

            override fun createSymbolicLink(link: Path, target: Path): Path =
                Files.createSymbolicLink(link, target)
        }

        SessionRegistryLink(failingFs).link(global, local)

        assertEquals("local-a", Files.readString(local.resolve("a.json")))
        assertEquals("local-z", Files.readString(local.resolve("z.key")))
        assertFalse(Files.exists(global.resolve("a.json")))
        assertFalse(Files.exists(global.resolve("z.key")))
    }

    // DR-1 residual: a migration that declines (refusal or rollback) used to return false into a
    // caller that discarded it — the operator saw "sessions not shared" with zero breadcrumb.
    @Test
    fun `a refused migration logs the refusal and names the collision`(@TempDir tmp: Path) {
        val local = tmp.resolve("local-sessions")
        val global = tmp.resolve("global-sessions")
        Files.createDirectories(local)
        Files.createDirectories(global)
        Files.writeString(local.resolve("z.key"), "local-z")
        Files.writeString(global.resolve("z.key"), "global-z")
        val log = mutableListOf<String>()

        SessionRegistryLink().link(global, local, log = { log += it })

        assertTrue(
            log.any { it.contains("REFUSED") && it.contains("z.key") },
            "the refusal must be logged with the colliding entry, got $log",
        )
    }

    @Test
    fun `a rolled-back migration logs the underlying failure`(@TempDir tmp: Path) {
        val local = tmp.resolve("local-sessions")
        val global = tmp.resolve("global-sessions")
        Files.createDirectories(local)
        Files.createDirectories(global)
        Files.writeString(local.resolve("a.json"), "local-a")
        Files.writeString(local.resolve("z.key"), "local-z")
        val failingFs = object : SessionRegistryFs {
            override fun move(source: Path, target: Path, vararg options: CopyOption): Path {
                if (source.fileName.toString() == "z.key") throw IOException("injected transfer failure")
                return Files.move(source, target, *options)
            }

            override fun createSymbolicLink(link: Path, target: Path): Path =
                Files.createSymbolicLink(link, target)
        }
        val log = mutableListOf<String>()

        SessionRegistryLink(failingFs).link(global, local, log = { log += it })

        assertTrue(
            log.any { it.contains("rolled") && it.contains("injected transfer failure") },
            "the rollback must log its cause, got $log",
        )
    }

    @Test
    fun `failed replacement creation preserves the stale symlink for retry`(@TempDir tmp: Path) {
        val oldTarget = Files.createDirectories(tmp.resolve("old-sessions"))
        val global = Files.createDirectories(tmp.resolve("global-sessions"))
        val local = Files.createSymbolicLink(tmp.resolve("sessions"), oldTarget)
        val failingFs = object : SessionRegistryFs {
            override fun move(source: Path, target: Path, vararg options: CopyOption): Path =
                Files.move(source, target, *options)

            override fun createSymbolicLink(link: Path, target: Path): Path =
                throw IOException("injected symlink failure")
        }

        assertThrows(IOException::class.java) { SessionRegistryLink(failingFs).link(global, local) }
        assertTrue(Files.isSymbolicLink(local))
        assertEquals(oldTarget, Files.readSymbolicLink(local))

        SessionRegistryLink().link(global, local)
        assertTrue(Files.isSymbolicLink(local))
        assertEquals(global, Files.readSymbolicLink(local))
    }
}
