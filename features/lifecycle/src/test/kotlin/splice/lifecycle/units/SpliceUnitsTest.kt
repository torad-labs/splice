// Goal (Oct 25, 2026, Host Shield's handover): splice's own install lays the systemd user units the daemon and its MCP
// front run under, in the folder systemd keeps for a user's installed software ($XDG_DATA_HOME/systemd/user), so the
// daemon keeps a unit to come back under after a restart or a reboot when Host Shield, or anyone, stops laying one. A
// unit file someone placed in ~/.config/systemd/user overrides splice's and is never touched; a copy splice's own
// earlier installs wrote there goes, so splice's laid unit takes effect. Driven through SpliceUnits against a stand-in
// user manager that searches the two folders in systemd's order, config before data; where systemd-analyze is
// installed, systemd itself reads every unit splice lays, by name, from splice's folder.
package splice.lifecycle.units

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.lifecycle.hub.HubUnits
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val MARKER = "# splice-owned unit"
private const val JDK = "/opt/jdks/jdk-21.0.11"
private const val LINK = "/home/someone/.local/bin/splice"
private const val SOCKET = "splice-mcp-front.socket"
private const val FRONT = "splice-mcp-front.service"
private const val SLICE = "app-mcp.slice"
private const val SAMPLES = 300
private const val SYSTEMD_SAMPLES = 6
private const val SEED = 20261025L
private const val SAFE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ._-+@,~=éüßж"
private const val UNCARRIED = "\"'\\$`%:\n\t\u0001\u007f"

class SpliceUnitsTest {
    @TempDir
    lateinit var root: Path

    private val lines = CopyOnWriteArrayList<String>()
    private val out = TerminalOutput { lines += it }
    private val manager by lazy { UserManagerStandIn(root) }

    private fun said(): String = lines.joinToString("\n")

    private fun units(): SpliceUnits = SpliceUnits(manager.data, manager.config, manager.record, out, manager.command)

    private fun daemonUnit(jdk: String = JDK, link: String = LINK): ByteArray =
        requireNotNull(DaemonUnit(Path.of(jdk), Path.of(link)).bytes()) { "a plain JDK and link path render a unit" }

    private fun hubTemplates(): Map<String, ByteArray> = HubUnits(units(), out).templates()

    private fun laid(name: String): String = Files.readString(manager.data.resolve(name))

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** What systemd-analyze says about [names] when it loads them by name from a user search path whose folders are
     *  the stand-in's config and data folders; null where systemd-analyze is not installed. */
    private fun systemdSays(names: Collection<String>): List<String>? {
        val home = Files.createDirectories(root.resolve("home"))
        val front = Files.createDirectories(home.resolve(".local/share/splice")).resolve("splice-mcp-front")
        Files.writeString(front, "#!/bin/sh\n")
        Files.setPosixFilePermissions(front, PosixFilePermissions.fromString("rwx------"))
        val run = Files.createDirectories(root.resolve("run"))
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"))
        val builder = ProcessBuilder(listOf("systemd-analyze", "--user", "verify") + names).redirectErrorStream(true)
        builder.environment().putAll(
            mapOf(
                "HOME" to home.toString(),
                "XDG_CONFIG_HOME" to root.resolve("config").toString(),
                "XDG_DATA_HOME" to root.resolve("data").toString(),
                "XDG_RUNTIME_DIR" to run.toString(),
            ),
        )
        val process = runCatching { builder.start() }.getOrNull() ?: return null
        val answer = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(30, TimeUnit.SECONDS)
        return answer.lines().filter { line -> names.any { it in line } }
    }

    @Test
    fun `install lays the daemon's unit where systemd looks for a user's installed units, marked as splice's`() {
        units().lay(mapOf(DAEMON_UNIT_NAME to daemonUnit()))

        assertTrue(Files.isRegularFile(manager.data.resolve(DAEMON_UNIT_NAME)), "no splice.service laid\n${said()}")
        assertTrue(laid(DAEMON_UNIT_NAME).startsWith(MARKER), "the unit does not say it is splice's")
        assertFalse(Files.exists(manager.config.resolve(DAEMON_UNIT_NAME)), "splice wrote into the overrides folder")
    }

