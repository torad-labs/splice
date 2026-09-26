// 2026-09-23: a command linked to OUR launch shim that no head claims. install.sh used to print a
// hardcoded "claudeor is stale, rm it" notice for ANY claudeor link to the shim — including one a
// topology still names (`command = "claudeor"`), where the rm deletes a working head. The question
// "does a head claim this name" is the topology's, so doctor asks it, for every name, and install.sh
// reads the answer from the doctor run it already ends with.
package splice.diagnostics.doctor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.EnvReader
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DoctorOrphanWrapperTest {

    @Test
    fun `a command the topology still names is not an orphan - the operator's claudeor`(@TempDir tmp: Path) {
        val bin = install(tmp, "claudeor", "splice")
        assertTrue(Files.isSymbolicLink(bin.resolve("claudeor")), "SETUP: the link must exist")
        assertEquals(emptyList<DoctorCheck>(), orphans(tmp, parsed(command = "claudeor")))
    }

    @Test
    fun `a shim link no head claims warns by name with the rm that clears it`(@TempDir tmp: Path) {
        val bin = install(tmp, "claudeor", "claude-openrouter", "splice")
        val row = orphans(tmp, parsed(command = "claude-openrouter")).single()
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.startsWith("'claudeor' → "), row.detail)
        assertEquals("rm ${bin.resolve("claudeor")}   (or give a head that command again in the topology)", row.fix)
    }

    @Test
    fun `a same-named link to anything but our shim is never named`(@TempDir tmp: Path) {
        val bin = install(tmp, "claude-openrouter", "splice")
        val foreign = Files.writeString(tmp.resolve("their-script"), "#!/bin/sh\n")
        Files.createSymbolicLink(bin.resolve("claudeor"), foreign)
        assertEquals(emptyList<DoctorCheck>(), orphans(tmp, parsed(command = "claude-openrouter")))
    }

    @Test
    fun `with no parsed topology no wrapper is judged an orphan`(@TempDir tmp: Path) {
        install(tmp, "claudeor", "splice")
        assertEquals(emptyList<DoctorCheck>(), orphans(tmp, DoctorTopology.Absent))
    }

    @Test
    fun `splice itself is never an orphan, and the key stands in for an absent command`(@TempDir tmp: Path) {
        install(tmp, "openrouter", "splice")
        assertEquals(emptyList<DoctorCheck>(), orphans(tmp, parsed(command = null)))
    }

    @Test
    fun `a bin dir that cannot be listed says the scan did not run, never none`(@TempDir tmp: Path) {
        val bin = install(tmp, "claudeor", "splice")
        val rows = try {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("--x------"))
            DoctorInstallProbes(DoctorTestPorts.probes(), DoctorTestPorts.noJar)
                .installationChecks(parsed(command = "claude-openrouter"), env(tmp))
        } finally {
            Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"))
        }
        val row = rows.single { it.detail.contains("could not be listed") }
        assertEquals(CheckStatus.WARN, row.status)
        assertTrue(row.detail.contains("were not checked"), row.detail)
    }

    private fun env(tmp: Path) = EnvReader { name ->
        mapOf(
            "SPLICE_SHARE_DIR" to tmp.resolve("share").toString(),
            "SPLICE_BIN_DIR" to tmp.resolve("bin").toString(),
        )[name]
    }

    /** The shim under share/ and each named command linked to it under bin/, as `install --all` leaves them. */
    private fun install(tmp: Path, vararg commands: String): Path {
        val shim = Files.createDirectories(tmp.resolve("share")).resolve("splice-launch")
        Files.writeString(shim, "const SPLICE_SHIM_VERSION = \"shim-test\";\n")
        val bin = Files.createDirectories(tmp.resolve("bin"))
        commands.forEach { Files.createSymbolicLink(bin.resolve(it), shim) }
        return bin
    }

    private fun orphans(tmp: Path, topology: DoctorTopology) =
        DoctorInstallProbes(DoctorTestPorts.probes(), DoctorTestPorts.noJar).installationChecks(topology, env(tmp))
            .filter { it.detail.contains("names no head in the topology") }

    private fun parsed(command: String?): DoctorTopology {
        val claude = if (command == null) "" else "[heads.openrouter.claude]\ncommand = \"$command\"\n"
        val toml = """
            [providers.cloud]
            dialect = "openai-chat"
            base_url = "https://openrouter.ai/api/v1"
            auth = { kind = "api-key", env = "K" }
            [[providers.cloud.models]]
            id = "m1"
            context_window = 8192

            [heads.openrouter]
            provider = "cloud"
            port = 3901
            discovery_prefix = "claude-openrouter--"
            pinned_model = "m1"

        """.trimIndent() + "\n" + claude
        return DoctorTopology.Parsed(TopologyLoader.parse(toml))
    }
}
