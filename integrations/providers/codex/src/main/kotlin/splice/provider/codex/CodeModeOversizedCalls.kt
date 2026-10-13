// NEW: a code-mode tool call whose arguments exceed the size limit is the script's error, not the turn's.
package splice.provider.codex

import splice.core.util.JsonWire
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeResult
import java.util.concurrent.ConcurrentHashMap

/** A batch with a call whose arguments exceed the size limit goes back to the script as error results, unsent, and
 *  the model reads why in its own output. Poisoned, it ended the turn as invalid_request, which Claude Code does not
 *  retry (8 claudex turns in the week to Oct 12). The whole batch is refused, so none of it runs. */
internal class CodeModeOversizedCalls(
    private val config: CodeModeBridgeConfig,
    private val validation: CodexCodeModeValidation,
) {
    /** Scripts already handed one oversized batch back. A second ends the script, so a script that retries the same
     *  call in a loop cannot spin. In memory only: one record id per refusal, which is rare. */
    private val refusedOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Null when every call fits; the batch's error results the first time one does not; empty the second time,
     *  which the caller ends the script on. */
    fun refused(record: CodeModeRecord, calls: List<CodeModeCall>): List<CodeModeResult>? {
        if (calls.all(::fits)) return null
        if (!refusedOnce.add(record.id)) return emptyList()
        config.log("[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)}: $OVERSIZED_LOG")
        val limit = OVERSIZED_ERROR.format(config.bounds.maxOutputChars)
        return calls.map { call -> CodeModeResult(call.id, if (fits(call)) BATCH_REFUSED else limit, isError = true) }
    }

    private fun fits(call: CodeModeCall): Boolean = validation.fitsOutput(JsonWire.string(call.arguments))
}

// The error a script's call reads when its arguments exceed the size limit; nothing reached the client.
private const val OVERSIZED_ERROR: String =
    "code mode did not send this call: its arguments exceed the %d-character size limit, and nothing ran. " +
        "Split the content into smaller calls"

// The error the other calls of that batch read: the batch is refused whole, so none of it ran.
private const val BATCH_REFUSED: String =
    "code mode did not send this call: another call in its batch exceeds the size limit, and nothing ran"

private const val OVERSIZED_LOG: String =
    "tool call arguments exceed the size limit; the batch went back to the script as errors, unsent"
