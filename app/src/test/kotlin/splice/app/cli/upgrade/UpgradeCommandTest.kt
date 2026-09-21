// `splice upgrade` (v0.4.0, FEATURES.md §5) against a fake share dir and a file:// release: a
// verified release lands in its own directory and the live jar repoints to it; a failed
// verification activates nothing; an edited wrapper is refreshed, saved and its diff printed; rollback
// repoints at the previous release; config and credentials are never touched.
package splice.app.cli.upgrade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

private const val STOCK = "#!/bin/sh\necho stock\n"
private const val PATCHED = "#!/bin/sh\necho patched\n"
private const val NEWER = "#!/bin/sh\necho newer\n"

class UpgradeCommandTest {

    private val calls = mutableListOf<List<String>>()
    private var unitRestarts = 0
    private var verbRestarts = 0
    private var inflightAnswers = ArrayDeque<InflightRead>()
    private var inflightAfter: InflightRead = InflightRead.NoDaemon
    private var reportedVersion = "9.9.9"
    private var unitActive = true

    /** What /health reports after a restart: the release `current` points at, unless a test pins it. */
    private var servingVersion: ((Path) -> String?)? = null

    /** [unitJar] is what the fake user unit's ExecStart names; null = no unit supervises this install. */
    private fun process(gh: Int = 0, unitJar: Path? = null) = UpgradeProcess { cmd, _ ->
        calls += cmd
        when (cmd[0]) {
            "gh" -> UpgradeExit(gh, "")
            "diff" -> UpgradeExit(1, "--- release\n+++ live\n-echo stock\n+echo patched\n")
            "systemctl" -> systemctl(cmd, unitJar)
            else -> java(cmd)
        }
    }

    private fun systemctl(cmd: List<String>, unitJar: Path?): UpgradeExit = when (cmd[2]) {
        "show" -> UpgradeExit(0, unitJar?.let { "java -jar $it daemon" } ?: "")
        "is-active" -> UpgradeExit(0, if (unitJar != null && unitActive) "active\n" else "inactive\n")
        else -> UpgradeExit(0, "").also { unitRestarts++ }
    }

    private fun java(cmd: List<String>) = when {
        cmd.last() == "version" -> UpgradeExit(0, "splice $reportedVersion\n")
        cmd.contains("doctor") -> UpgradeExit(0, "{}")
        else -> UpgradeExit(0, "")
    }

    private fun env(home: Path, base: String) = EnvReader { name ->
        when (name) {
            "SPLICE_SHARE_DIR" -> home.resolve("share").toString()
            "SPLICE_BIN_DIR" -> home.resolve("bin").toString()
            "SPLICE_RELEASE_BASE_URL" -> base
            "HOME" -> home.toString()
            else -> null
        }
    }

