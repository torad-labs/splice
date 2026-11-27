package splice.app.mods

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ModGuardianLossTest {
    @TempDir lateinit var root: Path

    @Test
    fun `an idle host stops its helpers when its guardian is killed but the daemon lives`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        rig.runner.start()
        try {
            val row = rig.install(client, fixture(rig, held = false))
            processes(rig, row.getValue("pid").jsonPrimitive.long).use { mod ->
                mod.killGuardian()
                rig.eventually { true.takeUnless { mod.host.isAlive || mod.helper.isAlive } }
                assertFalse(mod.host.isAlive)
                assertFalse(mod.helper.isAlive)
            }
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `a guardian killed above a blocked host names the group and enable does not signal it`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        rig.runner.start()
        try {
            val row = rig.install(client, fixture(rig, held = true))
            processes(rig, row.getValue("pid").jsonPrimitive.long).use { mod ->
                mod.killGuardian()
                val failed = rig.eventually {
                    rig.rows(client).single().takeIf { it["state"]?.jsonPrimitive?.content == "failed" }
                }
                val why = failed.getValue("why").jsonPrimitive.content
                assertTrue(why.contains("guardian was killed"), why)
                assertTrue(why.contains("group ${mod.host.pid()} may still be running"), why)

                val refused = rig.enable(client, "watched")
                assertEquals("failed", refused.getValue("state").jsonPrimitive.content)
                assertTrue(mod.host.isAlive)
                assertTrue(mod.helper.isAlive)

                mod.releaseHost()
                rig.eventually { true.takeUnless { mod.host.isAlive || mod.helper.isAlive } }
                val restarted = rig.enable(client, "watched")
                assertEquals("running", restarted.getValue("state").jsonPrimitive.content)
                assertNotEquals(mod.host.pid(), restarted.getValue("pid").jsonPrimitive.long)
            }
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `a guardian that does not exit after EOF is reported without signalling it`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        rig.runner.start()
        try {
            val row = rig.install(client, fixture(rig, held = true))
            processes(rig, row.getValue("pid").jsonPrimitive.long).use { mod ->
                mod.pauseGuardian()
                try {
                    val failure = rig.disable(client, "watched")
                    val why = failure.getValue("why").jsonPrimitive.content
                    assertEquals("failed", failure.getValue("state").jsonPrimitive.content)
                    assertTrue(why.contains("did not exit after stdin closed"), why)
                    assertTrue(why.contains("group ${mod.host.pid()} may still be running"), why)
                    assertTrue(mod.host.isAlive)
                } finally {
                    mod.resumeGuardian()
                }
                rig.eventually { true.takeUnless { mod.host.isAlive || mod.helper.isAlive } }
            }
        } finally {
            rig.runner.stop()
        }
    }

    private fun fixture(rig: RunnerTestRig, held: Boolean): Path = rig.fixture(
        "watched",
        """
        import { existsSync, writeFileSync } from "node:fs";
        export function register(on) {
          on("mod.start", async () => {
            const data = Bun.env.SPLICE_MOD_DATA;
            const child = Bun.spawn(["/bin/sleep", "60"], { stdin: "ignore", stdout: "ignore", stderr: "ignore" });
            writeFileSync(data + "/helper", String(child.pid));
            while ($held && !existsSync(data + "/release")) Bun.sleepSync(20);
          });
        }
        """.trimIndent(),
    )

    private suspend fun processes(rig: RunnerTestRig, pid: Long): ModGuardianFixture {
        val data = rig.paths.modsDir.resolve("data/watched")
        val helper = rig.eventually {
            val marker = data.resolve("helper")
            if (Files.exists(marker)) Files.readString(marker).toLongOrNull() else null
        }
        return ModGuardianFixture(pid, helper, data.resolve("release"))
    }
}

private class ModGuardianFixture(pid: Long, helperPid: Long, private val release: Path) : AutoCloseable {
    val host: ProcessHandle = ProcessHandle.of(pid).orElseThrow()
    val helper: ProcessHandle = ProcessHandle.of(helperPid).orElseThrow()
    private val guardian = host.parent().orElseThrow()

    fun killGuardian() {
        assertTrue(guardian.destroyForcibly())
    }

    fun pauseGuardian() = signal("STOP")

    fun resumeGuardian() = signal("CONT")

    private fun signal(signal: String) {
        assertEquals(0, ProcessBuilder("/bin/kill", "-$signal", guardian.pid().toString()).start().waitFor())
    }

    fun releaseHost() {
        Files.writeString(release, "")
    }

    override fun close() {
        releaseHost()
        if (host.isAlive) host.destroyForcibly()
        if (helper.isAlive) helper.destroyForcibly()
    }
}
