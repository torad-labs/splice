// NEW: a bounded captured source offers a fresh cursor only for proven pre-dispatch retries.
package splice.provider.codex.stream

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeSealedSource
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

private data class SourceSnapshot(
    val text: String,
    val terminal: CodeModeSourcePart? = null,
    val itemComplete: Boolean = false,
)

/** Joins an accumulated prefix to the conversation batch before a cursor hands it to the worker. */
internal fun interface CodeModeSourceCommit {
    fun commit(text: String)
}

/** Worker startup whose executable source must remain valid until a cell is returned. */
internal fun interface CodeModeSourceStart {
    suspend operator fun invoke(): CodeModeCell
}

internal class CodeModeSourceBuffer(
    private val beforeRead: CodeModeSourceCommit = CodeModeSourceCommit {},
) {
    private val state = MutableStateFlow(SourceSnapshot(""))
    val text: String get() = state.value.text

    @Volatile var startupRejected: Boolean = false
        private set

    fun publish(text: String) {
        state.update { previous ->
            check(previous.terminal == null) { "source already ended" }
            check(!previous.itemComplete) { "source item already completed" }
            check(text.startsWith(previous.text)) { "dispatched source changed" }
            SourceSnapshot(text)
        }
    }

    /** Item completion freezes unread statements until the response terminal certifies their source. */
    fun seal() {
        state.update { previous ->
            check(previous.terminal == null) { "source already ended" }
            previous.copy(itemComplete = true)
        }
    }

    /** A statement selected just before item completion still cannot escape as a client call afterward. */
    suspend fun awaitCertification() {
        val next = state.first { !it.itemComplete || it.terminal != null }
        (next.terminal as? CodeModeSourcePart.Failed)?.let { throw IOException(it.error) }
    }

    fun complete(text: String) {
        state.update { previous ->
            check(previous.terminal == null) { "source already ended" }
            check(text.startsWith(previous.text)) { "dispatched source changed" }
            SourceSnapshot(text, CodeModeSourcePart.Complete())
        }
    }

    fun fail(error: String) {
        state.update { previous ->
            if (previous.terminal == null) previous.copy(terminal = CodeModeSourcePart.Failed(error)) else previous
        }
    }

    /** Failed source cancels a pending boot. Certified source imposes no execution deadline. */
    suspend fun whileStarting(work: CodeModeSourceStart): CodeModeCell = coroutineScope {
        val produced = AtomicReference<CodeModeCell?>()
        val rejected = async { state.mapNotNull { it.terminal as? CodeModeSourcePart.Failed }.first() }
        val running = async { work().also(produced::set) }
        var adopted = false
        try {
            val cell = select {
                rejected.onAwait { rejectStartup(it) }
                running.onAwait { it }
            }
            (state.value.terminal as? CodeModeSourcePart.Failed)?.let(::rejectStartup)
            adopted = true
            cell
        } finally {
            rejected.cancel()
            withContext(NonCancellable) {
                running.cancelAndJoin()
                if (!adopted) produced.getAndSet(null)?.close()
            }
        }
    }

    private fun rejectStartup(failure: CodeModeSourcePart.Failed): Nothing {
        startupRejected = true
        throw IOException(failure.error)
    }

    fun view(): CodeModeSource {
        var position = 0
        return object : CodeModeSealedSource {
            override val sealedGlobals: Set<String> = CodeModeManual.streamingSealedGlobals

            override suspend fun read(): CodeModeSourcePart {
                while (true) {
                    val next = state.first { it.terminal != null || (!it.itemComplete && it.text.length > position) }
                    if (next.terminal is CodeModeSourcePart.Failed) return next.terminal
                    beforeRead.commit(next.text)
                    val latest = state.value
                    val changed = latest !== next
                    val sealed = latest.itemComplete || latest.terminal != null
                    if (changed && sealed) continue
                    val remainder = next.text.substring(position)
                    position = next.text.length
                    return if (next.terminal is CodeModeSourcePart.Complete) {
                        CodeModeSourcePart.Complete(remainder)
                    } else {
                        CodeModeSourcePart.Delta(remainder)
                    }
                }
            }
        }
    }
}
