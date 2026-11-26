package splice.app.mods

import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.mods.install.ModInstall
import splice.app.mods.run.ModCallRows
import splice.app.mods.run.ModEvent
import splice.app.mods.sessions.ModLaunches
import splice.core.util.LogSink
import splice.launch.LaunchModSettings
import splice.launch.LaunchNotice
import splice.launch.ModsOff
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.upstream.Waiter
import java.nio.file.Files
import java.nio.file.Path

class ModRunnerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `install starts observing and disable delivers stop before the process goes away`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        val source = rig.fixture(
            "recorder",
            """
            export function register(on) {
              on("mod.start", async ($, e) => {
                const child = Bun.spawn(["/bin/sleep", "600"], { stdin: "ignore", stdout: "ignore", stderr: "ignore" });
                $.log("started " + e.mod + " " + child.pid);
              });
              on("mod.stop", async ($, e) => { $.log("stopped " + e.reason); });
            }
            """.trimIndent(),
        )
        rig.runner.start()
        try {
            assertEquals("running", rig.install(client, source).field("state"))
            val row = rig.rows(client).single()
            val handle = ProcessHandle.of(row.field("pid").toLong()).orElseThrow()
            assertTrue(handle.isAlive)
            val started = rig.eventually {
                rig.log("recorder").lineSequence().firstOrNull { it.startsWith("started recorder ") }
            }
            val descendant = ProcessHandle.of(started.substringAfterLast(" ").toLong()).orElseThrow()
            try {
                assertTrue(row.field("notice").contains("recorder"))
                assertEquals("disabled", rig.disable(client, "recorder").field("state"))
                assertFalse(handle.isAlive)
                assertFalse(descendant.isAlive)
                assertTrue(rig.log("recorder").contains("stopped disabled"))
            } finally {
                if (descendant.isAlive) descendant.destroyForcibly()
            }
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `a load failure and a changed installed file are named without taking down a linked mod`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        val copied = rig.fixture("changed", "export function register() {}")
        val broken = rig.fixture("broken", """export function register() { throw Error("broken on purpose"); }""")
        val linked = rig.fixture("linked", "export function register() {}")
        rig.mods.install(ModInstall(copied, false, rig.mods.bun.atInstall()?.toString()))
        Files.writeString(rig.paths.modsDir.resolve("installed/changed/hooks/register.ts"), "// edited")
        rig.runner.start()
        try {
            val failedLoad = rig.install(client, broken)
            assertEquals("failed", failedLoad.field("state"))
            assertTrue(failedLoad.field("why").contains("broken on purpose"))
            assertFalse(failedLoad.containsKey("pid"))
            val linkedRow = rig.install(client, linked, linked = true)
            assertEquals("running", linkedRow.field("state"))
            assertEquals("false", linkedRow.field("hashChecked"))
            val failedHash = rig.rows(client).single { it.field("name") == "changed" }
            assertEquals("failed", failedHash.field("state"))
            assertTrue(failedHash.field("why").contains("hooks/register.ts (changed)"))
            assertTrue(failedHash.field("why").contains("splice mod enable changed --accept"))
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `without Bun the catalog tells the person what to set`() = testApplication {
        val rig = RunnerTestRig(root, emptyMap())
        application { routing { rig.mount.register(this) } }
        rig.runner.start()
        try {
            val row = rig.install(client, rig.fixture("needsbun", "export function register() {}"))
            assertEquals("failed", row.field("state"))
            assertTrue(row.field("why").contains("Bun was not found"))
            assertTrue(row.field("why").contains("SPLICE_BUN"))
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `the fourth crash stops retries and reports why`() = testApplication {
        val rig = RunnerTestRig(root, retryWait = Waiter {})
        application { routing { rig.mount.register(this) } }
        val source = rig.fixture(
            "crashes",
            """
            export function register(on) {
              on("mod.start", async () => { console.error("fixture crash"); process.exit(7); });
            }
            """.trimIndent(),
        )
        rig.runner.start()
        try {
            rig.install(client, source)
            val row = rig.eventually { rig.rows(client).single().takeIf { it.field("state") == "failed" } }
            assertTrue(row.field("why").contains("exited 4 times in 10 minutes"))
            assertTrue(row.field("why").contains("exit code 7"))
            assertTrue(rig.log("crashes").contains("Events sent to it may be lost"))
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `stream overflow cannot take the slots for turn edges`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        val source = rig.fixture(
            "busy",
            """
            export function register(on) {
              on("turn.start", async ($) => { $.log("holding"); await new Promise(() => {}); });
              on("stream.event", async () => {});
              on("turn.complete", async ($, e) => { if (e.turn === "first") $.log("first edge arrived"); });
            }
            """.trimIndent(),
        )
        rig.runner.start()
        try {
            rig.install(client, source)
            val hub = rig.runner.events
            val quiet = JsonObject(emptyMap())
            hub.offer(ModEvent("turn.start", "s", quiet), ModsOff(all = true))
            hub.offer(ModEvent("turn.start", "s", quiet))
            rig.eventually { rig.log("busy").takeIf { "holding" in it } }
            repeat(1537) { hub.offer(ModEvent("stream.event", "s", quiet)) }
            repeat(512) { index ->
                val payload = buildJsonObject { put("turn", if (index == 0) "first" else "later") }
                hub.offer(ModEvent("turn.complete", "s", payload))
            }
            hub.offer(ModEvent("turn.complete", "s", quiet))
            val row = rig.rows(client).single()
            assertEquals("2", row.field("dropped"))
            assertEquals("1", row.getValue("droppedByEvent").jsonObject.field("stream.event"))
            assertEquals("1", row.getValue("droppedByEvent").jsonObject.field("turn.complete"))
            rig.eventually { rig.log("busy").takeIf { "first edge arrived" in it } }
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `a mod can list registry sessions but does not see the ones that turned it off`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        val registry = Files.createDirectory(root.resolve("registrations"))
        val pid = ProcessHandle.current().pid()
        Files.writeString(registry.resolve("1.json"), """{"pid":$pid,"sessionId":"quiet","name":"Opted out"}""")
        Files.writeString(registry.resolve("2.json"), """{"pid":$pid,"sessionId":"shown","name":"Shown session"}""")
        val launches = ModLaunches(rig.paths.modsDir, LogSink {}, rig.mods)
        val settings = LaunchModSettings(modsOff = ModsOff(names = setOf("lists")))
        launches.launched(LaunchNotice("owner", null, null, settings))
        launches.forTurn("quiet", "owner")
        val sessions = SessionRegistry(registry, RouteOfPid { SessionRoute.Unknown })
        val source = rig.fixture(
            "lists",
            """
            export function register(on) {
              on("mod.start", async ($) => { $.log("Rows: " + JSON.stringify(await $.sessions.list())); });
            }
            """.trimIndent(),
        )
        rig.runner.start(ModCallRows(sessions, launches))
        try {
            val row = rig.install(client, source)
            val log = rig.eventually { rig.log("lists").takeIf { "Rows: " in it } }
            assertTrue(log.contains("Shown session"), log)
            assertFalse(log.contains("Opted out"), log)
            assertFalse(log.contains("messagingSocketPath"), log)
            val handle = ProcessHandle.of(row.field("pid").toLong()).orElseThrow()
            rig.runner.stop()
            assertFalse(handle.isAlive)
        } finally {
            rig.runner.stop()
        }
    }

    @Test
    fun `the next daemon reaps a process whose birth matches the old run record`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        val previous = ProcessBuilder("sh", "-c", "exec sleep 600").start()
        try {
            val birth = previous.info().startInstant().orElseThrow().toString()
            val run = Files.createDirectories(rig.paths.modsDir.resolve("run"))
            Files.writeString(run.resolve("old.json"), """{"pid":${previous.pid()},"birth":"$birth"}""")
            rig.runner.start()
            assertFalse(previous.isAlive)
            assertTrue(rig.rows(client).isEmpty())
        } finally {
            previous.destroyForcibly()
            rig.runner.stop()
        }
    }

    @Test
    fun `handler errors and log overflow do not take down the mod`() = testApplication {
        val rig = RunnerTestRig(root)
        application { routing { rig.mount.register(this) } }
        val source = rig.fixture(
            "loud",
            """
            export function register(on) {
              on("mod.start", async ($) => {
                for (let i = 0; i < 1000; i++) $.log("line " + i);
                throw Error("a handler failed");
              });
            }
            """.trimIndent(),
        )
        rig.runner.start()
        try {
            rig.install(client, source)
            val row = rig.eventually {
                rig.rows(client).single().takeIf { it.field("droppedLogLines").toLong() > 0 && "lastError" in it }
            }
            assertEquals("running", row.field("state"))
            assertTrue(row.field("lastError").contains("a handler failed"))
        } finally {
            rig.runner.stop()
        }
    }

    private fun JsonObject.field(key: String): String =
        getValue(key).jsonPrimitive.content
}
