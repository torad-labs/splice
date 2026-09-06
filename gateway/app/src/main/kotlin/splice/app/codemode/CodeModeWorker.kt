// NEW: bundled JavaScript worker yields privileged operations to permission-checked client tools.
package splice.app.codemode

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject
import splice.spi.CodeModeCall
import splice.spi.CodeModeInfrastructureCategory
import splice.spi.CodeModeInfrastructureClass
import splice.spi.CodeModeResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

private const val EXECUTION_FAILURE: String = "Code execution failed"
private const val IDLE_FAILURE: String = "Code execution paused without a tool call"
private const val TOOL_FAILURE: String = "Tool is not allowed"
private const val ARGUMENT_FAILURE: String = "Tool arguments must be a serializable object"
private const val CALL_LIMIT_FAILURE: String = "Code-mode tool call limit exceeded"

/** The isolated child-JVM entry point; its stdout is exclusively length-prefixed JSON protocol. */
public object CodeModeWorker {
    @JvmStatic
    public fun main(args: Array<String>) {
        DataInputStream(System.`in`.buffered()).use { input ->
            DataOutputStream(System.out.buffered()).use { output ->
                runWorker(input, output)
            }
        }
    }

    private fun runWorker(input: DataInputStream, output: DataOutputStream) {
        try {
            runSession(input, output)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            CodeModeWire.write(
                output,
                CodeModeFatalFrame.create(CodeModeInfrastructureCategory.PROTOCOL, CodeModeInfrastructureClass.IO),
            )
        } catch (_: RuntimeException) {
            CodeModeWire.write(
                output,
                CodeModeFatalFrame.create(CodeModeInfrastructureCategory.HOST, CodeModeInfrastructureClass.RUNTIME),
            )
        }
    }

    private fun runSession(input: DataInputStream, output: DataOutputStream) {
        val start = CodeModeFrames.parseStart(CodeModeWire.read(input))
        WorkerSession(start).use { session ->
            var reply = session.start()
            while (true) {
                CodeModeWire.write(output, toFrame(reply))
                if (reply.calls == null) return
                reply = session.advance(CodeModeFrames.parseResults(CodeModeWire.read(input)))
            }
        }
    }

    private fun toFrame(reply: WorkerReply): JsonObject = reply.calls?.let(CodeModeWire::callsFrame)
        ?: CodeModeWire.completedFrame(checkNotNull(reply.output), reply.error)
}

internal class WorkerSession(private val start: WorkerStart) : AutoCloseable {
    private val bridge: WorkerBridge = WorkerBridge(start.tools)
    private val context: Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowPolyglotAccess(PolyglotAccess.NONE)
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowNativeAccess(false)
        .option("engine.WarnInterpreterOnly", "false")
        .build()
    private val toolsJson: String = CodeModeJson.codec.encodeToString(
        JsonArray.serializer(),
        buildJsonArray { start.tools.sorted().forEach(::add) },
    )
    private var settle: Value? = null
    private var pendingCalls: List<CodeModeCall> = emptyList()

    fun start(): WorkerReply {
        val launcher = context.eval("js", LAUNCHER)
        val control = launcher.execute(start.source, toolsJson, bridge.host)
        settle = control.getMember("settle")
        return reply()
    }

    fun advance(results: List<CodeModeResult>): WorkerReply {
        CodeModeFrames.validateResultSet(pendingCalls, results)
        bridge.clearCalls()
        results.forEach { result ->
            checkNotNull(settle).execute(result.id, result.output, result.isError)
        }
        return reply()
    }

    override fun close() {
        context.close(true)
    }

    private fun reply(): WorkerReply {
        val calls = bridge.calls()
        if (calls.isNotEmpty()) {
            pendingCalls = calls
            return WorkerReply(calls = calls, output = null, error = null)
        }
        bridge.completion()?.let { completion ->
            return WorkerReply(calls = null, output = completion.output, error = completion.error)
        }
        return WorkerReply(calls = null, output = "", error = IDLE_FAILURE)
    }
}

internal class WorkerBridge(private val allowedTools: Set<String>) {
    private val outboundCalls: MutableList<CodeModeCall> = mutableListOf()
    private val logs: StringBuilder = StringBuilder()
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

    private fun appendLog(value: String) {
        val separator = if (logs.isEmpty()) "" else "\n"
        CodeModeFrames.requireText(logs.toString() + separator + value, "output")
        logs.append(separator).append(value)
    }

    private fun complete(value: String, failed: Boolean) {
        completion = WorkerCompletion(
            output = if (failed) "" else finalOutput(value),
            error = if (failed) EXECUTION_FAILURE else null,
        )
    }

    private fun finalOutput(value: String): String {
        val output = if (logs.isEmpty()) {
            value
        } else {
            listOf(logs.toString(), value).filter(String::isNotEmpty).joinToString("\n")
        }
        CodeModeFrames.requireText(output, "output")
        return output
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
(source, toolsJson, host) => {
  const allowed = new Set(JSON.parse(toolsJson));
  const pending = new Map();
  let sequence = 0;
  const tools = Object.freeze({
    call(name, args) {
      return new Promise((resolve, reject) => {
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
    }
  });
  const console = Object.freeze({
    log(...values) {
      host.log(values.map(value => String(value)).join(" "));
    }
  });
  try {
    const program = new Function("tools", "console", "\"use strict\"; return (async () => {\n" + source + "\n})()");
    Promise.resolve(program(tools, console)).then(
      value => host.complete(value === undefined ? "" : String(value), false),
      () => host.complete("", true)
    );
  } catch (error) {
    host.complete("", true);
  }
  return Object.freeze({
    settle(id, output, isError) {
      const callback = pending.get(id);
      if (!callback) throw new Error("Unknown code-mode call id");
      pending.delete(id);
      if (isError) callback.reject(new Error(output)); else callback.resolve(output);
    }
  });
}
"""
