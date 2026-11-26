package splice.head

import io.ktor.server.application.ApplicationCall
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.CLIENT_WINDOW_HEADER
import splice.core.model.ClaudeCodeNativeWindow
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
        val request = turn?.requestWindow
        val lane = turn?.clientWindow ?: headLaunch(catalog, model, turn)
        val session = maxOf(learned ?: 0, lane)
        val asked = turn?.asked ?: model
        val bound = (request as? ClientWindow.Bound)?.tokens ?: ClaudeCodeNativeWindow.of(asked)
        val launch = launched(call, catalog, model)
        val stated = (request as? ClientWindow.Stated)?.tokens?.takeIf { it > 0 }
            ?: oneMillion(call, asked)
        val evidence = stated?.let { ClientWindow.Stated(it) } ?: inferred(session, bound, launch)
        return WindowUsage(catalog).of(model, evidence, learned)
    }

    private fun inferred(session: Long, bound: Long?, launch: Long?): ClientWindow = when {
        bound != null -> ClientWindow.Bound(maxOf(bound, session, launch ?: 0))
        launch != null -> ClientWindow.Stated(launch)
        else -> ClientWindow.Hint(session.takeIf { it > 0 })
    }

    private fun headLaunch(catalog: ModelCatalog, model: String, turn: GatewayTurn?): Long =
        if (turn == null && catalog.envGoverned(model)) catalog.clientLaunchWindow else 0

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
