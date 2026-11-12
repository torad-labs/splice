package splice.provider.codex.stream.batch

import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeWire
import splice.upstream.RoundBody

/** Later items need parsed placement data, not the original multi-megabyte request text. */
internal class CodeModeAdmissionBodies(private val wire: CodexCodeModeWire) {
    fun compact(context: CodeModeRunContext, body: CodeModeBody): CodeModeBody {
        context.link.client = context.client?.let(::tree)
        context.recovery?.compact(wire)
        return tree(body)
    }
    private fun tree(
        body: CodeModeBody,
    ): CodeModeBody = body.request?.first?.let { wire.body(RoundBody.Tree(it)) } ?: body
}
