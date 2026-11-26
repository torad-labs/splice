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

/** A request speaks for its own divisor; neither a gateway's launch record nor a session's post can do that. */
internal class ClientWindowWitness(private val posted: ClientWindows? = null) {
    fun sizing(
        call: ApplicationCall,
        catalog: ModelCatalog,
        model: String,
        sessionId: String?,
        turn: GatewayTurn? = GatewayTurns.of(call),
    ): UsageSizing = WindowUsage(catalog).of(model, of(call, catalog, turn?.asked ?: model, sessionId, turn))

    private fun of(
        call: ApplicationCall,
        catalog: ModelCatalog,
        asked: String,
        sessionId: String?,
        turn: GatewayTurn?,
    ): ClientWindow {
        val stated = turn?.requestWindow?.takeIf { it > 0 }
            ?: oneMillion(call, asked)
            ?: launched(call, catalog, asked)
        return if (stated != null) {
            ClientWindow.Stated(stated)
        } else {
            ClientWindow.Hint(turn?.clientWindow?.takeIf { it > 0 } ?: posted?.windowFor(sessionId))
        }
    }

    // Claude Code can strip [1m] before writing the body and carry it in the beta instead.
    private fun oneMillion(call: ApplicationCall, asked: String): Long? {
        if (ModelTierSuffix.isOneMillion(asked)) return CLAUDE_CODE_ONE_MILLION
        val betas = call.request.headers.getAll(BETA_HEADER).orEmpty()
        return CLAUDE_CODE_ONE_MILLION.takeIf {
            betas.any { line -> line.split(',').any { it.trim().startsWith(ONE_MILLION_BETA) } }
        }
    }

    // The launch only describes the divisor of a row the client sizes from its environment.
    private fun launched(call: ApplicationCall, catalog: ModelCatalog, model: String): Long? =
        call.request.headers[CLIENT_WINDOW_HEADER]?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L && catalog.envGoverned(model) }
}

private const val BETA_HEADER = "anthropic-beta"
private const val ONE_MILLION_BETA = "context-1m-"
