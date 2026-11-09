package splice.app.instrumentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import java.nio.file.Files
import java.nio.file.Path

/** Paused-producer command entry. Full-GC probes are issued externally only after a returned checkpoint. */
object BenchmarkRetainedFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "expected task root, local|minimal, virtual start epoch, source commit" }
        val root = Path.of(args[0]).toAbsolutePath().normalize()
        require(root.toString().startsWith("/tmp/splice-instrumentation-"))
        require(root.toRealPath() == root)
        require(Path.of(System.getProperty("user.home")).toRealPath().startsWith(root))
        require(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().startsWith(root))
        require(args[1] in setOf("local", "minimal"))
        require(args[3].matches(Regex("[a-f0-9]{40}")))
        val start = args[2].toLong()
        require(start > 0 && start % RETAINED_HOUR_MS == 0L)
        val paths = StatePaths(root.resolve("state"), EnvReader { null }, root.resolve("home"))
        Files.createDirectories(paths.stateDir)
        BenchmarkRetainedOwners(paths, args[1] == "local", start, args[3]).use { owners ->
            emit(owners, "ready")
            var line = readlnOrNull()
            while (line != null && line != "stop") {
                command(owners, line)
                line = readlnOrNull()
            }
        }
    }

    private fun command(owners: BenchmarkRetainedOwners, line: String) {
        require(line.length <= 100)
        when {
            line == "sample" -> emit(owners, "paused")
            line.startsWith("advance ") -> {
                val hours = line.removePrefix("advance ").toInt()
                require(hours in 1..864)
                owners.advance(hours)
                emit(owners, "paused")
            }
            else -> error("unsupported fixture command")
        }
    }

    private fun emit(owners: BenchmarkRetainedOwners, status: String) {
        println(
            buildJsonObject {
                put("schema_version", 1)
                put("status", status)
                put("process_id", ProcessHandle.current().pid())
                put("producer_paused", true)
                put("measurement", owners.status())
                put("identity", Json.encodeToJsonElement(owners.identity))
            },
        )
        System.out.flush()
    }
}

internal const val RETAINED_HOUR_MS = 3_600_000L
