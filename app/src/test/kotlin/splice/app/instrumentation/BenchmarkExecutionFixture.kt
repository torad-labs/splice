package splice.app.instrumentation

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpSharing
import splice.codemode.NativeCodeModeRuntime
import splice.codemode.WorkerSpawn
import splice.control.mcp.FakeMcpServer
import splice.control.mcp.McpHost
import splice.control.mcp.McpHostConfig
import splice.control.mcp.McpProcessLauncher
import splice.control.mcp.observation.McpPerfRecorders
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.MonoClock
import splice.core.util.SecureFile
import splice.core.util.WallClock
import splice.upstream.codemode.CodeModePerfContext
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Explicit process entry, never a mock runtime and never an automatically launched benchmark test. */
object BenchmarkExecutionFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "expected task directory and source commit" }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        require(root.toString().startsWith("/tmp/splice-instrumentation-"))
        require(args[1].matches(Regex("[a-f0-9]{40}")))
        require(root.toRealPath() == root)
        require(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().startsWith(root))
        require(Path.of(System.getProperty("user.home")).toRealPath().startsWith(root))
        // A real-time process main bridge, not a coroutine unit test or a virtual-time latency measurement.
        runBlocking { withTimeout(120_000) { execute(root, args[1]) } }
    }

    private suspend fun execute(root: Path, source: String) {
        val children = CopyOnWriteArrayList<JsonObject>()
        val events = CopyOnWriteArrayList<PerfObservation>()
        val recorder = PerfRecorder(
            PerfIdentity("benchmark-execution", "source:$source", "fixture-v1", "fixture-config-v1"),
            PerfObserver { check(events.size < 5_000); events.add(it) },
            ElapsedClock(MonoClock::nowMs),
            WallClock(System::currentTimeMillis),
        )
        val rows = mutableListOf<JsonObject>()
        repeat(5) { native(root, recorder, children, rows, it) }
        repeat(5) { mcp(root, recorder, children, rows, it) }
        check(events.any { it.kind == PerfEventKind.TOOL_FINISHED && it.values["tool_native_span"] == 1.0 })
        check(events.any { it.kind == PerfEventKind.TOOL_FINISHED && it.scope.transport == "mcp" })
        receipt(root, source, children, rows, events)
    }

    private suspend fun native(
        root: Path,
        recorder: PerfRecorder,
        children: MutableList<JsonObject>,
        rows: MutableList<JsonObject>,
        repetition: Int,
    ) {
        NativeCodeModeRuntime(
            spawn = WorkerSpawn { builder ->
                val executable = Path.of(builder.command().first()).toRealPath()
                check(executable.startsWith(root)) { "native bundle must unpack into task tmpdir" }
                spawn(builder, root).also { children.add(child(it, executable, "native")) }
            },
        ).use { runtime ->
            for (cache in listOf("cold", "warm")) {
                val request = recorder.request(PerfScope(head = "bench"), MonoClock.nowMs(), System.currentTimeMillis())
                request.update(
                    request.scope,
                    request.links.copy(traceId = "a".repeat(32), spanId = "b".repeat(16), traceFlags = "01"),
                )
                val attempt = request.attempt("sse")
                val started = System.nanoTime()
                val result = withContext(CodeModePerfContext(request, attempt.links)) {
                    val cell = runtime.startSession("fixture", "text(await tools.echo({}));", setOf("echo"))
                    val calls = cell.advance() as CodeModeStep.Calls
                    check(calls.calls.size == 1)
                    cell.advance(
                        listOf(CodeModeResult(calls.calls.single().id, "fixture-ok")),
                    ) as CodeModeStep.Completed
                }
                check(result.output == "fixture-ok")
                rows.add(row("native", repetition, cache, started))
                attempt.finish("good")
                request.finish("good")
            }
        }
    }

    private fun receipt(
        root: Path,
        source: String,
        children: List<JsonObject>,
        rows: List<JsonObject>,
        events: List<PerfObservation>,
    ) {
        val receipt = buildJsonObject {
            put("schema_version", 1)
            put("cohort", "production_execution_fixture_not_baseline_causal_overhead")
            put("source_commit", source)
            put("process_id", ProcessHandle.current().pid())
            put("state_root", root.toString())
            put("children", JsonArray(children))
            put("returned_results_verified", true)
            put("repetitions", JsonArray(rows))
            putJsonArray("class_sha256") {
                listOf(
                    BenchmarkExecutionFixture::class.java,
                    NativeCodeModeRuntime::class.java,
                    McpHost::class.java,
                    FakeMcpServer::class.java,
                ).forEach { type ->
                    val bytes = checkNotNull(
                        type.getResourceAsStream("/${type.name.replace('.', '/')}.class"),
                    ).use { it.readBytes() }
                    add(buildJsonObject { put("class", type.name); put("sha256", digest(bytes)) })
                }
            }
            put(
                "events",
                Json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(PerfObservation.serializer()),
                    events.toList(),
                ),
            )
        }
        SecureFile.writeAtomic0600(root.resolve("execution-receipt.json"), receipt.toString())
    }

    private suspend fun mcp(
        root: Path,
        recorder: PerfRecorder,
        children: MutableList<JsonObject>,
        rows: MutableList<JsonObject>,
        repetition: Int,
    ) {
        val entry = FakeMcpServer.entry()
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath()
        val host = McpHost(
            McpSharing(true, emptySet(), "http://127.0.0.1:1/mcp/", { "fixture-key" }, DirectoryProbe { false }),
            { buildJsonObject { put("fixture", entry) } },
            McpHostConfig(
                requestTimeout = { 10.seconds },
                initializeTimeout = { 10.seconds },
                runtime = McpHostConfig.Runtime(observations = McpPerfRecorders { recorder }),
            ),
            launcher = McpProcessLauncher { spec ->
                check(Path.of(spec.command).toRealPath() == java)
                val builder = ProcessBuilder(listOf(spec.command, "-Duser.home=${root.resolve("home")}") + spec.args)
                spawn(builder, root).also { children.add(child(it, java, "mcp")) }
            },
            log = { },
        )
        try {
            val initializedAt = System.nanoTime()
            val initialized = host.post("fixture", null, INIT, head = "bench")
            check(initialized.status == 200)
            val session = checkNotNull(initialized.sessionId)
            for (cache in listOf("cold", "warm")) {
                val started = if (cache == "cold") initializedAt else System.nanoTime()
                val reply = host.post("fixture", session, CALL, head = "bench")
                check(reply.status == 200)
                val result = Json.parseToJsonElement(checkNotNull(reply.body)).jsonObject.getValue("result").jsonObject
                val text = JsonScalars.str(result.getValue("content").jsonArray.single().jsonObject, "text")
                val pid = children.last().getValue("pid").toString().toLong()
                check(text == "pid=$pid echo=fixture-ok")
                check(ProcessHandle.of(pid).orElseThrow().isAlive)
                rows.add(row("mcp", repetition, cache, started))
            }
        } finally { host.stop() }
    }

    private fun spawn(builder: ProcessBuilder, root: Path): Process {
        builder.directory(root.toFile())
        builder.environment().clear()
        builder.environment()["PATH"] = "/usr/bin:/bin"
        listOf("CONFIG", "CACHE", "DATA", "STATE").forEach { kind ->
            builder.environment()["XDG_${kind}_HOME"] = root.resolve(kind.lowercase()).toString()
        }
        return builder.start()
    }

    private fun child(process: Process, executable: Path, kind: String): JsonObject {
        check(process.isAlive)
        val handle = process.toHandle()
        check(handle.parent().orElseThrow().pid() == ProcessHandle.current().pid())
        check(Path.of(handle.info().command().orElseThrow()).toRealPath() == executable)
        return buildJsonObject {
            put("kind", kind); put("pid", process.pid()); put("parent_pid", ProcessHandle.current().pid())
            put("start_instant", handle.info().startInstant().orElseThrow().toString())
            put("executable", executable.toString())
            put(
                "executable_sha256",
                Files.newInputStream(executable).use { input ->
                    val sha = MessageDigest.getInstance("SHA-256")
                    java.security.DigestInputStream(
                        input,
                        sha,
                    ).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
                    java.util.HexFormat.of().formatHex(sha.digest())
                },
            )
        }
    }

    private fun row(kind: String, repetition: Int, cache: String, started: Long): JsonObject = buildJsonObject {
        put("kind", kind); put("repetition", repetition); put("cache", cache)
        put("elapsed_ms", (System.nanoTime() - started) / 1_000_000.0)
    }

    private fun digest(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(bytes),
    )
}

private const val INIT = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",""" +
    """"capabilities":{},"clientInfo":{"name":"benchmark","version":"1"}}}"""
private const val CALL = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo",""" +
    """"arguments":{"op":"echo","text":"fixture-ok"}}}"""
