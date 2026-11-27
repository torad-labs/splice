package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.app.desktop.setup.DesktopManagedPolicy
import splice.app.e2e.AnthropicFrames
import splice.app.e2e.RecordingAnthropic
import splice.core.client.LAUNCH_OWNER_HEADER
import splice.core.client.SESSION_HEADER
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.config.MgmtKey
import splice.core.config.TurnKey
import splice.core.testing.TestPorts
import splice.core.util.AsyncFileIo
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class ModSessionHandoverTest {
    @TempDir lateinit var root: Path

    @Test
    fun `a launched turn with no installed mods serves without creating a mod session ledger`() = isolated {
        val events = ModEventsTestRig(root)
        SessionDaemon(root, events).use { daemon ->
            daemon.start()
            val owner = daemon.launch(ProcessHandle.current().pid())
            daemon.turn("unobserved", owner)
            assertTrue(AsyncFileIo.drain())
            assertFalse(Files.exists(events.runner.paths.modsDir.resolve("sessions")))
        }
    }

    @Test
    fun `a background child holding stderr cannot keep the daemon from stopping`() = isolated {
        val events = ModEventsTestRig(root)
        SessionDaemon(root, events).use { daemon ->
            daemon.start()
            val child = daemon.installBackgroundChild()
            try {
                assertTrue(child.isAlive)
                withTimeout(6_000) { daemon.stop() }
                events.runner.eventually { true.takeUnless { child.isAlive } }
            } finally {
                if (child.isAlive) child.destroyForcibly()
            }
        }
    }

    @Test
    fun `a successor serves while reaping the old mod and restores its launch without a replay`() = isolated {
        val events = ModEventsTestRig(root)
        val launch = ProcessBuilder("/bin/sleep", "60").start()
        try {
            SessionDaemon(root, events).use { old ->
                old.start()
                old.install()
                val child = old.installBackgroundChild()
                try {
                    assertTrue(child.isAlive)
                    assertHandover(old, events, launch)
                    events.runner.eventually { true.takeUnless { child.isAlive } }
                } finally {
                    if (child.isAlive) child.destroyForcibly()
                }
            }
        } finally {
            launch.destroyForcibly().waitFor()
        }
    }

    private suspend fun assertHandover(old: SessionDaemon, events: ModEventsTestRig, launch: Process) {
        val owner = old.launch(launch.pid())
        old.turn("carried", owner)
        events.through("recorder", "turn.complete", "carried")
        withTimeout(6_000) { old.retire() }
        assertTrue(AsyncFileIo.drain())

        SessionDaemon(root, events).use { next ->
            withTimeout(10_000) {
                next.start()
                next.awaitRecorder()
                next.turn("carried", owner)
            }
            launch.destroyForcibly().waitFor()
            val received = events.through("recorder", "session.end", "carried").filter { it.session == "carried" }
            assertEquals(1, received.count { it.name == "session.start" })
            assertEquals(2, received.count { it.name == "turn.complete" })
            assertEquals(1, received.count { it.name == "session.end" })
        }
    }

    private fun isolated(test: suspend () -> Unit) {
        DesktopManagedPolicy.within(root.resolve("etc/managed-settings.json")) { runBlocking { test() } }
    }
}

private class SessionDaemon(private val home: Path, private val events: ModEventsTestRig) : AutoCloseable {
    private val control = TestPorts.reserve()
    private val head = TestPorts.reserve()
    private val base = "http://127.0.0.1:$control"
    private val client = HttpClient(CIO)
    private val upstream = RecordingAnthropic()
    private val paths = events.runner.paths
    private val key = MgmtKey(paths)
    private val daemon = Daemon(topology = TopologyLoader.parse(topology()), statePaths = paths, log = {})

    suspend fun start() {
        daemon.start()
        awaitListening(control, head)
    }

    suspend fun retire() {
        daemon.retire()
    }

    suspend fun stop() {
        daemon.stop()
    }

    suspend fun install() = events.install(client, "recorder", base = base)

    suspend fun installBackgroundChild(): ProcessHandle {
        val source = events.runner.fixture(
            "background",
            """
            import { existsSync, writeFileSync } from "node:fs";
            export function register(on) {
              on("mod.start", async () => {
                const path = Bun.env.SPLICE_MOD_DATA + "/child";
                if (existsSync(path)) return;
                const child = Bun.spawnSync(["sh", "-c", "sleep 45 >/dev/null & echo $!"], { stderr: "inherit" });
                writeFileSync(path, child.stdout);
              });
            }
            """.trimIndent(),
        )
        events.runner.install(client, source, base = base)
        val pidFile = paths.modsDir.resolve("data/background/child")
        return events.runner.eventually {
            if (Files.exists(pidFile)) {
                Files.readString(pidFile).trim().toLongOrNull()?.let { ProcessHandle.of(it).orElse(null) }
            } else {
                null
            }
        }
    }

    suspend fun awaitRecorder() {
        events.runner.eventually {
            val count = events.runner.log("recorder").lineSequence().count { it.startsWith("env=") }
            true.takeIf { count == 2 }
        }
    }

    suspend fun launch(pid: Long): String {
        val response = client.submitForm(
            "$base/launch/claude",
            Parameters.build {
                append("cwd", home.toString())
                append("launch", pid.toString())
            },
        ) {
            bearerAuth(key.get())
            accept(ContentType.parse("text/x-splice-launch"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.bodyAsText().lineSequence().single { it.startsWith("E SPLICE_FOREGROUND_OWNER=") }
            .substringAfter('=')
    }

    suspend fun turn(session: String, owner: String) {
        upstream.enqueue(AnthropicFrames.toolUse("fixture answer", "mcp__notes__read"))
        val answer = client.post("http://127.0.0.1:$head/v1/messages") {
            bearerAuth("test-provider-key")
            header(LAUNCH_PROOF_HEADER, TurnKey(key).get())
            header(LAUNCH_OWNER_HEADER, owner)
            header(SESSION_HEADER, session)
            header("Content-Type", "application/json")
            setBody("""{"model":"fixture","max_tokens":16,"stream":true,"messages":[{"role":"user","content":"hi"}]}""")
        }
        assertEquals(HttpStatusCode.OK, answer.status)
        assertTrue("message_stop" in answer.bodyAsText())
    }

    override fun close() {
        try {
            runBlocking { daemon.stop() }
        } finally {
            client.close()
            upstream.stop()
        }
    }

    private fun topology(): String = """
        [daemon]
        control_port = $control

        [providers.client]
        dialect = "anthropic-passthrough"
        base_url = "${upstream.baseUrl}"
        auth = { kind = "client" }

        [[providers.client.models]]
        id = "fixture"
        context_window = 200000

        [heads.claude]
        provider = "client"
        port = $head
        pinned_model = "fixture"
    """.trimIndent()
}
