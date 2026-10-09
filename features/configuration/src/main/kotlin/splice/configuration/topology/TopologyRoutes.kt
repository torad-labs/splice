// NEW: V4-128, FEATURES.md 4.7 and 6 — the topology routes, splice.toml read and written as data:
//
//   GET /api/topology   {path, topology, stale}
//   PUT /api/topology   body {topology}  ->  {ok, backup_path?, findings, restart_required}
//
// THE TOPOLOGY IS THE FILE ON DISK, parsed now, not the one this daemon booted with: the console edits
// the file, and `stale` (the same probe /health reports as topologyStale) says whether the running
// daemon still matches it. Every key's source is the file, so no per-key provenance rides this
// payload; the six knob layers are GET /api/config's.
//
// A REFUSAL IS AN ANSWER, NOT AN ERROR. A body that is not {topology: {...}} is a 400. A topology that
// does not decode, fails a check, or cannot be written faithfully answers 200 with ok false and
// findings, which the console renders beside the editor, and splice.toml is byte-identical after it.
// restart_required is false for a refusal, which wrote nothing, and for a write that changed nothing but
// context windows: the running daemon re-reads those (V4-162, TopologyWindows) and every other key is
// boot-only.
//
// SECRETS ARE NEVER READ BACK. Every `extra_headers` value is served as [MASK], since a header can
// carry a key and nothing here can tell which does. On PUT the mask means "keep the stored value";
// an absent header means "remove it"; an empty string is a real, empty value. A header whose real
// value is the mask text itself therefore cannot be set through this route.
// LAYOUT-01: the configuration capability's topology slice, beside the TopologyStale port it serves.
package splice.configuration.topology

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.prompt.SystemPromptMode
import splice.core.topology.Topology
import splice.core.topology.TopologyFinding
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.topology.TopologyWriterSource
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.http.JsonReply

/** What every `extra_headers` value reads as. */
internal const val MASK = "********"
internal const val TOPOLOGY_UNWIRED = "the topology writer is not wired into this control plane"
private const val PROVIDERS = "providers"
private const val EXTRA_HEADERS = "extra_headers"
private const val TOPOLOGY = "topology"

public class TopologyRoutes(private val source: TopologyWriterSource, private val stale: TopologyStale) {
    private val json = Json { encodeDefaults = false }

    public fun read(): JsonReply {
        val writer = source() ?: return error(HttpStatusCode.ServiceUnavailable, TOPOLOGY_UNWIRED)
        val current = Cancellables.runCatchingCancellable { writer.current() }.getOrElse { failure ->
            val why = SafeFailureText.render(failure)
            return error(HttpStatusCode.InternalServerError, "splice.toml does not parse: $why")
        }
        val body = buildJsonObject {
            put("path", writer.path.toString())
            put(TOPOLOGY, TopologySecrets().masked(current))
            put("stale", stale())
        }
        return JsonReply(HttpStatusCode.OK, body.toString())
    }

    /** Resolve a draft head's instruction file without writing splice.toml. */
    public fun preview(body: String): JsonReply = PromptPreview(source).reply(body)

