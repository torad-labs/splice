package splice.head

import io.ktor.server.application.ApplicationCall
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.CLIENT_WINDOW_HEADER
import splice.core.model.ClientWindow
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelTierSuffix
import splice.core.model.UsageSizing
import splice.core.model.WindowUsage

/** A request speaks for its divisor; a launch record or a session's older post can only suggest it. */
internal class ClientWindowWitness(private val posted: ClientWindows? = null) {
    fun sizing(
        call: ApplicationCall,
        catalog: ModelCatalog,
        model: String,
        sessionId: String?,
        turn: GatewayTurn? = GatewayTurns.of(call),
    ): UsageSizing {
        val learned = posted?.windowFor(sessionId)
        val hint = learned ?: turn?.clientWindow?.takeIf { it > 0 }
        val request = turn?.requestWindow
        val asked = turn?.asked ?: model
        val stated = (request as? ClientWindow.Stated)?.tokens?.takeIf { it > 0 }
            ?: oneMillion(call, asked) ?: launched(call, catalog, model)
        val evidence = stated?.let { ClientWindow.Stated(it) } ?: ClientWindow.Hint(hint)
        // The gateway can know the client without knowing how it interpreted this selector.
        return WindowUsage(catalog).of(model, evidence, asked.takeUnless { request is ClientWindow.Hint }, learned)
    }

    // Claude Code strips the 1M suffix before writing the body and can send the opt-in beta instead.
    private fun oneMillion(call: ApplicationCall, asked: String): Long? {
        if (ModelTierSuffix.isOneMillion(asked)) return CLAUDE_CODE_ONE_MILLION
        val betas = call.request.headers.getAll(BETA_HEADER).orEmpty()
        return CLAUDE_CODE_ONE_MILLION.takeIf {
            betas.any { line -> line.split(',').any { it.trim().startsWith(ONE_MILLION_BETA) } }
        }
    }

    // The provider row distinguishes a real Claude ID from the door's Claude-prefixed wrapper.
    private fun launched(call: ApplicationCall, catalog: ModelCatalog, model: String): Long? =
        call.request.headers[CLIENT_WINDOW_HEADER]?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L && catalog.envGoverned(model) }
}

private const val BETA_HEADER = "anthropic-beta"
private const val ONE_MILLION_BETA = "context-1m-"