    @Test
    fun `the daemon's unit runs the splice link's daemon on the install's JDK and keeps the daemon's contract`() {
        units().lay(mapOf(DAEMON_UNIT_NAME to daemonUnit()))
        val unit = laid(DAEMON_UNIT_NAME).lines()

        assertTrue("Environment=\"JAVA_HOME=$JDK\"" in unit, "the JDK the install ran on is not the daemon's")
        val start = unit.single { it.startsWith("ExecStart=") }
        assertTrue("exec \"$LINK\" daemon" in start, "the unit does not run the splice link's daemon: $start")
        assertTrue("PATH=\"\$\$JAVA_HOME/bin:\$\$PATH\"" in start, "the JDK is not first on the daemon's PATH: $start")
        val contract = listOf(
            "StartLimitIntervalSec=0", "Restart=always", "RestartSec=2", "RestartSteps=8", "RestartMaxDelaySec=300",
            "KillMode=mixed", "TimeoutStopSec=75", "SuccessExitStatus=143", "OOMPolicy=continue", "MemoryLow=1G",
            "ManagedOOMPreference=omit", "WantedBy=default.target",
        )
        val gone = contract.filterNot { it in unit }
        assertEquals(emptyList<String>(), gone, "directives the daemon runs under are gone")
    }

    @Test
    fun `the MCP front's units are laid beside the daemon's, each marked as splice's`() {
        val hub = hubTemplates()
        assertEquals(setOf(SLICE, SOCKET, FRONT), hub.keys, "the hub's three units")

        units().lay(hub + (DAEMON_UNIT_NAME to daemonUnit()))

        for (name in hub.keys + DAEMON_UNIT_NAME) {
            assertTrue(laid(name).startsWith(MARKER), "$name is not laid as splice's\n${said()}")
        }
    }

    @Test
    fun `systemd reads every unit splice lays, by name, from splice's folder`() {
        units().lay(hubTemplates() + (DAEMON_UNIT_NAME to daemonUnit()))

        val complaints = systemdSays(listOf(DAEMON_UNIT_NAME, SOCKET, FRONT, SLICE))
        assumeTrue(complaints != null, "systemd-analyze is not installed here")
        assertEquals(emptyList<String>(), complaints, "systemd could not read splice's units as laid")
    }

    @Test
    fun `a unit file someone placed in the overrides folder is left as it is, and install names it`() {
        val theirs = "[Service]\nExecStart=/usr/bin/their-splice\n".toByteArray()
        Files.createDirectories(manager.config)
        Files.write(manager.config.resolve(DAEMON_UNIT_NAME), theirs)

        units().lay(mapOf(DAEMON_UNIT_NAME to daemonUnit()))

        assertArrayEquals(theirs, Files.readAllBytes(manager.config.resolve(DAEMON_UNIT_NAME)), "their unit changed")
        assertTrue(laid(DAEMON_UNIT_NAME).startsWith(MARKER), "splice's own unit was not laid beneath theirs")
        assertTrue(
            said().lines().any { manager.config.resolve(DAEMON_UNIT_NAME).toString() in it && "overrides" in it },
            "install did not say which unit systemd runs:\n${said()}",
        )
    }

    @Test
    fun `a hub unit splice's earlier install wrote into the overrides folder goes, so the laid one takes effect`() {
        val old = "[Socket]\nListenStream=127.0.0.1:3095\n".toByteArray()
        Files.createDirectories(manager.config)
        Files.write(manager.config.resolve(SOCKET), old)
        Files.createDirectories(manager.record.parent)
        Files.writeString(manager.record, "${sha256(old)}  $SOCKET\n")

        units().lay(hubTemplates())

        assertFalse(Files.exists(manager.config.resolve(SOCKET)), "splice's old copy still overrides its laid unit")
        assertTrue(laid(SOCKET).startsWith(MARKER), "the socket was not laid in splice's folder")
        val left = if (Files.exists(manager.record)) Files.readString(manager.record) else ""
        assertFalse(SOCKET in left, "the record still lists a copy that is gone: $left")
    }

    @Test
    fun `a unit splice once wrote into the overrides folder but someone changed since is kept`() {
        val written = "[Socket]\nListenStream=127.0.0.1:3095\n".toByteArray()
        val changed = "[Socket]\nListenStream=127.0.0.1:4095\n".toByteArray()
        Files.createDirectories(manager.config)
        Files.write(manager.config.resolve(SOCKET), changed)
        Files.createDirectories(manager.record.parent)
        Files.writeString(manager.record, "${sha256(written)}  $SOCKET\n")

        units().lay(hubTemplates())

        assertArrayEquals(changed, Files.readAllBytes(manager.config.resolve(SOCKET)), "someone's change was removed")
    }

