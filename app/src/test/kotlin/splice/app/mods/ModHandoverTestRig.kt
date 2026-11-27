package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import splice.app.control.ControlAuth
import splice.app.control.ControlServer
import splice.app.control.controlServerFor
import splice.app.mods.handover.ModHandoverStarts
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.testing.TestPorts
import splice.core.util.AsyncFileIo
import splice.core.util.LogSink
import splice.launch.LaunchModSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val RECORDER = "recorder"

internal class ModHandoverTestRig(private val root: Path) : AutoCloseable {
    private val relayWaiting = CompletableDeferred<Unit>()
    private val old = ModEventsTestRig(
        root,
        daemonLog = LogSink {
            if ("waiting for the successor to accept the handover" in it) relayWaiting.complete(Unit)
        },
    )
    private val next by lazy { ModEventsTestRig(root) }
    val key = MgmtKey(old.runner.paths, LogSink {})
    private val client = HttpClient(CIO)
    private val oldControl = control(old, 0)
    private val confirmation = ModHandoverStarts(true)
    private val boot = CompletableDeferred<Unit>()
    private var nextControl: ControlServer? = null
    val modsUrl get() = "${url(checkNotNull(nextControl).listeningPort)}/api/mods"
    val handoverUrl get() = "$modsUrl/handover"

    private var oldPid = 0L

    suspend fun installRecorder(slowStop: Boolean = false, startRunner: Boolean = true, linked: Boolean = false) {
        if (startRunner) old.runner.runner.start()
        oldControl.start()
        val source = old.runner.fixture(
            RECORDER,
            """
            import { existsSync, readFileSync, writeFileSync } from "node:fs";
            export function register(on) {
              on("mod.start", async ($) => {
                const path = Bun.env.SPLICE_MOD_DATA + "/previous-pid";
                let oldAlive = false;
                try {
                  const previous = Number(readFileSync(path, "utf8"));
                  process.kill(previous, 0);
                  oldAlive = true;
                } catch {}
                writeFileSync(path, String(process.pid));
                $.log("started=" + JSON.stringify({pid: process.pid, oldAlive}));
              });
              on("mod.stop", async ($) => {
                $.log("stopping=" + process.pid);
                if (existsSync(Bun.env.SPLICE_MOD_DATA + "/stall")) {
                  const helper = Bun.spawn(["/bin/sleep", "60"], { stderr: "inherit" });
                  writeFileSync(Bun.env.SPLICE_MOD_DATA + "/late-helper", String(helper.pid));
                  Bun.sleepSync(30_000);
                }
                ${if (slowStop) "await Bun.sleep(1500);" else ""}
                $.log("stopped=" + process.pid);
              });
              for (const name of ["session.start", "session.end", "turn.start", "request.send", "response.receive",
                                  "stream.event", "tool.call", "turn.complete"]) {
                on(name, async ($, e) => {
                  $.log("event=" + JSON.stringify({name, payload:e}));
                  const data = Bun.env.SPLICE_MOD_DATA;
                  if (name === "request.send" && existsSync(data + "/hold")) {
                    while (!existsSync(data + "/release")) await Bun.sleep(25);
                  }
                });
              }
            }
            """.trimIndent(),
        )
        old.runner.install(client, source, linked = linked, base = url(oldControl.listeningPort))
        if (startRunner) oldPid = starts(1).single().pid
    }

    fun holdRequest() {
        Files.writeString(data("hold"), "")
    }

    fun releaseRequest() {
        Files.writeString(data("release"), "")
    }

    fun launchOwner(settings: LaunchModSettings = LaunchModSettings()): String =
        old.launch(ProcessHandle.current().pid(), settings)

    fun startTurn(session: String, owner: String = old.launch()) = checkNotNull(old.begin(session, owner))

    fun successorTurn(session: String, owner: String? = null) = checkNotNull(next.begin(session, owner))

    fun breakReplacement() {
        Files.writeString(root.resolve("recorder/hooks/register.ts"), "throw new Error('replacement failed to load');")
    }

    fun forgetLedger(session: String, owner: String) {
        Files.deleteIfExists(old.runner.paths.modsDir.resolve("sessions/$session.$owner.json"))
    }

