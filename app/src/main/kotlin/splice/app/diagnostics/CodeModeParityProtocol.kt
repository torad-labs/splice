package splice.app.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeWorkerLostException

/** Dispatch is independent of observations so callback replies and termination can release a live cell. */
internal class CodeModeParityProtocol(
    private val runtime: NativeCodeModeLifecycle,
    private val frames: CodeModeParityFrames,
    private val scope: CoroutineScope,
) {
    private val delegates = CodeModeParityDelegates(frames)
    private val cells = CodeModeParityCells(runtime, frames, delegates)

    suspend fun run() {
        try {
            while (true) {
                val frame = withContext(ProcessDispatchers().io()) { frames.read() } ?: break
                if (JsonScalars.str(frame, "type") == "delegate/response") {
                    delegates.reply(frame)
                } else {
                    scope.launch { dispatch(frame) }
                }
            }
        } finally {
            runtime.close()
        }
    }

    private suspend fun dispatch(frame: JsonObject) {
        when (JsonScalars.str(frame, "type")) {
            "connection/hello" -> frames.send(CodeModeParityMessages.hello())
            "operation/request" -> {
                val id = frame.getValue("id")
                try {
                    operation(id, frame.getValue("request").jsonObject)
                } catch (error: CodeModeWorkerLostException) {
                    failed(id, error.message.orEmpty(), "splice.upstream.failure.CodeModeWorkerLostException")
                } catch (error: CodeModeInfrastructureException) {
                    failed(id, error.message.orEmpty(), "splice.upstream.failure.CodeModeInfrastructureException")
                } catch (error: IllegalArgumentException) {
                    failed(id, error.message.orEmpty())
                } catch (error: IllegalStateException) {
                    failed(id, error.message.orEmpty())
                }
            }
        }
    }

    private fun failed(id: JsonElement, message: String, errorType: String? = null) {
        frames.send(CodeModeParityMessages.response(id, CodeModeParityPayload.failure(message, errorType)))
    }

    private suspend fun operation(id: JsonElement, request: JsonObject) {
        val session = JsonScalars.str(request, "sessionId").orEmpty()
        when (JsonScalars.str(request, "method")) {
            "session/open" -> {
                runtime.openNativeSession(session)
                frames.send(
                    CodeModeParityMessages.response(id, CodeModeParityMessages.session(session, "session/ready")),
                )
            }
            "session/execute" -> cells.execute(id, session, request.getValue("request").jsonObject)
            "session/wait" -> cells.wait(id, session, request.getValue("request").jsonObject)
            "session/terminate" -> cells.terminate(id, session, JsonScalars.str(request, "cellId").orEmpty())
            "session/shutdown" -> {
                cells.shutdown(session)
                frames.send(
                    CodeModeParityMessages.response(id, CodeModeParityMessages.session(session, "session/closed")),
                )
            }
            else -> error("Unsupported native diagnostic method")
        }
    }
}
