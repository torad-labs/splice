// NEW: the Context Getter (docs/specs/harness.md, decision 9). The built-in worker: a normal Claude Code session in print
// mode, in the session's folder, on the head's fast model, started as splice starts any session on that head (its
// login, the MCP hub's tools, the person's settings) and thinking at its own model's level. Its requests name the
// session they work for, and its stream's final result is what it found. A session on the door is gathered for on the
// door, naming a selector of the head that holds its waiting turn, so the door sends the gatherer there.
package splice.harness

import splice.client.ClaudeTiers
import splice.core.auth.LoginAnswer
import splice.core.capability.CapabilityWorker
import splice.core.failure.LoginOwner
import splice.core.turn.GATHERS_FOR_HEADER
import splice.launch.LaunchHead
import splice.launch.LaunchOutcome
import splice.launch.LaunchRecipe
import java.nio.file.Path

internal class ContextGetter(private val heads: GathererHeads, private val run: GathererRun) {
    /** Why the gatherer is not called for [on], or null when it can run: the session's folder cannot be found (a
     *  session relayed from another machine has none here), or the head has no login that can serve its fast model. */
    suspend fun notCalled(on: CallFor): String? {
        on.turn.folder(on.request.sessionId) ?: return NO_FOLDER
        val head = heads.of(on.head) ?: return NO_LAUNCH
        return NO_LOGIN.takeUnless { signedIn(head.launch) }
    }

    /** What the held turn says as a gather for [on] starts: that it explores with [worker]'s model or the head's fast
     *  one, by the label the head's models give it. */
    fun message(worker: CapabilityWorker.Builtin, on: CallFor): String {
        val head = heads.of(on.head)
        val model = head?.let { fast(worker, it) } ?: worker.model
        val label = if (head != null && model != null) label(head, model) else null
        return "$EXPLORING ${label ?: model ?: FAST_ALIAS}"
    }

    /** The label [head]'s models give [model], as its catalog has it now, else as its launch has it. */
    private fun label(head: GathererHead, model: String): String? =
        head.launch.catalog?.live()?.models?.firstOrNull { it.id == model }?.label?.takeIf(String::isNotBlank)
            ?: head.launch.spec?.models?.modelLabels?.get(model)

    /** [worker]'s model, else the head's fast one, as the head's rows name it; null for a head that names none. */
    private fun fast(worker: CapabilityWorker.Builtin, head: GathererHead): String? =
        worker.model ?: head.launch.spec?.models?.tiers?.byTier?.get(SMALL_TIER)

    /** One gather for [on], on [worker]'s model or the head's fast one, its steps told to [progress], under a deadline
     *  of its own past [limitS]. */
    suspend fun call(worker: CapabilityWorker.Builtin, on: CallFor, limitS: Int, progress: WorkerProgress): WorkerEnd =
        when (val start = start(worker, on)) {
            is Start.Ready -> run.run(start.command, limitS, progress)
            is Start.Not -> WorkerEnd.Failed(start.why)
        }

    /** What the gatherer for [on] is started as, or why it cannot be. */
    private suspend fun start(worker: CapabilityWorker.Builtin, on: CallFor): Start {
        val session = on.request.sessionId
        val folder = on.turn.folder(session)
        val head = heads.of(on.head)
        if (session == null || folder == null) return Start.Not(NO_FOLDER)
        if (head == null) return Start.Not(NO_LAUNCH)
        val door = onDoor(worker, on, head, folder)
        return when (val outcome = door?.outcome ?: head.headless.of(head.launch, folder)) {
            is LaunchOutcome.Refused -> Start.Not("its launch was refused: ${outcome.reason}")
            is LaunchOutcome.Ready -> {
                val model = door?.model ?: model(worker, head, outcome.recipe)
                Start.Ready(command(outcome.recipe, model, folder, session, on.request.firstUserText()))
            }
        }
    }

    /** For a session on the door, `claude`'s launch on the door in [folder] and the selector of this head it names.
     *  Null for any other session, and where the door gives no launch (`claude` is not on it) or the head names no
     *  such model: the head's own launch reaches the same head. */
    private suspend fun onDoor(
        worker: CapabilityWorker.Builtin,
        on: CallFor,
        head: GathererHead,
        folder: Path,
    ): OnDoor? {
        if (on.request.shape.door == null) return null
        val selector = selector(worker, head) ?: return null
        return head.headless.onDoor(folder)?.let { OnDoor(named(it, selector), selector) }
    }

    /** [outcome] with Claude Code's opus tier named as [selector], so the gatherer's every request names it and the
     *  door sends none of them to another head. */
    private fun named(outcome: LaunchOutcome, selector: String): LaunchOutcome = when (outcome) {
        is LaunchOutcome.Ready -> {
            val recipe = outcome.recipe
            LaunchOutcome.Ready(recipe.copy(env = recipe.env + (OPUS_ENV to selector)))
        }
        is LaunchOutcome.Refused -> outcome
    }

