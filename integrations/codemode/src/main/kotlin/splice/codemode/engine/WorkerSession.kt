// NEW: one guest context owns callbacks and statement state inside its session's native engine.
package splice.codemode.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import splice.codemode.CodeModeCellLauncher
import splice.codemode.CodeModeEvalProjection
import splice.codemode.CodeModeFrames
import splice.codemode.CodeModeHeap
import splice.codemode.CodeModeScopeSeal
import splice.codemode.CodeModeStatementParser
import splice.codemode.CodeModeStatementSyntax
import splice.codemode.StatementInput
import splice.codemode.StreamingCodeModeWire
import splice.codemode.StreamingWorkerSession
import splice.codemode.WorkerBridge
import splice.codemode.WorkerReply
import splice.codemode.WorkerStart
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult

private const val IDLE_FAILURE: String = "Code execution paused without a tool call"

internal class WorkerSession(
    engine: Engine,
    heapLimitBytes: Long = CodeModeHeap.guestBytes(),
) : AutoCloseable {
    private val context: Context = Context.newBuilder("js")
        .engine(engine)
        .option("sandbox.MaxHeapMemory", "${heapLimitBytes}B")
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .build()

    // Warm-up belongs to engine admission, never to the source execution deadline.
    private val launcher: Value = context.eval(CodeModeCellLauncher.source()).also { launcher ->
        val warmUp = launcher.execute(
            "text(ALL_TOOLS.length); await tools.warm({}); return 1;",
            CodeModeToolCatalog.render(WorkerStart("", setOf("warm"))),
            WorkerBridge(setOf("warm")).host,
        )
        warmUp.getMember("settle").execute("1", "{}", false)
    }
    private var bridge: WorkerBridge? = null
    private var settle: Value? = null
    private var pendingCalls: List<CodeModeCall> = emptyList()
    private var streaming: StreamingWorkerSession? = null

    fun start(start: WorkerStart): WorkerReply {
        val bridge = WorkerBridge(start.tools).also { this.bridge = it }
        scopeViolation(start)?.let { return WorkerReply(null, "", it) }
        val evalProjection = ProxyExecutable { values ->
            CodeModeEvalProjection.compile(values[0].asString(), values[1].asString(), values[2].asString())
        }
        val control = launcher.execute(
            if (start.streaming) null else start.source,
            CodeModeToolCatalog.render(start),
            bridge.host,
            evalProjection,
            JsonArray(start.sealedGlobals.sorted().map(::JsonPrimitive)).toString(),
        )
        settle = control.getMember("settle")
        if (start.streaming) {
            streaming = StreamingWorkerSession(
                control.getMember("stream"),
                bridge,
                CodeModeStatementSyntax(::validStatement),
                start.sealedGlobals,
            ).also {
                it.append(start.source, complete = false, error = null)
            }
        }
        return reply()
    }

    fun advance(results: List<CodeModeResult>): WorkerReply {
        CodeModeFrames.validateResultSet(pendingCalls, results)
        val bridge = checkNotNull(bridge)
        bridge.clearSent(pendingCalls.size)
        results.forEach { result ->
            checkNotNull(settle).execute(result.id, result.output, result.isError)
        }
        streaming?.drain()
        return reply()
    }

    private fun scopeViolation(start: WorkerStart): String? {
        if (start.streaming || start.sealedGlobals.isEmpty()) return null
        val parser = CodeModeStatementParser(CodeModeStatementSyntax(::validStatement))
        parser.append(start.source, finished = true, error = null)
        val program = parser.next() as? StatementInput.Program ?: return null
        return CodeModeScopeSeal.violation(program.bindings, start.sealedGlobals)
    }

    private fun validStatement(source: String): Boolean = try {
        val wrapped = "\"use strict\"; (async () => {\n$source\n})"
        context.parse(Source.newBuilder("js", wrapped, "splice-statement").cached(false).build())
        true
    } catch (error: PolyglotException) {
        if (!error.isSyntaxError) throw error
        false
    }

    fun input(frame: JsonObject): WorkerReply {
        require(pendingCalls.isEmpty()) { "Source input cannot replace pending tool results" }
        StreamingCodeModeWire.append(frame, checkNotNull(streaming))
        return reply()
    }

    override fun close() {
        context.close(true)
    }

    private fun reply(): WorkerReply {
        val bridge = checkNotNull(bridge)
        val calls = bridge.calls()
        pendingCalls = calls
        if (calls.isNotEmpty()) return WorkerReply(calls = calls, output = null, error = null)
        bridge.completion()?.let { completion ->
            return WorkerReply(calls = null, output = completion.output, error = completion.error)
        }
        return if (streaming != null) {
            WorkerReply(null, null, null, waitingForInput = true)
        } else {
            WorkerReply(calls = null, output = "", error = IDLE_FAILURE)
        }
    }
}
