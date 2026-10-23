// NEW: one guest context owns a whole script and its callbacks inside its session's native engine.
package splice.codemode.engine

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import splice.codemode.CodeModeCellLauncher
import splice.codemode.CodeModeHeap
import splice.codemode.WorkerBridge
import splice.codemode.WorkerReply
import splice.codemode.WorkerStart
import splice.codemode.wire.CodeModeFrames
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

    fun start(start: WorkerStart): WorkerReply {
        val bridge = WorkerBridge(start.tools).also { this.bridge = it }
        val control = launcher.execute(start.source, CodeModeToolCatalog.render(start), bridge.host)
        settle = control.getMember("settle")
        return reply()
    }

    fun advance(results: List<CodeModeResult>): WorkerReply {
        CodeModeFrames.validatePartialResultSet(pendingCalls, results)
        val bridge = checkNotNull(bridge)
        val received = results.map(CodeModeResult::id).toSet()
        pendingCalls = pendingCalls.filterNot { it.id in received }
        results.forEach { result ->
            checkNotNull(settle).execute(result.id, result.output, result.isError)
            bridge.settleCall(result.id)
        }
        return reply()
    }

    override fun close() {
        context.close(true)
    }

    private fun reply(): WorkerReply {
        val bridge = checkNotNull(bridge)
        val completion = bridge.completion()
        val calls = bridge.takeCalls(completion?.output, completion?.error)
        pendingCalls = pendingCalls + calls
        return when {
            calls.isNotEmpty() -> WorkerReply(calls = calls, output = completion?.output, error = completion?.error)
            completion != null && pendingCalls.isEmpty() ->
                WorkerReply(calls = null, output = completion.output, error = completion.error)
            pendingCalls.isNotEmpty() -> WorkerReply(
                calls = null,
                output = completion?.output,
                error = completion?.error,
                waitingForResults = true,
            )
            else -> WorkerReply(calls = null, output = "", error = IDLE_FAILURE)
        }
    }
}
