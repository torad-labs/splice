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

    // DR-1 second residual (codex-splice review, 2026-08-30): the item demanded
    // `Files.createDirectories(globalSessions)` "so the feature is not silently inert when the
    // operator has never run plain claude". link() instead returned on a missing registry, which is
    // the FRESH-MACHINE case — the one where cross-head visibility is most likely to be wanted and
    // least likely to be noticed missing, because nothing anywhere says it did not happen.
    @Test
    fun `a missing global registry is created rather than silently disabling sharing`(@TempDir tmp: Path) {
        val local = Files.createDirectories(tmp.resolve("local-sessions"))
        val global = tmp.resolve("global-sessions") // never created: a machine that never ran plain claude
        Files.writeString(local.resolve("a.json"), "local-a")

        SessionRegistryLink().link(global, local)

        assertTrue(Files.isDirectory(global), "the global registry must be created, not skipped")
        assertTrue(Files.isSymbolicLink(local), "the head's sessions dir must end up linked")
        assertEquals(global, Files.readSymbolicLink(local))
        assertEquals("local-a", Files.readString(global.resolve("a.json")), "entries migrate into it")
    }

    @Test
    fun `an unusable global registry path declines out loud instead of silently`(@TempDir tmp: Path) {
        val local = Files.createDirectories(tmp.resolve("local-sessions"))
        val global = tmp.resolve("global-sessions")
        Files.writeString(global, "not a directory")
        val log = mutableListOf<String>()

        SessionRegistryLink().link(global, local, log = { log += it })

        assertTrue(Files.isDirectory(local), "the real local directory must survive")
        assertTrue(log.any { it.contains("global-sessions") }, "the decline must name the path, got $log")
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

    // DR-39 (codex): a regular file squatting at the head's `sessions` path was preserved — right
    // — but SILENTLY, contradicting the materializer's "link() logs its own declines" contract.
    // The registry quietly stayed private and the cross-head visibility hunt started elsewhere.
    @Test
    fun `unexpected non-directory local sessions content declines out loud`(@TempDir tmp: Path) {
        val global = Files.createDirectories(tmp.resolve("global-sessions"))
        val local = tmp.resolve("sessions")
        Files.writeString(local, "operator content")
        val log = mutableListOf<String>()

        SessionRegistryLink().link(global, local, log = { log += it })

        assertEquals("operator content", Files.readString(local), "unexpected content is preserved")
        assertFalse(Files.isSymbolicLink(local), "the squatter must not be replaced")
        assertTrue(
            log.any { it.contains("sessions registry NOT linked") },
            "the preserved squatter must be loud: $log",
        )
    }

    // DR-104: the bare `finally Files.deleteIfExists(staged)` let a cleanup throw REPLACE the
    // in-flight outcome — after a successful move/migration, a delete failure converted the
    // success into a thrown (and caller-logged) not-linked failure. The staged leftover is a
    // courtesy; the link outcome must stand.
    @Test
    fun `a staged-cleanup throw never converts a successful link into a failure - DR-104`(@TempDir tmp: Path) {
        val cfg = Files.createDirectories(tmp.resolve("cfg"))
        val global = Files.createDirectories(tmp.resolve("global-sessions"))
        val dst = cfg.resolve("sessions")
        val fake = object : SessionRegistryFs {
            override fun move(source: Path, target: Path, vararg options: CopyOption): Path {
                // Pretend the move landed but leave `source` in place, then make the cleanup
                // impossible: the finally's deleteIfExists now throws AccessDenied.
                Files.setPosixFilePermissions(cfg, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"))
                return target
            }
            override fun createSymbolicLink(link: Path, target: Path): Path = Files.createSymbolicLink(link, target)
        }
        try {
            SessionRegistryLink(fake).link(global, dst, log = { })
        } finally {
            Files.setPosixFilePermissions(cfg, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
        // Reaching here without a throw IS the assertion — pre-fix the AccessDeniedException from
        // the staged cleanup escaped link() in place of the successful outcome.
        assertTrue(true)
    }
}
