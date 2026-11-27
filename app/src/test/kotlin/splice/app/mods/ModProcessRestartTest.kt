package splice.app.mods

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.MgmtKey
import java.nio.file.Path

class ModProcessRestartTest {
    @TempDir lateinit var root: Path

    @Test
    fun `enabling while the old process is stopping reaches running with a new pid`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        val source = rig.fixture(
            "restartable",
            """
            export function register(on) {
              on("mod.stop", async ($) => {
                $.log("old process is stopping");
                await Bun.sleep(1200);
              });
            }
            """.trimIndent(),
        )
        rig.runner.start()
        try {
            val old = rig.install(client, source).getValue("pid").jsonPrimitive.long
            coroutineScope {
                val disabling = async { rig.disable(client, "restartable") }
                rig.eventually { rig.log("restartable").takeIf { "old process is stopping" in it } }
                val enabled = client.put("/api/mods/restartable") {
                    bearerAuth(MgmtKey(rig.paths).get())
                    setBody("""{"enabled":true}""")
                }
                assertEquals(HttpStatusCode.OK, enabled.status)
                val row = Json.parseToJsonElement(enabled.bodyAsText()).jsonObject
                assertEquals("running", row.getValue("state").jsonPrimitive.content)
                assertNotEquals(old, row.getValue("pid").jsonPrimitive.long)
                assertFalse(ProcessHandle.of(old).map(ProcessHandle::isAlive).orElse(false))
                disabling.await()
            }
        } finally {
            rig.runner.stop()
        }
    }
}
