// `splice upgrade` (v0.4.0, FEATURES.md §5) against a fake share dir and a file:// release: a
// verified release lands in its own directory and the live jar repoints to it; a failed
// verification activates nothing; an edited wrapper is kept with its diff printed; rollback
// repoints at the previous release; config and credentials are never touched.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.JdkUpgradeFetch
import splice.app.cli.UpgradeCommand
import splice.app.cli.UpgradeDaemon
import splice.app.cli.UpgradeExit
import splice.app.cli.UpgradeFetch
import splice.app.cli.UpgradeLayout
import splice.app.cli.UpgradeProcess
import splice.app.cli.UpgradeRelease
import splice.app.cli.UpgradeWrapper
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val STOCK = "#!/bin/sh\necho stock\n"
private const val PATCHED = "#!/bin/sh\necho patched\n"
private const val NEWER = "#!/bin/sh\necho newer\n"

class UpgradeCommandTest {

    private val calls = mutableListOf<List<String>>()
    private var unitRestarts = 0
    private var verbRestarts = 0
    private var inflightAnswers = ArrayDeque<Int>()

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
        else -> UpgradeExit(0, "").also { unitRestarts++ }
    }

    private fun java(cmd: List<String>) = when {
        cmd.last() == "version" -> UpgradeExit(0, "splice 9.9.9\n")
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
    ): UpgradeCommand {
        val env = env(home, base)
        val unitJar = home.resolve("share/splice.jar").takeIf { supervised }
        val daemon = UpgradeDaemon(
            process(unitJar = unitJar),
            { inflightAnswers.removeFirstOrNull() },
            restartVerb = {
                verbRestarts++
                true
            },
            pollMs = 1,
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
        assertEquals(1 to 0, unitRestarts to verbRestarts, "the unit that names this jar was restarted")
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

    @Test
    fun `an edited wrapper is kept and its diff printed, and rollback repoints at the previous release`(
        @TempDir home: Path,
    ) {
        val intact = flatInstall(home, shim = PATCHED)
        pristine(home)
        val base = release(home)
        inflightAnswers = ArrayDeque(listOf(2, 1, 0))
        val (ok, out) = captured { command(home, base).upgrade(listOf("--to", "v9.9.9")) }
        assertTrue(ok, out)
        assertTrue(out.contains("kept") && out.contains("+echo patched"), out)
        assertEquals(PATCHED, read(home, "splice-launch"))
        assertEquals(2, out.split("in flight").size - 1, "waited two polls for the turns in flight")
        val (back, out2) = captured { command(home, base).upgrade(listOf("--rollback", "--now")) }
        assertTrue(back, out2)
        assertEquals("old-jar", read(home, "splice.jar"))
        assertEquals("0.3.2", link(home, "current"))
        assertEquals("9.9.9", link(home, "previous"))
        assertEquals(PATCHED, read(home, "splice-launch"), "still kept")
        assertEquals(0 to 2, unitRestarts to verbRestarts, "no unit names this install's jar: the verb restarts")
        assertIntact(intact)
    }
}