    /** The selector the door runs [worker]'s model or the head's fast one by on this head: the one its catalog lists
     *  for that model, else the catalog's spelling of it. Null for a head with no catalog or no fast model. */
    private fun selector(worker: CapabilityWorker.Builtin, head: GathererHead): String? {
        val catalog = head.launch.catalog ?: return null
        val fast = fast(worker, head) ?: return null
        return catalog.discoveryRows().firstOrNull { catalog.unwrap(it.id) == fast }?.id ?: catalog.wrap(fast)
    }

    /** [worker]'s model as the head's client is handed it, else the model the head's launch names for its fast
     *  tier, else Claude Code's own name for that tier. */
    private fun model(worker: CapabilityWorker.Builtin, head: GathererHead, recipe: LaunchRecipe): String =
        worker.model?.let { head.launch.spec?.models?.tiers?.clientId(it) ?: it }
            ?: recipe.env[ClaudeTiers.model(FAST_TIER)]
            ?: FAST_ALIAS

    /** The head's own launch, in print mode on [model], streaming its steps and naming [session] in its header. Every
     *  word of the head's or the launcher's about how hard to think is left out, so it thinks at the level the
     *  person's settings name for [model], else Claude Code's own. */
    private fun command(recipe: LaunchRecipe, model: String, folder: Path, session: String, typed: String) =
        GathererCommand(
            argv = recipe.argv + listOf("-p", "--model", model, "--output-format", "stream-json", "--verbose"),
            env = recipe.env - THINKING + mapOf(
                SUMMARIES_ENV to "1",
                HEADERS_ENV to listOfNotNull(recipe.env[HEADERS_ENV], "$GATHERS_FOR_HEADER: $session")
                    .joinToString("\n"),
            ),
            unset = recipe.unset + THINKING,
            folder = folder,
            input = GatheringPrompt.of(typed),
        )

    /** Whether [head] has a login to send: the client's own on a head that forwards it, else one splice holds now. */
    private suspend fun signedIn(head: LaunchHead): Boolean {
        if (!head.keyPresence.keyPresentNow()) return false
        if (head.auth.owner == LoginOwner.CLIENT) return true
        return head.auth.answer() is LoginAnswer.Granted
    }
}

/** A launch on the door, and the selector it names. */
private data class OnDoor(val outcome: LaunchOutcome, val model: String)

/** How a gatherer starts: ready, as [command], or not at all, for [why]. */
private sealed class Start {
    data class Ready(val command: GathererCommand) : Start()

    data class Not(val why: String) : Start()
}

/** why: the tier Claude Code names its small, fast model by, which a head's launch points at its own fast model. */
private const val FAST_TIER = "HAIKU"
private const val FAST_ALIAS = "haiku"

/** why: the tier a head's tiers name its small, fast model under, as the door reads them. */
private const val SMALL_TIER = "haiku"

/** why: Claude Code's switch for a `tool_use_summary` event per step of a print-mode stream (2.1.326). */
private const val SUMMARIES_ENV = "CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES"

/** why: the model Claude Code names its opus tier by. With none, Claude Code 2.1.326 on the door (Mantle) first probes
 *  which Opus there is with one-token requests (`anthropic.claude-opus-5-5`, then older ones), whatever `--model` and
 *  `ANTHROPIC_MODEL` say; the door runs those as the opus tier of a session that has named no selector yet, on its
 *  default head. With one named, there is no probe (November 17, 2026). */
private const val OPUS_ENV = "ANTHROPIC_DEFAULT_OPUS_MODEL"

/** why: the headers Claude Code adds to every request it makes, one per line. */
private const val HEADERS_ENV = "ANTHROPIC_CUSTOM_HEADERS"

/** why: Claude Code's effort word and its thinking budget. A launch plants the budget and a head's effort may plant the
 *  word; the daemon's own environment may carry either from whoever started it. */
private val THINKING = setOf("CLAUDE_CODE_EFFORT_LEVEL", "MAX_THINKING_TOKENS")

/** What the held turn says as a gather starts, before the model's label (decision 9). */
private const val EXPLORING = "Exploring files with"

/** What the held turn says once a gather stopped before its result, until the turn's answer begins (decision 9). */
internal const val GATHER_STOPPED = "Exploring stopped, continuing without it"

private const val NO_FOLDER = "the session's folder cannot be found"
private const val NO_LAUNCH = "splice cannot start a session on this head"
private const val NO_LOGIN = "the head has no login that can serve its fast model"
