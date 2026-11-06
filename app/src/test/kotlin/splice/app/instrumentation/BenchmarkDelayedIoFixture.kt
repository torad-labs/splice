package splice.app.instrumentation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.app.ApplicationArtifactStamp
import splice.core.util.AsyncFileIo
import splice.core.util.JsonScalars
import splice.topology.TopologyLoader
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarFile
import splice.app.main as daemonMain

/** Explicit, candidate-bound process fixture; never an automatically executed test or a live-daemon hook. */
object BenchmarkDelayedIoFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val binding = Binding(args)
        binding.verify()
        DelayRun(binding).use { delay ->
            delay.start()
            // The production entrypoint owns daemon startup and shutdown. The driver owns its process deadline.
            runBlocking { daemonMain(arrayOf("daemon")) }
        }
    }

    private class Binding(args: Array<String>) {
        val root: Path
        private val manifest: JsonObject
        private val manifestSha: String
        private val artifact: Path
        private val fixture: Path
        val nonce: String

        init {
            require(args.size == 2) { "expected task root and manifest SHA-256" }
            root = taskPath(args[0])
            manifestSha = args[1].also { require(it.matches(SHA256)) }
            val file = root.resolve("delayed-io-binding.json")
            manifest = Json.parseToJsonElement(boundText(file, manifestSha)).jsonObject
            require(manifest.keys == MANIFEST_KEYS) { "unexpected binding fields" }
            artifact = taskPath(value("artifact_path"))
            fixture = taskPath(value("fixture_path"))
            nonce = value("nonce").also { require(it.matches(SHA256)) }
            require(value("source_commit").matches(Regex("[a-f0-9]{40}")))
            require(value("process_build").matches(Regex("compiled-artifact-inputs-sha256:[a-f0-9]{64}")))
        }

        fun verify() {
            require(System.getProperty("java.class.path") == "$artifact${java.io.File.pathSeparator}$fixture") {
                "candidate-first two-JAR classpath required"
            }
            require(digest(artifact, ARTIFACT_BYTES) == value("artifact_sha256")) { "candidate digest mismatch" }
            require(digest(fixture, FIXTURE_BYTES) == value("fixture_sha256")) { "fixture digest mismatch" }
            verifyFixtureJar()
            val loader = BenchmarkDelayedIoFixture::class.java.classLoader
            PRODUCTION_CLASSES.forEach { name ->
                // Identity inspection only: the entrypoint above is invoked directly, never reflectively.
                require(source(Class.forName(name, false, loader)) == artifact) { "production class source mismatch" }
            }
            require(source(BenchmarkDelayedIoFixture::class.java) == fixture) { "fixture class source mismatch" }
            require(
                "compiled-artifact-inputs-sha256:${ApplicationArtifactStamp.fingerprint()}" == value("process_build"),
            ) {
                "loaded production fingerprint mismatch"
            }
            verifyPaths()
            verifyConfig()
        }

        fun receipt(): JsonObject = buildJsonObject {
            put("manifest_sha256", manifestSha)
            put("synthetic_fixture_build", "source:${value("source_commit")}")
            put("live_process_build", value("process_build"))
            put("artifact_path", artifact.toString())
            put("artifact_sha256", value("artifact_sha256"))
            put("fixture_path", fixture.toString())
            put("fixture_sha256", value("fixture_sha256"))
            put("config_sha256", value("config_sha256"))
            put("task_root", root.toString())
            putJsonArray("candidate_classes") {
                PRODUCTION_CLASSES.forEach { name ->
                    val type = Class.forName(name, false, BenchmarkDelayedIoFixture::class.java.classLoader)
                    add(
                        buildJsonObject {
                            put("class", name)
                            put("code_source", source(type).toString())
                            put("jar_sha256", value("artifact_sha256"))
                        },
                    )
                }
            }
        }

        private fun verifyPaths() {
            require(Path.of(System.getProperty("user.home")).toRealPath() == root.resolve("home"))
            require(Path.of(System.getProperty("java.io.tmpdir")).toRealPath() == root.resolve("tmp"))
            require(Path.of("").toRealPath() == root.resolve("home")) { "task working directory required" }
            val expected = mutableMapOf(
                "PATH" to root.resolve("empty-path").toString(),
                "LANG" to "C.UTF-8",
                "LC_ALL" to "C.UTF-8",
                "SPLICE_CONFIG" to root.resolve("splice.toml").toString(),
                "SPLICE_STATE_DIR" to root.resolve("state").toString(),
            )
            listOf("CONFIG", "DATA", "STATE", "CACHE").forEach { kind ->
                expected["XDG_${kind}_HOME"] = root.resolve("home/${kind.lowercase()}").toString()
            }
            require(System.getenv() == expected) { "exact stripped task environment required" }
            expected.filterKeys {
                it !in setOf(
                    "LANG",
                    "LC_ALL",
                )
            }.values.forEach { require(taskPath(it).startsWith(root)) }
            Files.list(root.resolve("empty-path")).use { require(it.findAny().isEmpty) { "empty task PATH required" } }
        }

        private fun verifyConfig() {
            val file = root.resolve("splice.toml")
            val topology = TopologyLoader.parse(boundText(file, value("config_sha256")))
            require(topology.providers.keys == setOf("bench") && topology.heads.keys == setOf("bench"))
            val provider = topology.providers.getValue("bench")
            val endpoint = URI(provider.baseUrl)
            require(
                endpoint.scheme == "http" && endpoint.host == "127.0.0.1",
            ) { "synthetic loopback provider required" }
            require(endpoint.rawUserInfo == null && endpoint.rawQuery == null && endpoint.rawFragment == null)
            require(endpoint.path.isEmpty())
            val head = topology.heads.getValue("bench")
            val controlPort = checkNotNull(topology.daemon.controlPort)
            val ports = listOf(endpoint.port, head.port, controlPort)
            require(ports.distinct().size == ports.size && ports.all { it in 1025..65535 && it !in setOf(3096, 3099) })
            require(topology == TopologyLoader.parse(approvedConfig(endpoint.port, head.port, controlPort))) {
                "exact synthetic storage configuration required"
            }
        }

        // The storage/B08 fixture schema, parsed by the production parser; no additional paths, commands or peers.
        private fun approvedConfig(upstreamPort: Int, headPort: Int, controlPort: Int): String = """
            [defaults]
            perf_collection_enabled = "true"
            [telemetry.operator]
            prometheus_enabled = false
            otlp_endpoint = ""
            otlp_protocol = "http/protobuf"
            otlp_headers = '{}'
            otlp_signals = ""
            [daemon]
            control_port = $controlPort
            state_dir = ${kotlinx.serialization.json.JsonPrimitive(root.resolve("state").toString())}
            auto_update = false
            [providers.bench]
            dialect = "anthropic-passthrough"
            base_url = "http://127.0.0.1:$upstreamPort"
            auth = { kind = "client" }
            [[providers.bench.models]]
            id = "fixture-model"
            context_window = 2000000
            [heads.bench]
            provider = "bench"
            port = $headPort
            pinned_model = "fixture-model"
            discovery_prefix = "claude-bench--"
        """.trimIndent()

        private fun verifyFixtureJar() {
            JarFile(fixture.toFile()).use { jar ->
                require(jar.manifest?.mainAttributes?.getValue("Class-Path") == null)
                val entries = jar.entries().asSequence().take(FIXTURE_ENTRIES + 1).toList()
                require(entries.size <= FIXTURE_ENTRIES)
                require(
                    entries.all { entry ->
                        entry.name in FIXTURE_DIRECTORIES || entry.name == "META-INF/MANIFEST.MF" ||
                            (
                                entry.name.endsWith(".class") &&
                                    (entry.name == "$FIXTURE_CLASS.class" || entry.name.startsWith("$FIXTURE_CLASS$"))
                                )
                    },
                ) { "fixture-only JAR required" }
            }
        }

        private fun value(key: String): String = checkNotNull(JsonScalars.str(manifest, key))

        private fun source(type: Class<*>): Path = Path.of(
            checkNotNull(type.protectionDomain.codeSource).location.toURI(),
        ).toRealPath()

        private fun taskPath(raw: String): Path {
            val path = Path.of(raw)
            require(path.isAbsolute && path.normalize() == path && path.toRealPath() == path)
            require(path.startsWith(TASK_ROOT) && path != TASK_ROOT) { "canonical task path required" }
            return path
        }

        private fun digest(path: Path, maximum: Long): String {
            require(taskPath(path.toString()) == path && Files.isRegularFile(path) && Files.size(path) <= maximum)
            val sha = MessageDigest.getInstance("SHA-256")
            DigestInputStream(Files.newInputStream(path), sha).use { it.transferTo(OutputStream.nullOutputStream()) }
            return HexFormat.of().formatHex(sha.digest())
        }

        private fun boundText(path: Path, sha256: String): String {
            require(taskPath(path.toString()) == path && Files.isRegularFile(path))
            val bytes = Files.newInputStream(path).use { it.readNBytes(MANIFEST_BYTES + 1) }
            require(bytes.size <= MANIFEST_BYTES) { "binding input exceeds limit" }
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == sha256) {
                "binding input digest mismatch"
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private class DelayRun(private val binding: Binding) : AutoCloseable {
        private val input = FileInputStream(FileDescriptor.`in`).channel
        private val release = CountDownLatch(1)
        private val entered = CountDownLatch(1)
        private val ended = CountDownLatch(1)
        private val startedAt = AtomicReference<Instant>()
        private val endedAt = AtomicReference<Instant>()
        private val startedNanos = AtomicLong()
        private val endedNanos = AtomicLong()
        private val releaseReason = AtomicReference("none")
        private val closed = AtomicBoolean()
        private val reader = Executors.newSingleThreadExecutor { task -> thread(task, "control") }
        private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            thread(
                task,
                "watchdog",
            )
        }

        fun start() {
            emit("bound", binding.receipt())
            reader.execute(::control)
        }

        private fun control() {
            try {
                require(command() == "ARM ${binding.nonce}") { "one ARM command required" }
                binding.verify()
                val baseline = snapshot()
                val admittedAt = Instant.now()
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HOLD_MS)
                watchdog.schedule(Runnable { unblock("deadline") }, HOLD_MS, TimeUnit.MILLISECONDS)
                val accepted = AsyncFileIo.submit { hold(deadline) }
                emit(
                    "submitted",
                    buildJsonObject {
                        put("accepted", accepted)
                        put("baseline", baseline)
                        put("admitted_at", admittedAt.toString())
                        put("release_deadline_nanos", deadline)
                    },
                )
                if (!accepted) return
                if (!entered.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
                    emit("start_timeout")
                    return
                }
                emit(
                    "started",
                    buildJsonObject {
                        put("task_started_at", startedAt.get().toString())
                        put("task_started_nanos", startedNanos.get())
                    },
                )
                // Exactly one command. EOF, any extra byte, the watchdog, or close releases the held task.
                val next = ByteBuffer.allocate(1)
                val count = input.read(next)
                unblock(if (count < 0) "eof" else "extra_input")
            } catch (_: java.nio.channels.AsynchronousCloseException) {
                // Watchdog/owner close deliberately interrupts the blocking stdin channel.
            } catch (_: Exception) {
                emit("refused")
            } finally {
                unblock("controller_finished")
                reportEnd()
                watchdog.shutdownNow()
                reader.shutdown()
            }
        }

        private fun hold(deadline: Long) {
            startedAt.set(Instant.now())
            startedNanos.set(System.nanoTime())
            entered.countDown()
            try {
                release.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            } finally {
                endedAt.set(Instant.now())
                endedNanos.set(System.nanoTime())
                ended.countDown()
            }
        }

        private fun reportEnd() {
            if (startedAt.get() == null) return
            val finished = ended.await(CLEANUP_MS, TimeUnit.MILLISECONDS)
            emit(
                "ended",
                buildJsonObject {
                    put("task_body_finished", finished)
                    put("task_started_at", startedAt.get().toString())
                    put("task_ended_at", endedAt.get()?.toString().orEmpty())
                    put("task_started_nanos", startedNanos.get())
                    put("task_ended_nanos", endedNanos.get())
                    put("release_reason", releaseReason.get())
                },
            )
        }

        private fun command(): String {
            val bytes = ByteBuffer.allocate(COMMAND_BYTES)
            while (bytes.hasRemaining()) {
                val next = ByteBuffer.allocate(1)
                require(input.read(next) == 1) { "ARM required before EOF" }
                val value = next.array()[0]
                if (value == '\n'.code.toByte()) return String(bytes.array(), 0, bytes.position(), Charsets.US_ASCII)
                require(value in ' '.code.toByte()..'~'.code.toByte()) { "ASCII command required" }
                bytes.put(value)
            }
            error("command length exceeded")
        }

        private fun unblock(reason: String) {
            releaseReason.compareAndSet("none", reason)
            release.countDown()
            input.close()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            unblock("daemon_finished")
            watchdog.shutdownNow()
            reader.shutdownNow()
            reader.awaitTermination(CLEANUP_MS, TimeUnit.MILLISECONDS)
            watchdog.awaitTermination(CLEANUP_MS, TimeUnit.MILLISECONDS)
        }

        private fun emit(event: String, detail: JsonObject = buildJsonObject {}): Unit = System.out.println(
            buildJsonObject {
                put("fixture", "delayed-file-io-v1")
                put("event", event)
                put("pid", ProcessHandle.current().pid())
                put("process_started_at", ProcessHandle.current().info().startInstant().orElseThrow().toString())
                put("observed_at", Instant.now().toString())
                put("observed_nanos", System.nanoTime())
                put("work", snapshot())
                put("detail", detail)
            },
        )

        private fun snapshot(): JsonObject = AsyncFileIo.workSnapshot.let { work ->
            buildJsonObject {
                put("accepted", work.accepted); put("completed", work.completed); put("failed", work.failed)
                put("failed_writes", work.failedWrites); put("dropped", work.dropped); put("pending", work.pending)
                put("running", work.running); put("queued", work.queued); put("scheduled", work.scheduled)
            }
        }

        private fun thread(task: Runnable, role: String): Thread = Executors.defaultThreadFactory().newThread(
            task,
        ).apply {
            name = "splice-delayed-io-$role"
            isDaemon = true
        }
    }

    private val TASK_ROOT = Path.of("/tmp/splice-instrumentation-run")
    private val SHA256 = Regex("[a-f0-9]{64}")
    private val MANIFEST_KEYS = setOf(
        "source_commit",
        "artifact_path",
        "artifact_sha256",
        "fixture_path",
        "fixture_sha256",
        "process_build",
        "config_sha256",
        "nonce",
    )
    private val PRODUCTION_CLASSES = listOf(
        "splice.app.MainKt", "splice.core.util.AsyncFileIo", "splice.app.ApplicationArtifactStamp",
        "splice.app.observability.PerfTelemetry", "splice.head.perf.RequestEvidenceFiles",
        "splice.app.sources.observability.PerfAggregateStore", "kotlin.Unit", "kotlinx.coroutines.BuildersKt",
        "kotlinx.serialization.json.Json", "splice.topology.TopologyLoader",
    )
    private const val FIXTURE_CLASS = "splice/app/instrumentation/BenchmarkDelayedIoFixture"
    private val FIXTURE_DIRECTORIES = setOf("META-INF/", "splice/", "splice/app/", "splice/app/instrumentation/")

    // One fixed five-second maximum from admission also bounds a late-starting injection.
    private const val HOLD_MS = 5_000L

    // A released latch needs only a bounded scheduling grace; the external driver still owns process cleanup.
    private const val CLEANUP_MS = 1_000L

    // ARM, one space, 64 lowercase hex nonce bytes, and a newline; no arbitrary command payload.
    private const val COMMAND_BYTES = 69

    // Binding and TOML are tiny, task-owned inputs; reject large files before reading them into memory.
    private const val MANIFEST_BYTES = 16_384

    // The frozen fat JAR shares the storage driver's existing artifact size ceiling.
    private const val ARTIFACT_BYTES = 512L * 1024 * 1024

    // Only this launcher and its nested classes belong in the fixture JAR.
    private const val FIXTURE_BYTES = 2L * 1024 * 1024

    // Generated Kotlin coroutine/lambda classes are finite; directories and the manifest count too.
    private const val FIXTURE_ENTRIES = 64
}