    @Test
    fun `splice's own unit is replaced when splice's bytes for it change, as on an upgrade or a new JDK`() {
        units().lay(mapOf(DAEMON_UNIT_NAME to daemonUnit(jdk = "/opt/jdks/jdk-21.0.10")))
        units().lay(mapOf(DAEMON_UNIT_NAME to daemonUnit(jdk = "/opt/jdks/jdk-21.0.12")))

        val unit = laid(DAEMON_UNIT_NAME).lines()
        assertTrue("Environment=\"JAVA_HOME=/opt/jdks/jdk-21.0.12\"" in unit, "the newer unit did not replace the old")
        assertFalse(unit.any { "jdk-21.0.10" in it }, "the old unit's JDK is still there")
    }

    @Test
    fun `the user manager reloads after splice lays a unit, so systemctl sees it at once`() {
        units().lay(mapOf(DAEMON_UNIT_NAME to daemonUnit()))

        assertTrue("daemon-reload" in manager.heard(), "the user manager was not told: ${manager.heard()}")
    }

    @Test
    fun `laying never starts, stops, restarts or enables a unit`() {
        Files.createDirectories(manager.config)
        Files.writeString(manager.config.resolve(DAEMON_UNIT_NAME), "[Service]\nExecStart=/usr/bin/their-splice\n")

        units().lay(hubTemplates() + (DAEMON_UNIT_NAME to daemonUnit()))

        assertEquals(listOf("daemon-reload"), manager.heard().distinct(), "laying acted on a unit")
    }

    @Test
    fun `any JDK and link path made of characters a unit carries as written renders a unit holding them verbatim`() {
        val random = Random(SEED)
        repeat(SAMPLES) {
            val jdk = path(random)
            val link = path(random)
            val unit = DaemonUnit(Path.of(jdk), Path.of(link))
            val bytes = unit.bytes()
            assertNotNull(bytes, "no unit for $jdk and $link: ${unit.refusal()}")
            val text = bytes!!.decodeToString().lines()
            assertTrue("Environment=\"JAVA_HOME=$jdk\"" in text, "the JDK was not carried verbatim: $jdk")
            assertTrue(text.any { it.startsWith("ExecStart=") && "exec \"$link\" daemon" in it }, "link: $link")
            assertNull(unit.refusal(), "a carriable pair was refused: $jdk, $link")
        }
    }

    @Test
    fun `any JDK or link path holding a character a unit cannot carry as written lays no unit, and says why`() {
        val random = Random(SEED + 1)
        repeat(SAMPLES) {
            val clean = path(random)
            val at = 1 + random.nextInt(clean.length - 1)
            val bad = clean.substring(0, at) + UNCARRIED[random.nextInt(UNCARRIED.length)] + clean.substring(at)
            val (jdk, link) = if (random.nextBoolean()) bad to LINK else JDK to bad
            val unit = DaemonUnit(Path.of(jdk), Path.of(link))
            assertNull(unit.bytes(), "a unit was rendered for ${bad.toList()}")
            assertTrue(unit.refusal()?.contains(DAEMON_UNIT_NAME) == true, "no reason given for ${bad.toList()}")
        }
        assertNull(DaemonUnit(Path.of("jdks/21"), Path.of(LINK)).bytes(), "a relative JDK path rendered a unit")
    }

    @Test
    fun `systemd reads the daemon's unit for generated JDK and link paths, spaces and accents included`() {
        val random = Random(SEED + 2)
        assumeTrue(systemdSays(emptyList()) != null, "systemd-analyze is not installed here")
        repeat(SYSTEMD_SAMPLES) {
            units().lay(mapOf(DAEMON_UNIT_NAME to daemonUnit(path(random), path(random))))
            assertEquals(emptyList<String>(), systemdSays(listOf(DAEMON_UNIT_NAME)), laid(DAEMON_UNIT_NAME))
        }
    }

    /** An absolute path of one to four segments drawn from the characters a unit carries as written. */
    private fun path(random: Random): String =
        (1..1 + random.nextInt(4)).joinToString("/", prefix = "/") {
            (1..1 + random.nextInt(12)).map { SAFE[random.nextInt(SAFE.length)] }.joinToString("")
        }
}