    private fun command(
        home: Path,
        base: String,
        fetch: UpgradeFetch = JdkUpgradeFetch(),
        gh: Int = 0,
        supervised: Boolean = false,
        maxWaitMs: Long = 60_000,
    ): UpgradeCommand {
        val env = env(home, base)
        val unitJar = home.resolve("share/splice.jar").takeIf { supervised }
        val daemon = UpgradeDaemon(
            process(unitJar = unitJar),
            { inflightAnswers.removeFirstOrNull() ?: inflightAfter },
            restartVerb = {
                verbRestarts++
                true
            },
            healthVersion = { servingVersion?.invoke(home) ?: link(home, "current") },
            pollMs = 1,
            maxWaitMs = maxWaitMs,
            confirmPollMs = 1,
        )
        return UpgradeCommand(
            env = env,
            java = "java",
            release = UpgradeRelease(fetch, process(gh), "java"),
            wrapper = UpgradeWrapper(process()),
            daemon = daemon,
            layout = UpgradeLayout(env),
        )
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A flat install as install.sh wrote it before 0.4.0, plus config and a credential elsewhere. */
    private fun flatInstall(home: Path, shim: String = STOCK): Map<Path, ByteArray> {
        val share = Files.createDirectories(home.resolve("share"))
        Files.writeString(share.resolve("splice.jar"), "old-jar")
        Files.writeString(share.resolve("splice-launch"), shim)
        val config = Files.createDirectories(home.resolve(".config/splice"))
        Files.writeString(config.resolve("splice.toml"), "[daemon]\ncontrol_port = 1\n")
        Files.writeString(config.resolve("codex.json"), "{\"token\":\"secret\"}")
        return listOf(config.resolve("splice.toml"), config.resolve("codex.json"))
            .associateWith { Files.readAllBytes(it) }
    }

    /** install.sh 0.4.0 keeps a pristine copy of the installed release's shim. */
    private fun pristine(home: Path, shim: String = STOCK) {
        val dir = Files.createDirectories(home.resolve("share/releases/0.3.2"))
        Files.writeString(dir.resolve("splice-launch"), shim)
    }

    /** A release directory served over file://; [sums] overrides the published checksums. */
    private fun release(home: Path, shim: String = NEWER, sums: Map<String, ByteArray>? = null): String {
        val dir = Files.createDirectories(home.resolve("release"))
        val assets = mapOf("splice.jar" to "new-jar".toByteArray(), "splice-launch" to shim.toByteArray())
        assets.forEach { (name, bytes) -> Files.write(dir.resolve(name), bytes) }
        val lines = (sums ?: assets).entries.joinToString("") { (name, bytes) -> "${sha(bytes)}  $name\n" }
        Files.writeString(dir.resolve("sha256sums.txt"), lines)
        return dir.toUri().toString().trimEnd('/')
    }

    private fun captured(block: () -> Boolean): Pair<Boolean, String> {
        val buffer = ByteArrayOutputStream()
        val prev = System.out
        System.setOut(PrintStream(buffer, true))
        val ok = try {
            block()
        } finally {
            System.setOut(prev)
        }
        return ok to buffer.toString()
    }

    private fun assertIntact(files: Map<Path, ByteArray>) =
        files.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(Files.readAllBytes(path)), "$path changed") }

    @Test
    fun `a version that is not a normalized SemVer segment never becomes a path`(@TempDir home: Path) {
        flatInstall(home)
        reportedVersion = "current"
        assertFalse(command(home, release(home)).upgrade(emptyList()))
        assertEquals(0, stagingDirs(home), "the staged candidate was discarded")
        assertEquals(emptySet<String>(), releaseDirs(home), "no directory named after the bad version line")
        reportedVersion = "9.9.9"
        assertFalse(command(home, release(home)).upgrade(listOf("--to", "../x")))
        assertFalse(Files.exists(home.resolve("share/x")), "--to never escaped releases/")
        assertEquals(0, stagingDirs(home), "a refused --to never fetched anything")
    }

    private fun releaseDirs(home: Path): Set<String> = Files.list(home.resolve("share/releases")).use { entries ->
        entries.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList().toSet()
    }

    private fun read(home: Path, name: String): String = Files.readString(home.resolve("share").resolve(name))

    private fun link(home: Path, name: String): String =
        Files.readSymbolicLink(home.resolve("share/releases").resolve(name)).toString()

    @Test
    fun `a verified release lands in its own directory, the live jar repoints, a pristine wrapper is refreshed`(
        @TempDir home: Path,
    ) {
        val intact = flatInstall(home)
        pristine(home)
        val (ok, out) = captured { command(home, release(home), supervised = true).upgrade(listOf("--to", "v9.9.9")) }
        assertTrue(ok, out)
        assertEquals("new-jar", read(home, "releases/9.9.9/splice.jar"))
        assertTrue(Files.isSymbolicLink(home.resolve("share/splice.jar")))
        assertEquals("new-jar", read(home, "splice.jar"))
        assertEquals(NEWER, read(home, "splice-launch"))
        assertFalse(Files.isSymbolicLink(home.resolve("share/splice-launch")), "the shim stays a real file")
        assertEquals("9.9.9", link(home, "current"))
        assertEquals("0.3.2", link(home, "previous"))
        assertEquals("old-jar", read(home, "releases/0.3.2/splice.jar"), "the flat jar was recorded")
        assertEquals(1 to 0, unitRestarts to verbRestarts, "the active unit that names this jar was restarted")
        assertTrue(out.contains("serving 9.9.9"), out)
        assertTrue(calls.any { it.first() == "java" && it.last() == "doctor" }, "doctor ran on the new jar")
        assertTrue(calls.none { it.first() == "gh" }, "a file:// base needs no gh")
        assertIntact(intact)
    }

    @Test
    fun `a failed checksum or attestation activates nothing and touches no config`(@TempDir home: Path) {
        val intact = flatInstall(home)
        val tampered = mapOf("splice.jar" to "tampered".toByteArray(), "splice-launch" to "x".toByteArray())
        val bad = release(home, sums = tampered)
        val (ok, out) = captured { command(home, bad).upgrade(listOf("--to", "v9.9.9")) }
        assertFalse(ok)
        assertTrue(out.contains("sha256 verification FAILED"), out)
        val good = Path.of(java.net.URI(release(home)))
        val fake = UpgradeFetch { url -> Files.readAllBytes(good.resolve(url.substringAfterLast('/'))) }
        val remote = "https://example.invalid/releases/download/v9.9.9"
        val (attested, out2) = captured { command(home, remote, fetch = fake, gh = 1).upgrade(emptyList()) }
        assertFalse(attested)
        assertTrue(out2.contains("gh"), out2)
        assertEquals("old-jar", read(home, "splice.jar"))
        assertFalse(Files.isSymbolicLink(home.resolve("share/splice.jar")))
        assertFalse(Files.exists(home.resolve("share/releases/9.9.9")))
        val leftovers = Files.list(home.resolve("share/releases")).use { entries ->
            entries.filter { it.fileName.toString().startsWith(".staging") }.count()
        }
        assertEquals(0, leftovers)
        assertEquals(0, unitRestarts + verbRestarts)
        assertIntact(intact)
    }

    /** The shim is version-locked to the jar (the launch handshake), so an edited one is refreshed
     *  too — saved beside its release with the diff printed, never silently lost (review 2026-09-14). */
    @Test
    fun `an edited wrapper is refreshed, its copy saved and diff printed, and rollback repoints at previous`(
        @TempDir home: Path,
    ) {
        val intact = flatInstall(home, shim = PATCHED)
        pristine(home)
        val base = release(home)
        inflightAnswers = ArrayDeque(listOf(InflightRead.Count(2), InflightRead.Count(1), InflightRead.Count(0)))
        val (ok, out) = captured { command(home, base).upgrade(listOf("--to", "v9.9.9")) }
        assertTrue(ok, out)
        assertTrue(out.contains("edited since") && out.contains("+echo patched"), out)
        assertEquals(NEWER, read(home, "splice-launch"), "the new release's shim is live")
        assertEquals(PATCHED, read(home, "releases/0.3.2/splice-launch.edited"), "the edit is saved")
        assertEquals(2, out.split("in flight").size - 1, "waited two polls for the turns in flight")
        val (back, out2) = captured { command(home, base).upgrade(listOf("--rollback", "--now")) }
        assertTrue(back, out2)
        assertEquals("old-jar", read(home, "splice.jar"))
        assertEquals("0.3.2", link(home, "current"))
        assertEquals("9.9.9", link(home, "previous"))
        assertEquals(STOCK, read(home, "splice-launch"), "rollback activates that release's shim")
        assertEquals(PATCHED, read(home, "releases/0.3.2/splice-launch.edited"), "the saved edit stays")
        assertEquals(0 to 2, unitRestarts to verbRestarts, "no unit names this install's jar: the verb restarts")
        assertIntact(intact)
    }

    /** A flat install has no pristine shim: the live one is recorded with its jar and refreshed, so
     *  launches keep working and a rollback has a shim to copy (review 2026-09-14). */
    @Test
    fun `a flat install's shim is recorded, refreshed, and restored by rollback - review 2026-09-14`(
        @TempDir home: Path,
    ) {
        flatInstall(home)
        val base = release(home)
        val (ok, out) = captured { command(home, base).upgrade(listOf("--to", "v9.9.9", "--now")) }
        assertTrue(ok, out)
        assertEquals(NEWER, read(home, "splice-launch"))
        assertEquals(STOCK, read(home, "releases/0.3.2/splice-launch"), "the flat shim was recorded with its jar")
        assertTrue(out.contains("refreshed from the release") && !out.contains("saved at"), out)
        val (back, out2) = captured { command(home, base).upgrade(listOf("--rollback", "--now")) }
        assertTrue(back, out2)
        assertEquals(STOCK, read(home, "splice-launch"), "the recorded shim came back with its jar")
    }

    /** The restart is judged by what /health serves afterwards, never by an exit code: a unit whose
     *  file names the jar but is inactive does not own the daemon; a daemon still on the old version
     *  is named (review 2026-09-14). */
    @Test
    fun `an inactive unit takes the verb path, and a daemon still serving the old version is reported`(
        @TempDir home: Path,
    ) {
        flatInstall(home)
        pristine(home)
        unitActive = false
        val (ok, out) = captured { command(home, release(home), supervised = true).upgrade(listOf("--now")) }
        assertTrue(ok, out)
        assertEquals(0 to 1, unitRestarts to verbRestarts, "a loaded but inactive unit does not own the daemon")
        unitActive = true
        servingVersion = { "9.9.9" }
        val (back, out2) = captured {
            command(home, release(home), supervised = true).upgrade(listOf("--rollback", "--now"))
        }
        assertFalse(back, out2)
        assertTrue(out2.contains("still serves 9.9.9"), out2)
        assertEquals("0.3.2", link(home, "current"), "the rollback itself landed; only the restart is red")
    }

    /** A candidate older than 0.4.0 has no `doctor --json`; its text doctor is not run against the
     *  live install for nothing, and `--to` names the tag with or without its v (review 2026-09-14). */
    @Test
    fun `a pre-0-4-0 candidate skips the JSON doctor preflight, and --to accepts a bare version`(
        @TempDir home: Path,
    ) {
        flatInstall(home)
        reportedVersion = "0.3.9"
        val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--to", "0.3.9", "--now")) }
        assertTrue(ok, out)
        assertTrue(out.contains("predates doctor --json"), out)
        assertTrue(calls.none { it.contains("--json") }, "no doctor --json for a jar that ignores the flag: $calls")
        assertEquals("0.3.9", link(home, "current"))
        val release = UpgradeRelease(JdkUpgradeFetch(), process(), "java")
        assertEquals(release.base("v0.4.0", null), release.base("0.4.0", null), "one tag, two spellings")
        assertTrue(release.base("0.4.0", null).endsWith("/download/v0.4.0"), release.base("0.4.0", null))
    }

    @Test
    fun `rollback with a version is refused instead of silently ignoring it`(@TempDir home: Path) {
        flatInstall(home)
        val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--rollback", "--to", "v1.2.3")) }
        assertFalse(ok)
        assertTrue(out.contains("--rollback takes no --to"), out)
    }

    @Test
    fun `an in-flight count that cannot be read never activates`(@TempDir home: Path) {
        val intact = flatInstall(home)
        inflightAnswers = ArrayDeque(listOf(InflightRead.Count(2)))
        inflightAfter = InflightRead.Unknown("timeout")
        val cmd = command(home, release(home), maxWaitMs = 50)
        val (ok, out) = captured { cmd.upgrade(listOf("--to", "v9.9.9")) }
        assertFalse(ok, out)
        assertTrue(out.contains("in-flight count unknown"), out)
        assertFalse(Files.isSymbolicLink(home.resolve("share/splice.jar")), "nothing was activated")
        assertEquals(0, unitRestarts + verbRestarts)
        assertTrue(Files.isDirectory(home.resolve("share/releases/9.9.9")), "the candidate stays staged")
        assertIntact(intact)
    }

    @Test
    fun `a release whose jar reports another version than --to is refused and leaves no staging`(
        @TempDir home: Path,
    ) {
        flatInstall(home)
        reportedVersion = "9.9.8"
        val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--to", "v9.9.9")) }
        assertFalse(ok, out)
        assertTrue(out.contains("reporting 9.9.8"), out)
        assertFalse(Files.exists(home.resolve("share/releases/9.9.9")))
        assertFalse(Files.exists(home.resolve("share/releases/9.9.8")))
        assertEquals(0, stagingDirs(home))
        assertEquals("old-jar", read(home, "splice.jar"))
    }

    @Test
    fun `a verification failure after the jar was fetched leaves no staging either`(@TempDir home: Path) {
        flatInstall(home)
        val shimOnly = mapOf("splice.jar" to "new-jar".toByteArray(), "splice-launch" to "x".toByteArray())
        val (ok, out) = captured { command(home, release(home, sums = shimOnly)).upgrade(listOf("--to", "v9.9.9")) }
        assertFalse(ok, out)
        assertTrue(out.contains("sha256 verification FAILED for splice-launch"), out)
        assertEquals(0, stagingDirs(home))
        assertEquals("old-jar", read(home, "splice.jar"))
    }

    @Test
    fun `a failed wrapper refresh activates nothing and restores every pointer`(@TempDir home: Path) {
        val intact = flatInstall(home)
        pristine(home)
        val share = home.resolve("share")
        val perms = Files.getPosixFilePermissions(share)
        Files.setPosixFilePermissions(share, PosixFilePermissions.fromString("r-x------"))
        try {
            val (ok, out) = captured { command(home, release(home)).upgrade(listOf("--to", "v9.9.9")) }
            assertFalse(ok, out)
            assertTrue(out.contains("0.3.2 restored") || out.contains("staging failed"), out)
        } finally {
            Files.setPosixFilePermissions(share, perms)
        }
        assertFalse(Files.isSymbolicLink(home.resolve("share/splice.jar")), "the live jar is untouched")
        assertEquals("old-jar", read(home, "splice.jar"))
        assertEquals(STOCK, read(home, "splice-launch"))
        assertEquals(0, unitRestarts + verbRestarts)
        assertIntact(intact)
    }

    private fun stagingDirs(home: Path): Long = Files.list(home.resolve("share/releases")).use { entries ->
        entries.filter { it.fileName.toString().startsWith(".staging") }.count()
    }

    @Test
    fun `a fetch that fails is refused by its class, an absent asset by its absence`(@TempDir home: Path) {
        flatInstall(home)
        val remote = "https://example.invalid/releases/download/v9.9.9"
        val forbidden = UpgradeFetch { throw UpgradeFetchFailed("HTTP 403 (forbidden)") }
        val (ok, out) = captured { command(home, remote, fetch = forbidden).upgrade(emptyList()) }
        assertFalse(ok)
        assertTrue(out.contains("fetching sha256sums.txt from $remote failed: HTTP 403 (forbidden)"), out)
        assertFalse(out.contains("no sha256sums.txt at"), "a refusal is not an absence: $out")
        val empty = Files.createDirectories(home.resolve("empty")).toUri().toString().trimEnd('/')
        val (absent, out2) = captured { command(home, empty).upgrade(emptyList()) }
        assertFalse(absent)
        assertTrue(out2.contains("no sha256sums.txt at $empty"), out2)
        assertEquals("old-jar", read(home, "splice.jar"))
        assertEquals(0, stagingDirs(home))
    }

    @Test
    fun `a second upgrade while one holds the install lock is refused before it fetches anything`(
        @TempDir home: Path,
    ) {
        flatInstall(home)
        val lockFile = Files.createDirectories(home.resolve("share/releases")).resolve(".upgrade.lock")
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val (ok, out) = captured { command(home, release(home)).upgrade(emptyList()) }
                assertFalse(ok)
                assertTrue(out.contains("another splice upgrade is running"), out)
                assertEquals(0, stagingDirs(home), "refused before fetching")
                assertEquals("old-jar", read(home, "splice.jar"))
            }
        }
        assertTrue(command(home, release(home)).upgrade(emptyList()), "the lock is free again")
        assertEquals("9.9.9", link(home, "current"))
    }
}
