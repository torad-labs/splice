// DR-9 absence-class arms, split from ConfigServiceTest (detekt LargeClass): the class law —
// NoSuchFileException is the only positive evidence of absence, exists(NOFOLLOW) only disambiguates
// a caught NoSuch — applied to ConfigService's read path (+ streak latch) and its patch/persist
// write guard. See the sibling file for the layer-precedence and per-mtime latch arms.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText

class ConfigServiceAbsenceTest {

    @TempDir
    lateinit var tmp: Path

    // DR-9 (codex class law + latch trap): an inaccessible config symlink has no readable mtime, so
    // the per-mtime CAS cannot dedup — the old exists() pre-gate hid this as silent absence, and a
    // naive direct-read would log on EVERY merge-per-request call. Streak contract: first
    // inaccessible read logs once, repeats stay one, a healthy read re-arms, the next episode logs
    // again.
    @Test
    fun `an inaccessible config symlink logs one discard per streak then re-arms - DR-9`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        Files.createDirectories(paths.configFile.parent)
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val target = Files.writeString(externalDir.resolve("config.json"), "{\"maxQueued\": 50}")
        Files.createSymbolicLink(paths.configFile, target)
        val denied = PosixFilePermissions.fromString("---------")
        val open = PosixFilePermissions.fromString("rwx------")
        try {
            Files.setPosixFilePermissions(externalDir, denied)
            repeat(3) { svc.getConfig() }
            assertEquals(1, logged.count { it.contains("unreadable") }, "one line per streak, got $logged")

            Files.setPosixFilePermissions(externalDir, open)
            assertEquals(50L, svc.layers().file["maxQueued"], "the healthy read must see the target again")

            Files.setPosixFilePermissions(externalDir, denied)
            repeat(2) { svc.getConfig() }
        } finally {
            Files.setPosixFilePermissions(externalDir, open)
        }
        assertEquals(2, logged.count { it.contains("unreadable") }, "a new episode logs again, got $logged")
    }

    // DR-9: NoSuchFile alone is not absence. A truly absent config.json is the quiet fresh install;
    // a DANGLING config symlink throws the same NoSuch but its entry exists — that logs.
    @Test
    fun `a dangling config symlink logs its discard while true absence stays quiet - DR-9`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        repeat(2) { svc.getConfig() }
        assertTrue(logged.isEmpty(), "a fresh install must not warn: $logged")

        Files.createDirectories(paths.configFile.parent)
        Files.createSymbolicLink(paths.configFile, tmp.resolve("never-created.json"))
        svc.getConfig()
        assertEquals(1, logged.count { it.contains("unreadable") }, "a dangling link is present: $logged")
    }

    // DR-9 write guard (codex repro): the operator's config.json is a symlink whose target parent
    // lost read. The old exists() pre-gate read that as "no file", seeded an empty base, and
    // writeAtomic0600 atomically REPLACED the symlink with a fresh file — knobs on the real target
    // shadowed, the link destroyed, while the CLI reported success. The persist must abort loudly;
    // the runtime layer still applies.
    @Test
    fun `patch never replaces an inaccessible config symlink - DR-9`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        Files.createDirectories(paths.configFile.parent)
        val externalDir = Files.createDirectories(tmp.resolve("external"))
        val target = Files.writeString(externalDir.resolve("config.json"), "{\"effort\":\"high\"}")
        Files.createSymbolicLink(paths.configFile, target)
        Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("---------"))
        try {
            val result = svc.patch(mapOf("maxQueued" to 77))
            assertEquals(77L, result.applied["maxQueued"], "the runtime layer must still apply")
            assertTrue(
                Files.isSymbolicLink(paths.configFile),
                "the operator's config symlink must survive an inaccessible-target persist",
            )
            assertTrue(logged.any { it.contains("refusing to rewrite") }, "the abort must log: $logged")
        } finally {
            Files.setPosixFilePermissions(externalDir, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals("{\"effort\":\"high\"}", target.readText(), "the real target keeps its knobs")
    }

    // DR-9 (codex half-fix guard): an exists(NOFOLLOW) PRE-gate still reads false when config.json
    // sits DIRECTLY under an untraversable parent — no symlink anywhere — so that half-fix passes
    // every symlink arm above while silently serving defaults here. Only the gateless direct stat
    // reaches the AccessDenied; this arm is what kills the NOFOLLOW-pre-gate mutant.
    @Test
    fun `an untraversable state dir logs one discard, not silent defaults - DR-9`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        Files.createDirectories(paths.configFile.parent)
        Files.writeString(paths.configFile, "{\"maxQueued\": 50}")
        try {
            Files.setPosixFilePermissions(paths.configFile.parent, PosixFilePermissions.fromString("---------"))
            repeat(3) { svc.getConfig() }
        } finally {
            Files.setPosixFilePermissions(paths.configFile.parent, PosixFilePermissions.fromString("rwx------"))
        }
        assertEquals(1, logged.count { it.contains("unreadable") }, "one streak line, not silence: $logged")
    }

    // DR-9 write guard, dangling shape: the entry exists even though the read throws NoSuch —
    // seeding would replace the operator's (repairable) link with a fresh file. Refuse instead.
    @Test
    fun `patch refuses to seed over a dangling config symlink - DR-9`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        Files.createDirectories(paths.configFile.parent)
        Files.createSymbolicLink(paths.configFile, tmp.resolve("never-created.json"))

        svc.patch(mapOf("maxQueued" to 77))

        assertTrue(Files.isSymbolicLink(paths.configFile), "a dangling config link must not be replaced")
        assertTrue(logged.any { it.contains("refusing to rewrite") }, "the abort must log: $logged")
    }
}

// DR-73 (invariant audit): the persist path's strict-read refusal embedded the raw parse
// throwable — config.json bytes rode "JSON input:" excerpts into daemon.log. The refusal now
// travels as an allowlisted FileSystemException whose reason is already rendered safe, so the
// log line keeps the whole refusing-to-rewrite story while the bytes stay withheld.
class ConfigPersistDiagnosticsTest {

    @Test
    fun `persist diagnostics never quote config bytes and keep the refusal story - DR-73`(@TempDir tmp2: Path) {
        val sentinel = "SENTINEL-CONFIG-BYTES"
        val paths = StatePaths(baseOverride = tmp2.resolve("state"))
        val logged = mutableListOf<String>()
        val svc = ConfigService(paths, envReader = { null }, log = { logged += it })
        Files.createDirectories(paths.configFile.parent)
        Files.writeString(paths.configFile, """{"maxQueued": "$sentinel""")

        svc.patch(mapOf("maxQueued" to 77))

        val joined = logged.joinToString("\n")
        assertTrue(!joined.contains(sentinel), "config bytes must never ride diagnostics: $joined")
        assertTrue(logged.any { it.contains("refusing to rewrite") }, "the refusal diagnostic survives: $joined")
    }
}
