// The fixture `splice upgrade`'s tests share: a fake share dir, a file:// release, and the processes the upgrade
// shells out to (gh, systemctl, java), so each test file states only what it proves.
package splice.lifecycle.upgrade

import org.junit.jupiter.api.Assertions.assertTrue
import splice.core.GATEWAY_VERSION
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal const val STOCK = "#!/bin/sh\necho stock\n"
internal const val PATCHED = "#!/bin/sh\necho patched\n"
internal const val NEWER = "#!/bin/sh\necho newer\n"

// A flat install has no `current` pointer, so upgrade records it under the running binary's version
// (UpgradeLayout.installedVersion). Spelled as a literal, this file went red at the 0.4.0 bump.
internal const val INSTALLED = GATEWAY_VERSION

internal abstract class UpgradeRig {

    protected val calls = mutableListOf<List<String>>()
    protected var unitRestarts = 0
    protected var verbRestarts = 0

    // Each line through println, so `captured` reads the verb's output off System.out as before.
    protected val out = TerminalOutput(::println)
    protected var inflightAnswers = ArrayDeque<InflightRead>()
    protected var inflightAfter: InflightRead = InflightRead.NoDaemon
    protected var reportedVersion = "9.9.9"
    protected var unitActive = true

    /** The exit of the doctor the upgrade runs AFTER activation (the preflight one carries --json). */
    protected var postUpgradeDoctor = 0

    /** How long the daemon wait may spend on an in-flight count before it gives up. */
    protected var maxWaitMs = 60_000L

    /** What /health reports after a restart: the release `current` points at, unless a test pins it. */
    protected var servingVersion: ((Path) -> String?)? = null

    /** The `gh` CLI as the upgrade sees it: [auth] is `gh auth status`'s exit (127 = gh not installed),
     *  [verify] `gh attestation verify`'s. */
    internal class FakeGh(private val auth: Int = 0, private val verify: Int = 0) {
        fun exit(cmd: List<String>) = UpgradeExit(if (cmd[1] == "auth") auth else verify, "")
    }

    /** [unitJar] is what the fake user unit's ExecStart names; null = no unit supervises this install. */
    protected fun process(gh: FakeGh = FakeGh(), unitJar: Path? = null) = UpgradeProcess { cmd, _ ->
        calls += cmd
        when (cmd[0]) {
            "gh" -> gh.exit(cmd)
            "diff" -> UpgradeExit(1, "--- release\n+++ live\n-echo stock\n+echo patched\n")
            "systemctl" -> systemctl(cmd, unitJar)
            else -> java(cmd)
        }
    }

    protected fun systemctl(cmd: List<String>, unitJar: Path?): UpgradeExit = when (cmd[2]) {
        "show" -> UpgradeExit(0, unitJar?.let { "java -jar $it daemon" } ?: "")
        "is-active" -> UpgradeExit(0, if (unitJar != null && unitActive) "active\n" else "inactive\n")
        else -> UpgradeExit(0, "").also { unitRestarts++ }
    }

    /** What the candidate's `check-config` answers: 0 for a splice.toml it boots on, 3 with the findings it refuses. */
    protected var configCheck = UpgradeExit(0, "")

    protected fun java(cmd: List<String>) = when {
        cmd.last() == "check-config" -> configCheck
        cmd.last() == "version" -> UpgradeExit(0, "splice $reportedVersion\n")
        cmd.contains("doctor") -> UpgradeExit(if ("--json" in cmd) 0 else postUpgradeDoctor, "{}")
        else -> UpgradeExit(0, "")
    }

    protected fun env(home: Path, base: String) = EnvReader { name ->
        when (name) {
            "SPLICE_SHARE_DIR" -> home.resolve("share").toString()
            "SPLICE_BIN_DIR" -> home.resolve("bin").toString()
            "SPLICE_RELEASE_BASE_URL" -> base
            "HOME" -> home.toString()
            else -> null
        }
    }

    protected fun command(
        home: Path,
        base: String,
        fetch: UpgradeFetch = JdkUpgradeFetch(),
        gh: FakeGh = FakeGh(),
        supervised: Boolean = false,
    ): UpgradeCommand {
        val env = env(home, base)
        val unitJar = home.resolve("share/splice.jar").takeIf { supervised }
        val daemon = UpgradeDaemon(
            out,
            process(unitJar = unitJar),
            { inflightAnswers.removeFirstOrNull() ?: inflightAfter },
            restartVerb = {
                verbRestarts++
                true
            },
            healthVersion = { servingVersion?.invoke(home) ?: link(home, "current") },
            pacing = UpgradePacing(pollMs = 1, maxWaitMs = maxWaitMs, confirmPollMs = 1),
            userUnit = "splice.service",
        )
        return UpgradeCommand(
            output = out,
            env = env,
            java = "java",
            release = UpgradeRelease(out, fetch, process(gh), "java"),
            wrapper = UpgradeWrapper(out, process()),
            daemon = daemon,
            layout = UpgradeLayout(env),
        )
    }

    protected fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A flat install as install.sh wrote it before 0.4.0, plus config and a credential elsewhere. */
    protected fun flatInstall(home: Path, shim: String = STOCK): Map<Path, ByteArray> {
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
    protected fun pristine(home: Path, shim: String = STOCK) {
        val dir = Files.createDirectories(home.resolve("share/releases/$INSTALLED"))
        Files.writeString(dir.resolve("splice-launch"), shim)
    }

    /** A release directory served over file://; [sums] overrides the published checksums. */
    protected fun release(home: Path, shim: String = NEWER, sums: Map<String, ByteArray>? = null): String {
        val dir = Files.createDirectories(home.resolve("release"))
        val assets = mapOf("splice.jar" to "new-jar".toByteArray(), "splice-launch" to shim.toByteArray())
        assets.forEach { (name, bytes) -> Files.write(dir.resolve(name), bytes) }
        val lines = (sums ?: assets).entries.joinToString("") { (name, bytes) -> "${sha(bytes)}  $name\n" }
        Files.writeString(dir.resolve("sha256sums.txt"), lines)
        return dir.toUri().toString().trimEnd('/')
    }

    protected fun captured(block: () -> Boolean): Pair<Boolean, String> {
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

    protected fun assertIntact(files: Map<Path, ByteArray>) =
        files.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(Files.readAllBytes(path)), "$path changed") }

    protected fun releaseDirs(home: Path): Set<String> = Files.list(home.resolve("share/releases")).use { entries ->
        entries.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }
            .map { it.fileName.toString() }
            .toList()
            .toSet()
    }

    protected fun read(home: Path, name: String): String = Files.readString(home.resolve("share").resolve(name))

    protected fun link(home: Path, name: String): String =
        Files.readSymbolicLink(home.resolve("share/releases").resolve(name)).toString()

    protected fun stagingDirs(home: Path): Long = Files.list(home.resolve("share/releases")).use { entries ->
        entries.filter { it.fileName.toString().startsWith(".staging") }.count()
    }
}
