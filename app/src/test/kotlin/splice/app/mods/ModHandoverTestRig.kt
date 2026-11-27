package splice.app.mods

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import splice.app.control.ControlAuth
import splice.app.control.ControlServer
import splice.app.control.controlServerWith
import splice.app.control.topologyHealthFor
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.util.AsyncFileIo
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val RECORDER = "recorder"

private val backgroundSleep = """
    if (!existsSync(path)) {
      const child = Bun.spawnSync(["sh", "-c", "sleep 45 >/dev/null & echo $!"], {stderr:"inherit"});
      writeFileSync(Bun.env.SPLICE_MOD_DATA + "/child", child.stdout);
    }
""".trimIndent()

internal class ModHandoverTestRig(private val root: Path) : AutoCloseable {
    private val old = ModEventsTestRig(root)
    private val next by lazy { ModEventsTestRig(root) }
    val key = MgmtKey(old.runner.paths, LogSink {})
    private val client = HttpClient(CIO)
    private val oldControl = control(old)
    private var nextControl: ControlServer? = null
    val modsUrl get() = "${url(checkNotNull(nextControl).listeningPort)}/api/mods"
    val handoverUrl get() = "$modsUrl/handover"

    private var oldPid = 0L

    suspend fun installRecorder(
        slowStop: Boolean = false,
        startRunner: Boolean = true,
        backgroundChild: Boolean = false,
    ) {
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
                ${if (backgroundChild) backgroundSleep else ""}
                writeFileSync(path, String(process.pid));
                $.log("started=" + JSON.stringify({pid: process.pid, oldAlive}));
              });
              on("mod.stop", async ($) => {
                $.log("stopping=" + process.pid);
                if (existsSync(Bun.env.SPLICE_MOD_DATA + "/stall")) Bun.sleepSync(30_000);
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
        old.runner.install(client, source, base = url(oldControl.listeningPort))
        if (startRunner) oldPid = starts(1).single().pid
    }

    fun holdRequest() {
        Files.writeString(data("hold"), "")
    }

    fun releaseRequest() {
        Files.writeString(data("release"), "")
    }

    fun startTurn(session: String) = checkNotNull(old.begin(session, old.launch()))

    suspend fun through(event: String) = old.through(RECORDER, event)

    fun recorderProcess(): ProcessHandle = ProcessHandle.of(oldPid).orElseThrow()

    fun backgroundChild(): ProcessHandle {
        val pid = Files.readString(data("child")).trim().toLong()
        return ProcessHandle.of(pid).orElseThrow()
    }

    fun serveOnSuccessor(session: String) = next.complete(session, next.launch())

    suspend fun waitForBackgroundExit(child: ProcessHandle) {
        old.runner.eventually { true.takeUnless { child.isAlive } }
    }

    suspend fun retire(leave: Boolean = true) {
        val control = control(next)
        nextControl = control
        control.start()
        old.runner.runner.beginHandover(control.listeningPort, key)
        old.sessions.stop()
        check(AsyncFileIo.drain())
        if (leave) leave()
    }

    suspend fun leave() {
        oldControl.stop()
        next.launches.restore()
        next.sessions.restore(next.launches)
        next.runner.runner.start()
        old.runner.runner.leave()
    }

    suspend fun finish() = old.runner.runner.stop("handover")

    suspend fun successorRecorderStart(): RecorderStart = starts(2).last()

    suspend fun killRetiringOwner(): ProcessHandle {
        val owner = OrphanedRecorder.start(root)
        try {
            val recorder = ProcessHandle.of(starts(1).single().pid).orElseThrow()
            Files.writeString(data("stall"), "")
            owner.outputStream.write(1)
            owner.outputStream.flush()
            old.runner.eventually { old.runner.log(RECORDER).takeIf { "stopping=${recorder.pid()}" in it } }
            check(owner.destroyForcibly().waitFor(10, TimeUnit.SECONDS))
            check(recorder.isAlive) { "The recorder did not survive its owner to exercise the reaper" }
            return recorder
        } finally {
            if (owner.isAlive) owner.destroyForcibly().waitFor()
        }
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

    private fun control(rig: ModEventsTestRig) = controlServerWith(
        heads = emptyMap(),
        config = ConfigService(rig.runner.paths),
        health = topologyHealthFor(emptyMap()),
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
        next.runner.runner.stop()
        client.close()
    }
}

internal data class RecorderStart(val pid: Long, val oldProcessAlive: Boolean)
