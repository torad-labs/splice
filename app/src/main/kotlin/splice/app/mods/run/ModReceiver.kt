package splice.app.mods.run

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars

internal sealed class ModLoad {
    data class Ready(
        val events: Set<String>,
        val notice: String,
        val refused: List<String>,
        val declared: List<String>,
        val version: String?,
    ) : ModLoad()

    data class Failed(val why: String) : ModLoad()
}

internal class ModReceiver(
    private val name: String,
    private val pipe: ModPipe,
    private val calls: ModCalls,
    private val log: ModLog,
    private val drops: ModDrops,
) {
    val loaded = CompletableDeferred<ModLoad>()

    @Volatile var lastStderr: String? = null
        private set

    @Volatile var lastError: String? = null
        private set

    val child = CompletableDeferred<Long>()

    @Volatile var hostExit: Int? = null
        private set

    fun stdout(text: String?) {
        val line = text?.let(ModLine::decode)
        if (line == null) drops.transport() else receive(line)
    }

    private fun receive(line: JsonObject) {
        when (JsonScalars.str(line, "type")) {
            "ready" -> ready(line)
            "load-failed" -> loaded.complete(ModLoad.Failed(field(line, "why")))
            "handled" -> pipe.handled()
            "aborted" -> Unit // The stopping pipe still owns this delivery until close.
            "call" -> call(line)
            "log" -> log.write(JsonScalars.str(line, "message").orEmpty())
            "error", "timeout" -> error(line)
            else -> receiveDrop(line)
        }
    }

    private fun receiveDrop(line: JsonObject) = when (JsonScalars.str(line, "type")) {
        "guardian" -> JsonScalars.str(line, "pid")?.toLongOrNull()?.let(child::complete)
        "host-exit" -> { hostExit = JsonScalars.str(line, "code")?.toIntOrNull() }
        "dropped" -> drops.transport(count(line))
        "log-dropped" -> drops.log(count(line))
        else -> drops.transport()
    }

    fun stderr(text: String?) {
        if (text == null) {
            drops.log()
        } else {
            if (text.isNotBlank()) lastStderr = text.takeLast(MOD_STDERR_TAIL)
            log.write(text)
        }
    }

    private fun ready(line: JsonObject) {
        val ready = ModLoad.Ready(
            events = strings(line, "events").toSet(),
            notice = field(line, "notice"),
            refused = strings(line, "refused"),
            declared = strings(line, "declared_not_served"),
            version = JsonScalars.str(line, "version"),
        )
        if (loaded.complete(ready)) {
            log.write(ready.notice)
            (ready.refused + ready.declared).forEach(log::write)
        }
    }

    private fun call(line: JsonObject) {
        val id = JsonScalars.str(line, "id")?.toLongOrNull()
        if (id == null) {
            drops.transport()
        } else {
            val reply = Cancellables.runCatchingBestEffort {
                calls.reply(name, id, JsonScalars.str(line, "method"))
            }.getOrElse { calls.error(id, "Splice could not list sessions.") }
            pipe.reply(id, reply)
        }
    }

    private fun error(line: JsonObject) {
        val error = "${field(line, "event")}: ${field(line, "message")}"
        lastError = error
        log.write(error)
    }

    private fun field(line: JsonObject, key: String): String =
        JsonScalars.str(line, key).orEmpty().take(MOD_DIAGNOSTIC_CHARS)

    private fun strings(line: JsonObject, key: String): List<String> =
        (line[key] as? JsonArray).orEmpty().map { JsonScalars.strOrEmpty(it).take(MOD_DIAGNOSTIC_CHARS) }

    private fun count(line: JsonObject): Long =
        JsonScalars.str(line, "count")?.toLongOrNull()?.coerceIn(1, Int.MAX_VALUE.toLong()) ?: 1
}

private const val MOD_STDERR_TAIL = 512 // why: actionable exit evidence without a whole stderr line
private const val MOD_DIAGNOSTIC_CHARS = 2048 // why: keep one mod's diagnostics short on the catalog and log