    /** The `topology` object of a request body. A body that is not JSON, not an object, or without that member
     *  gets the same answer, one 400 naming the shape expected, so the parse failure has nothing more to say. */
    private fun topologyObject(body: String): JsonObject? = try {
        json.parseToJsonElement(body).jsonObject[TOPOLOGY] as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    public fun write(body: String): JsonReply {
        val writer = source() ?: return error(HttpStatusCode.ServiceUnavailable, TOPOLOGY_UNWIRED)
        val requested = topologyObject(body)
            ?: return error(HttpStatusCode.BadRequest, "the body must be {\"topology\": {...}}")
        val attempt = Cancellables.runCatchingCancellable { attempt(writer, requested) }.getOrElse { failure ->
            Attempt(refused(TopologyFinding("splice.toml", SafeFailureText.render(failure))))
        }
        return JsonReply(HttpStatusCode.OK, outcome(attempt).toString())
    }

    /** What a PUT did, and whether the running daemon needs a restart to serve it (V4-162). Only a
     *  write that moved a boot-only key needs one: a refusal wrote nothing (V4-413). */
    private data class Attempt(val result: TopologyWriteResult, val movedBootKeys: Boolean = true) {
        val restartRequired: Boolean = result is TopologyWriteResult.Written && movedBootKeys
    }

    /** Decode the MASKED request first, so a decoder message can only ever quote the mask; then put
     *  the stored secrets back, decode again, and write. */
    private fun attempt(writer: TopologyWriter, requested: JsonObject): Attempt {
        val masked = decode(requested).fold(
            onSuccess = { null },
            onFailure = { SafeFailureText.maskedInputRefusal(it) },
        )
        if (masked != null) return Attempt(refused(TopologyFinding(TOPOLOGY, masked)))
        val secrets = TopologySecrets()
        val stored = writer.current()
        val unmasked = secrets.unmasked(requested, stored)
        if (secrets.findings.isNotEmpty()) return Attempt(TopologyWriteResult.Refused(secrets.findings))
        val topology = decode(unmasked).getOrThrow()
        val before = decode(stored)
        val unreadable = unreadableInstructions(writer, before.getOrNull(), topology)
        // V4-162: the running daemon re-reads context windows, so a write that moved nothing else
        // needs no restart. Compared the way the writer diffs: the file as it stood against what is
        // written, both holding their real secrets. A file that no longer decodes says restart.
        val restart = before.fold(
            onSuccess = { it.withoutWindows() != topology.withoutWindows() },
            onFailure = { true },
        )
        return if (unreadable.isEmpty()) {
            Attempt(writer.write(topology), restart)
        } else {
            Attempt(TopologyWriteResult.Refused(unreadable))
        }
    }

    /** V4-400: each head whose `system_prompt_file` this write sets or changes is read the way the
     *  preview reads it, and a file the preview refuses is refused here with the preview's sentence. A
     *  file the stored topology already names is left alone: it is not this write's, and an unrelated
     *  edit must not be held by a file that has since moved. */
    private fun unreadableInstructions(
        writer: TopologyWriter,
        stored: Topology?,
        wanted: Topology,
    ): List<TopologyFinding> =
        wanted.heads.mapNotNull { (key, head) ->
            val file = head.systemPromptFile?.takeIf(String::isNotBlank)
            if (file == null || stored?.heads?.get(key)?.systemPromptFile == file) {
                null
            } else {
                val mode = head.systemPromptMode ?: SystemPromptMode.APPEND
                PromptPreview(source).refusal(writer, key, file, mode)
                    ?.let { TopologyFinding("heads.$key.system_prompt_file", it) }
            }
        }

    private fun decode(tree: JsonObject): Result<Topology> =
        Cancellables.runCatchingCancellable { json.decodeFromJsonElement(Topology.serializer(), tree) }

    private fun refused(finding: TopologyFinding): TopologyWriteResult = TopologyWriteResult.Refused(listOf(finding))

    private fun outcome(attempt: Attempt): JsonObject = buildJsonObject {
        val result = attempt.result
        put("ok", result is TopologyWriteResult.Written)
        (result as? TopologyWriteResult.Written)?.backup?.let { put("backup_path", it.toString()) }
        val findings = (result as? TopologyWriteResult.Refused)?.findings.orEmpty()
        put(
            "findings",
            buildJsonArray {
                findings.forEach { finding ->
                    add(
                        buildJsonObject {
                            put("path", finding.path)
                            put("message", finding.message)
                        },
                    )
                }
            },
        )
        put("restart_required", attempt.restartRequired)
    }

    private fun error(status: HttpStatusCode, message: String): JsonReply =
        JsonReply(status, buildJsonObject { put("error", message) }.toString())
}

/** The mask over `providers.<key>.extra_headers`, both ways. [findings] collects what [unmasked] could
 *  not resolve: a masked value for a header the file does not store. */
internal class TopologySecrets {
    val findings: MutableList<TopologyFinding> = mutableListOf()

    fun masked(tree: JsonObject): JsonObject = headers(tree) { _, _, _ -> JsonPrimitive(MASK) }

    fun unmasked(tree: JsonObject, stored: JsonObject): JsonObject = headers(tree) { provider, name, value ->
        val kept = (value as? JsonPrimitive)?.takeIf { it.isString && it.content == MASK }?.let {
            stored[PROVIDERS]?.jsonObject?.get(provider)?.jsonObject?.get(EXTRA_HEADERS)?.jsonObject?.get(name)
        }
        val unresolved = kept == null && value == JsonPrimitive(MASK)
        if (unresolved) {
            findings += TopologyFinding(
                "$PROVIDERS.$provider.$EXTRA_HEADERS.$name",
                "a masked value keeps a stored header, and splice.toml stores none by this name; type its value",
            )
        }
        kept ?: value
    }

    private fun headers(tree: JsonObject, value: HeaderValue): JsonObject {
        val providers = tree[PROVIDERS] as? JsonObject ?: return tree
        val mapped = providers.mapValues { (key, provider) ->
            val table = provider as? JsonObject
            val headers = table?.get(EXTRA_HEADERS) as? JsonObject
            if (headers == null) {
                provider
            } else {
                val mapped = headers.mapValues { (name, v) -> value(key, name, v) }
                JsonObject(table + (EXTRA_HEADERS to JsonObject(mapped)))
            }
        }
        return JsonObject(tree + (PROVIDERS to JsonObject(mapped)))
    }
}

/** One header value, given its provider key and header name. */
internal fun interface HeaderValue {
    operator fun invoke(provider: String, name: String, value: JsonElement): JsonElement
}
