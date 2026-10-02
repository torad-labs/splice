// NEW: bundled JavaScript worker yields privileged operations to permission-checked client tools.
package splice.codemode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeResult
import java.io.DataInputStream
import java.io.DataOutputStream

private const val EXECUTION_FAILURE: String = "Code execution failed"

private val CELL_LAUNCHER: Source by lazy {
    val source = "((makeStream) => ($LAUNCHER))(($STREAMING_CODE_MODE_LAUNCHER))"
    Source.newBuilder("js", source, "splice-code-mode").build()
}

/** Bytes kept free under the worker text ceiling for the truncation marker. */
private const val TRUNCATION_RESERVE: Int = 64
private const val MAX_CELL_LOG_BYTES: Int = CodeModeWire.maxTextBytes - TRUNCATION_RESERVE
private const val IDLE_FAILURE: String = "Code execution paused without a tool call"
private const val TOOL_FAILURE: String = "Tool is not allowed"
private const val ARGUMENT_FAILURE: String = "Tool arguments must be a serializable object"
private const val CALL_LIMIT_FAILURE: String = "Code-mode tool call limit exceeded"

/** The isolated child-JVM entry point; its stdout is exclusively length-prefixed JSON protocol. */
internal object CodeModeWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() || args.contentEquals(arrayOf("host"))) { "Unknown code-mode worker mode" }
        DataInputStream(System.`in`.buffered()).use { input ->
            DataOutputStream(System.out.buffered()).use { output ->
                SharedCodeModeWorker.run(input, output)
            }
        }
    }
}

