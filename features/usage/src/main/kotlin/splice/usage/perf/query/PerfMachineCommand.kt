package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import splice.core.perf.OutcomeTag
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.topology.TopologyLoader
import splice.usage.perf.HeadPerfRows
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.query.PerfQueryJson.queryError
import java.io.IOException
import java.nio.file.Files

/** JSON-only command surface. Legacy human output remains in PerfCommand. */
internal class PerfMachineCommand(
    private val output: TerminalOutput,
    private val rows: HeadPerfRows,
    private val queries: HeadPerfQuery?,
    private val profiles: PerfProfileCommand?,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
) {
    fun execute(args: List<String>, env: EnvReader): Boolean {
        val operation = args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: "summary"
        if (operation == "profile") {
            return write(
                profiles?.execute(args.drop(1))
                    ?: queryError("profile_unavailable", "profile_command_not_wired", "unsupported"),
                capabilities = args.getOrNull(1) == "capabilities",
            )
        }
        val parsed = PerfCliArguments.read(if (args.firstOrNull() == operation) args.drop(1) else args)
        if (parsed == null) return write(queryError("invalid_arguments", "expected_named_flags_and_values"))
        return if (operation == "schema") write(schema(parsed)) else invoke(operation, parsed, env)
    }

    private fun invoke(operation: String, parsed: Map<String, String>, env: EnvReader): Boolean = try {
        val borrowed = queries?.service(env)
        if (borrowed != null) {
            dispatch(operation, parsed, borrowed)
        } else {
            offline(env).use { dispatch(operation, parsed, it) }
        }
    } catch (_: IOException) {
        write(queryError("topology_unreadable", "offline_topology_unavailable"))
    } catch (_: IllegalArgumentException) {
        write(queryError("invalid_configuration", "offline_topology_or_query_invalid"))
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (_: IllegalStateException) {
        write(queryError("invalid_configuration", "offline_topology_or_query_invalid"))
    }

    private fun dispatch(operation: String, parameters: Map<String, String>, service: PerfQueryService): Boolean {
        val parsed = PerfQueryParser.parse(if (operation == "export") parameters - "mode" else parameters, clock())
        val query = parsed.query ?: return write(requireNotNull(parsed.error))
        return when (operation) {
            "summary" -> write(service.summary(query))
            "series" -> write(service.series(query))
            "diagnose" -> write(service.diagnose(query))
            "export" -> PerfJsonlExport(output, service).execute(query, parameters)
            else -> write(queryError("unknown_command", "unsupported_perf_command"))
        }
    }

    private fun schema(parameters: Map<String, String>): JsonObject = if (parameters.isEmpty()) {
        PerfQuerySchema.render()
    } else {
        queryError("invalid_arguments", "schema_accepts_no_filters")
    }

    private fun offline(env: EnvReader): PerfQueryService {
        val keys = TopologyLoader.parse(Files.readString(TopologyLoader.configPath(env))).heads.keys
        return PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = keys
            override fun rows(head: String): PerfRowsSource = rows.rows(head, env)
        })
    }

    private fun write(json: JsonObject, capabilities: Boolean = false): Boolean {
        output.line(json.toString())
        val result = successful(json, capabilities)
        PerfWorkBudget.release(json)
        return result
    }

    private fun successful(json: JsonObject, capabilities: Boolean): Boolean {
        val status = JsonScalars.str(json, "status")
        return if (status != null) {
            status in setOf(OutcomeTag.OK.wire, "empty", "partial", "running", "completed")
        } else {
            capabilities && json["capabilities"] is kotlinx.serialization.json.JsonArray &&
                JsonScalars.str(json, "schema_version") == "1" && "error" !in json
        }
    }
}
