package splice.app.mods

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

                val enable = client.put("/api/mods/watched") {
                    bearerAuth(rig.key.get())
                    setBody("""{"enabled":true}""")
                }
                assertEquals(HttpStatusCode.OK, enable.status)
                val refused = Json.parseToJsonElement(enable.bodyAsText()).jsonObject
                assertEquals("failed", refused.getValue("state").jsonPrimitive.content)
                assertTrue(mod.host.isAlive)
                assertTrue(mod.helper.isAlive)
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

    override fun close() {
        Files.writeString(release, "")
        if (host.isAlive) host.destroyForcibly()
        if (helper.isAlive) helper.destroyForcibly()
    }
}