/** One isolated script context on the explicitly shared host engine, resumed through its own tool results. */
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

    // The launcher is compiled and taken once through a whole cell here, a tool call, its settle and
    // the completion, before the worker says ready, because a cold engine's FIRST run is the worker's
    // start, not the script's: 1.4-1.9 s from a built context to a script's first yield, against
    // 21-25 ms for the next session in the same JVM (three fresh JVMs, 2026-09-25). Charged to the
    // script, it spent that much of the advance deadline on an idle box and more on a loaded one (gate
    // run 36180689372). The run is self-contained: its state lives in the launcher's closure and a
    // bridge nothing else holds.
    private val launcher: Value = context.eval(CELL_LAUNCHER).also { launcher ->
        val warmUp = launcher.execute(
            "text(ALL_TOOLS.length); await tools.warm({}); return 1;",
            catalog(WorkerStart("", setOf("warm"))),
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
            catalog(start),
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
        bridge.clearCalls()
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

    /** V4-388: every allowed client name for `tools.call`, and the nested tools under their codex
     *  identifiers for `tools.<Name>` and `ALL_TOOLS` — the rule the exec manual is rendered by. */
    private fun catalog(start: WorkerStart): String = CodeModeJson.codec.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("allowed", buildJsonArray { start.tools.sorted().forEach(::add) })
            put(
                "nested",
                buildJsonArray {
                    CodeModeManual.nestedNames(start.tools).forEach { name ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("global", CodeModeManual.identifier(name))
                                put("description", start.descriptions[name].orEmpty())
                            },
                        )
                    }
                },
            )
        },
    )

    private fun reply(): WorkerReply {
        val bridge = checkNotNull(bridge)
        val calls = bridge.calls()
        pendingCalls = calls
        if (calls.isNotEmpty()) {
            return WorkerReply(calls = calls, output = null, error = null)
        }
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

internal class WorkerBridge(private val allowedTools: Set<String>) {
    private val outboundCalls: MutableList<CodeModeCall> = mutableListOf()
    private val logs: StringBuilder = StringBuilder()
    private var truncatedChars: Int = 0
    private var callCount: Int = 0
    private var completion: WorkerCompletion? = null

    val host: ProxyObject = ProxyObject.fromMap(
        mapOf(
            "call" to ProxyExecutable { values ->
                recordCall(values.single().asString())
                null
            },
            "log" to ProxyExecutable { values ->
                appendLog(values.single().asString())
                null
            },
            "complete" to ProxyExecutable { values ->
                complete(values[0].asString(), values[1].asBoolean())
                null
            },
        ),
    )

    fun calls(): List<CodeModeCall> = outboundCalls.toList()

    fun clearCalls() {
        outboundCalls.clear()
    }

    fun completion(): WorkerCompletion? = completion

    private fun recordCall(raw: String) {
        if (outboundCalls.size >= CodeModeWire.maxCallsPerBatch || callCount >= CodeModeWire.maxCallsPerCell) {
            throw IllegalStateException(CALL_LIMIT_FAILURE)
        }
        val call = parseCall(raw)
        if (call.name !in allowedTools) throw IllegalArgumentException(TOOL_FAILURE)
        callCount += 1
        outboundCalls.add(call)
    }

    /** Output past the text ceiling is cut behind a marker, never fatal: a cell that already ran its
     *  calls must not lose them to a verbose console.log (a four-call cell on 2026-09-20 did). */
    private fun appendLog(value: String) {
        val separator = if (logs.isEmpty()) "" else "\n"
        val room = MAX_CELL_LOG_BYTES - logs.toString().encodeToByteArray().size - separator.length
        val kept = fitBytes(value, room.coerceAtLeast(0))
        truncatedChars += value.length - kept.length
        if (kept.isNotEmpty()) logs.append(separator).append(kept)
    }

    /** A failure carries the evidence logged before it as its output and its reason as its error, apart,
     *  so the bridge frames them as codex does (output, then "Script error:"); the model used to see only
     *  "Code execution failed" and rerun every call directly. */
    private fun complete(value: String, failed: Boolean) {
        completion = if (failed) {
            WorkerCompletion(
                output = fitBytes(logsWithMarker(), CodeModeWire.maxTextBytes),
                error = fitBytes(value.ifBlank { EXECUTION_FAILURE }, CodeModeWire.maxTextBytes),
            )
        } else {
            WorkerCompletion(output = finalOutput(value), error = null)
        }
    }

    private fun finalOutput(value: String): String {
        val output = listOf(logsWithMarker(), value).filter(String::isNotEmpty).joinToString("\n")
        return fitBytes(output, CodeModeWire.maxTextBytes)
    }

    private fun logsWithMarker(): String =
        if (truncatedChars == 0) logs.toString() else "$logs\n[truncated $truncatedChars chars]"

    /** The longest prefix of [value] that fits [bytes] of UTF-8, cut on a code point boundary. */
    private fun fitBytes(value: String, bytes: Int): String {
        if (value.encodeToByteArray().size <= bytes) return value
        var used = 0
        var index = 0
        while (index < value.length) {
            val width = if (value[index].isHighSurrogate() && index + 1 < value.length) 2 else 1
            val size = value.substring(index, index + width).encodeToByteArray().size
            if (used + size > bytes) break
            used += size
            index += width
        }
        return value.substring(0, index)
    }

    private fun parseCall(raw: String): CodeModeCall {
        val frame = requireNotNull(CodeModeJson.codec.parseToJsonElement(raw) as? JsonObject) {
            ARGUMENT_FAILURE
        }
        require(frame.keys == setOf("id", "name", "arguments")) { ARGUMENT_FAILURE }
        val id = requireNotNull(
            (frame["id"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content,
        ) { ARGUMENT_FAILURE }
        val name = requireNotNull(
            (frame["name"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content,
        ) { ARGUMENT_FAILURE }
        val arguments = requireNotNull(frame["arguments"] as? JsonObject) { ARGUMENT_FAILURE }
        CodeModeFrames.requireText(id, "call id")
        return CodeModeCall(id, name, arguments)
    }
}

internal data class WorkerCompletion(val output: String, val error: String?)

private const val LAUNCHER: String = """
(source, catalogJson, host, evalProjection, scopeSeal) => {
  const catalog = JSON.parse(catalogJson);
  const allowed = new Set(catalog.allowed);
  const pending = new Map();
  let sequence = 0;
  const call = (name, args) => new Promise((resolve, reject) => {
    if (typeof name !== "string" || !allowed.has(name)) {
      reject(new Error("Tool is not allowed"));
      return;
    }
    if (args === null || Array.isArray(args) || typeof args !== "object") {
      reject(new Error("Tool arguments must be a serializable object"));
      return;
    }
    try {
      const encoded = JSON.stringify(args);
      if (typeof encoded !== "string") throw new Error("Tool arguments must be a serializable object");
      const id = String(++sequence);
      pending.set(id, {resolve, reject});
      host.call(JSON.stringify({id, name, arguments: JSON.parse(encoded)}));
    } catch (error) {
      reject(error);
    }
  });
  // codex's globals: tools.<Name>(args) per nested tool, ALL_TOOLS, text() and exit(). A call with no
  // argument sends {} as codex does (code-mode-runtime callbacks.rs). tools.call stays for cells
  // written against the splice_exec API before V4-388.
  const bound = {};
  for (const tool of catalog.nested) bound[tool.global] = args => call(tool.name, args === undefined ? {} : args);
  if (!Object.prototype.hasOwnProperty.call(bound, "call")) bound.call = call;
  const tools = Object.freeze(bound);
  const ALL_TOOLS = Object.freeze(catalog.nested.map(tool =>
    Object.freeze({name: tool.global, description: tool.description})));
  // codex's text() (code-mode-runtime value.rs): primitives as String(), anything else as JSON; a
  // value JSON.stringify throws on (a cycle) throws into the script instead of printing
  // "[object Object]".
  const render = value => {
    const kind = typeof value;
    if (value === null || kind === "undefined" || kind === "boolean" || kind === "number" ||
        kind === "bigint" || kind === "string") return String(value);
    const encoded = JSON.stringify(value);
    return typeof encoded === "string" ? encoded : String(value);
  };
  const text = value => { host.log(render(value)); };
  const EXIT = Object.freeze({exit: true});
  const exit = () => { throw EXIT; };
  const console = Object.freeze({
    log(...values) {
      host.log(values.map(value => String(value)).join(" "));
    }
  });
  // A SyntaxError message carries the offending source line; only its first line (position and
  // reason) is reported. A rejected tool call keeps its whole error text.
  const describe = error => {
    if (!error || error.message === undefined) return String(error);
    const message = error instanceof SyntaxError ? String(error.message).split("\n")[0] : String(error.message);
    return String(error.name || "Error") + ": " + message;
  };
  const stream = source === null ? makeStream({tools, console, text, exit, ALL_TOOLS}, host, EXIT, describe, evalProjection, scopeSeal) : null;
  if (source !== null) try {
    const program = new Function(
      "tools", "console", "text", "exit", "ALL_TOOLS",
      "\"use strict\"; return (async () => {\n" + source + "\n})()"
    );
    Promise.resolve(program(tools, console, text, exit, ALL_TOOLS)).then(
      value => host.complete(value === undefined ? "" : String(value), false),
      error => error === EXIT ? host.complete("", false) : host.complete(describe(error), true)
    );
  } catch (error) {
    host.complete(describe(error), true);
  }
  return Object.freeze({
    stream,
    settle(id, output, isError) {
      const callback = pending.get(id);
      if (!callback) throw new Error("Unknown code-mode call id");
      pending.delete(id);
      if (isError) callback.reject(new Error(output)); else callback.resolve(output);
    }
  });
}
"""