    suspend fun through(event: String, session: String? = null) = old.through(RECORDER, event, session)

    suspend fun awaitMods(client: HttpClient, state: String) = old.runner.eventually {
        val answer = client.get(modsUrl) { bearerAuth(key.get()) }
        val rows = Json.parseToJsonElement(answer.bodyAsText()).jsonObject
        rows.takeIf { it.getValue("mods").jsonArray.single().jsonObject["state"]?.jsonPrimitive?.content == state }
    }

    fun recorderProcess(): ProcessHandle = ProcessHandle.of(oldPid).orElseThrow()

    suspend fun retire(leave: Boolean = true) {
        val port = TestPorts.reserve()
        nextControl = control(next, port)
        old.runner.runner.beginHandover(port, key)
        old.sessions.stop()
        check(AsyncFileIo.drain())
        if (leave) leave()
    }

    fun releaseRelay() = old.runner.runner.leave(old.sessions.confirmations)

    suspend fun awaitRelayWaiting() {
        withTimeout(5_000) { relayWaiting.await() }
    }

    suspend fun openSuccessor() {
        oldControl.stop()
        next.sessions.follow(next.runner.mods.presence)
        next.runner.runner.handoverStarts = confirmation
        next.runner.runner.start(recoveredSessions = boot)
        checkNotNull(nextControl).start()
    }

    suspend fun recoverSessions() {
        next.launches.restore()
        next.sessions.restore(next.launches, confirmation.await())
        boot.complete(Unit)
    }

    suspend fun leave() {
        releaseRelay()
        openSuccessor()
        recoverSessions()
    }

    suspend fun finish() = old.runner.runner.stop("handover")

    suspend fun successorRecorderStart(): RecorderStart = starts(2).last()

    suspend fun killRetiringOwner(): List<ProcessHandle> {
        val owner = OrphanedRecorder.start(root)
        try {
            val recorder = ProcessHandle.of(starts(1).single().pid).orElseThrow()
            Files.writeString(data("stall"), "")
            owner.outputStream.write(1)
            owner.outputStream.flush()
            val helper = old.runner.eventually {
                val marker = data("late-helper")
                if (Files.exists(marker)) Files.readString(marker).toLongOrNull() else null
            }
            val process = ProcessHandle.of(helper).orElseThrow()
            check(owner.destroyForcibly().waitFor(10, TimeUnit.SECONDS))
            return listOf(recorder, process)
        } finally {
            if (owner.isAlive) owner.destroyForcibly().waitFor()
        }
    }

    suspend fun awaitGone(processes: List<ProcessHandle>) {
        old.runner.eventually { true.takeIf { processes.none(ProcessHandle::isAlive) } }
    }

    suspend fun recoverRecorder() {
        oldControl.stop()
        next.runner.runner.start()
    }

    private suspend fun starts(count: Int): List<RecorderStart> = old.runner.eventually {
        old.runner.log(RECORDER).lineSequence()
            .filter { it.startsWith("started=") }
            .map { Json.parseToJsonElement(it.substringAfter("started=")).jsonObject }
            .map {
                RecorderStart(
                    it.getValue("pid").jsonPrimitive.long,
                    it.getValue("oldAlive").jsonPrimitive.content == "true",
                )
            }
            .toList().takeIf { it.size >= count }
    }

    private fun control(rig: ModEventsTestRig, port: Int) = controlServerFor(
        port = port,
        heads = emptyMap(),
        config = ConfigService(rig.runner.paths),
        auth = ControlAuth(mgmtKey = key, log = LogSink {}),
    ).apply {
        ports.mods = rig.runner.mods
        ports.modRunner = rig.runner.runner
    }

    private fun url(port: Int) = "http://127.0.0.1:$port"

    private fun data(name: String) = old.runner.paths.modsDir.resolve("data/$RECORDER/$name")

    override fun close() = runBlocking {
        oldControl.stop()
        old.runner.runner.stop()
        nextControl?.stop()
        boot.complete(Unit)
        next.runner.runner.stop()
        client.close()
    }
}

internal data class RecorderStart(val pid: Long, val oldProcessAlive: Boolean)
