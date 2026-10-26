package splice.provider.codex.stream.native

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.catalog.CodeModeCatalogMessages
import splice.provider.codex.stream.arguments.CodeModeSearchArguments
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeInvocationAction
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeRankedSearch
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.CodeModeToolSearch
import splice.upstream.codemode.NativeCodeModeCell
import splice.upstream.codemode.search.CodeModeSearchCorpus
import java.math.BigInteger

private fun interface NativeSearchObservation {
    suspend fun next(): CodeModeStep
}

/** Discovery stays local; freeform strings cross the Anthropic tool boundary in its input object field. */
internal class CodeModeSearchCell(private val delegate: NativeCodeModeCell, turn: CodexCodeModeBridge.Turn) :
    NativeCodeModeCell {
    override val cellId: String get() = delegate.cellId
    private var search = catalog(turn)
    private var currentTurn = turn

    fun update(turn: CodexCodeModeBridge.Turn) {
        currentTurn = turn
        search = catalog(turn)
    }

    override suspend fun submitResults(results: List<CodeModeResult>) = delegate.submitResults(results)

    override suspend fun <T> withInvocationContext(id: String, action: CodeModeInvocationAction<T>): T =
        delegate.withInvocationContext(id, action)

    override suspend fun advance(results: List<CodeModeResult>): CodeModeStep {
        submitResults(results)
        return observe(DEFAULT_SEARCH_OBSERVATION_MS)
    }

    override suspend fun observe(yieldTimeMs: Long): CodeModeStep = observation { delegate.observe(yieldTimeMs) }

    override suspend fun observe(yieldTimeMs: BigInteger): CodeModeStep = observation { delegate.observe(yieldTimeMs) }

    private suspend fun observation(next: NativeSearchObservation): CodeModeStep {
        while (true) {
            currentCoroutineContext().ensureActive()
            val step = next.next()
            if (step !is CodeModeStep.Calls) return step
            val (local, client) = step.calls.partition { it.name == CodeModeToolSearch.NAME || unrepresentable(it) }
            val exposed = step.copy(
                calls = client.map { call ->
                    if (call.kind == CodeModeManual.Kind.FREEFORM) {
                        call.copy(arguments = JsonObject(mapOf("input" to checkNotNull(call.rawInput))))
                    } else {
                        call
                    }
                },
            )
            if (local.isEmpty()) return exposed
            delegate.submitResults(local.map { call -> delegate.withInvocationContext(call.id) { answer(call) } })
            if (client.isNotEmpty()) return exposed
        }
    }

    override suspend fun terminate(): CodeModeStep = delegate.terminate()
    override suspend fun yieldObservation() = delegate.yieldObservation()
    override fun close() = delegate.close()

    private fun answer(call: CodeModeCall): CodeModeResult = try {
        require(!unrepresentable(call)) {
            val expected = if (call.kind == CodeModeManual.Kind.FREEFORM) {
                "a string input"
            } else {
                "a JSON object for arguments"
            }
            "tool `${call.name}` expects $expected"
        }
        val index = requireNotNull(search) { "unknown tool `${CodeModeToolSearch.NAME}`" }
        val result = presentation(index.search(CodeModeSearchArguments.validate(call.arguments)))
        CodeModeResult(call.id, JsonWire.string(result), payload = result)
    } catch (error: IllegalArgumentException) {
        CodeModeResult(call.id, error.message.orEmpty(), isError = true)
    }

    private fun unrepresentable(call: CodeModeCall): Boolean {
        val input = call.rawInput
        return when (call.kind) {
            CodeModeManual.Kind.FUNCTION -> input != null && input !is JsonObject
            CodeModeManual.Kind.FREEFORM -> input !is JsonPrimitive || !input.isString
        }
    }

    private fun presentation(result: JsonObject): JsonObject {
        val prefixes = CodeModeCatalogMessages(currentTurn.toolPolicy.modelMessages).namespacePrefixes
        val tools = result["tools"] as? JsonArray ?: return result
        val definitions = currentTurn.toolDefinitions.associateBy { CodeModeManual.identifier(it.codeModeName) }
        return JsonObject(
            result + (
                "tools" to JsonArray(
                    tools.map { value ->
                        val item = value as JsonObject
                        val definition = definitions[JsonScalars.str(item, "name")] ?: return@map item
                        val decorated = prefixes.codeMode(
                            listOf(definition.copy(description = JsonScalars.str(item, "description").orEmpty())),
                        ).single()
                        JsonObject(item + ("description" to JsonPrimitive(decorated.description)))
                    },
                )
                ),
        )
    }

    private fun catalog(turn: CodexCodeModeBridge.Turn): CodeModeRankedSearch? {
        if (turn.toolDefinitions.none { it.codeModeName == CodeModeToolSearch.NAME }) return null
        val deferred = turn.toolDefinitions.filter { it.codeModeName in turn.toolPolicy.deferredNames }
        return CodeModeRankedSearch(
            CodeModeSearchCorpus.entries(deferred),
            turn.tools,
            turn.toolPolicy.toolDescriptionFirst,
        )
    }
}

// why: Codex 0.164's code-mode yield defaults, DEFAULT_EXEC_YIELD_TIME_MS and DEFAULT_WAIT_YIELD_TIME_MS
// in code-mode-protocol/src/runtime.rs, are both 10_000 ms.
private const val DEFAULT_SEARCH_OBSERVATION_MS = 10_000L
