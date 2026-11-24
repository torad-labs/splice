// NEW: optional `splice setup` / `splice setup --update` step for installing or migrating Codex peer hooks in
// `~/.codex/hooks.json` (docs/peers.md). Completely silent when Codex is absent or already up to date, and never
// blocks setup on failure.
package splice.app.cli.setup

import splice.app.capability.CodexHooksOutcome
import splice.app.capability.CodexHooksReading
import splice.app.capability.CodexHooksState
import splice.app.capability.CodexPeerHooks
import splice.core.util.Cancellables

/** What the operator chose for `~/.codex/hooks.json` during setup. */
internal data class CodexHooksChoice(
    val apply: Boolean = false,
    val reading: CodexHooksReading? = null,
)

/** Interactive offer and best-effort installer/migrator for `~/.codex/hooks.json`. */
internal class SetupCodexHooks(private val prompts: SetupPrompts) {
    private val hooks = CodexPeerHooks()

    /** Offers install (opt-in, default no) or migration (default yes) when Codex is installed on a TTY. */
    fun offer(facts: SetupFacts): CodexHooksChoice {
        val reading = facts.capabilities?.harness?.takeIf { prompts.hasConsole() && it.codex != null }?.codexHooks
        val question = reading?.let(::questionFor)
        return if (reading != null && question != null) {
            val defaultYes = reading.state == CodexHooksState.NEEDS_MIGRATION
            CodexHooksChoice(prompts.frame.confirm(question, defaultYes), reading)
        } else {
            CodexHooksChoice()
        }
    }

    fun summary(choice: CodexHooksChoice): List<String> {
        val reading = choice.reading?.takeIf { choice.apply } ?: return emptyList()
        val verb = if (reading.state == CodexHooksState.NEEDS_MIGRATION) "migrate" else "install"
        return listOf("Codex peer hooks: $verb ${reading.file}")
    }

    /** Applies [choice] best-effort; never throws or blocks setup. */
    fun apply(choice: CodexHooksChoice): Boolean {
        val reading = choice.reading?.takeIf { choice.apply } ?: return false
        return applyFile(reading)
    }

    /** For `splice setup --update`: null when nothing to offer, true when applied, false when declined or failed. */
    fun offerUpdate(reading: CodexHooksReading?): Boolean? {
        val question = reading?.let(::questionFor) ?: return null
        val defaultYes = reading.state == CodexHooksState.NEEDS_MIGRATION
        return prompts.frame.confirm(question, defaultYes) && applyFile(reading)
    }

    private fun questionFor(reading: CodexHooksReading): String? = when (reading.state) {
        CodexHooksState.MISSING ->
            "Install Codex peer hooks in ${reading.file} (lets Claude sessions message Codex threads)?"
        CodexHooksState.NEEDS_MIGRATION ->
            "Migrate Codex peer hooks in ${reading.file} (${reading.issues.joinToString("; ")})?"
        CodexHooksState.CURRENT, CodexHooksState.UNREADABLE -> null
    }

    private fun applyFile(reading: CodexHooksReading): Boolean {
        val outcome = Cancellables.runCatchingBestEffort { hooks.installOrMigrate(reading.file) }
            .getOrElse { CodexHooksOutcome.Refused(reading.file, it::class.simpleName ?: "error") }
        return when (outcome) {
            is CodexHooksOutcome.Written -> {
                val verb = if (outcome.migrated) "Migrated" else "Installed"
                prompts.frame.step("$verb Codex peer hooks in ${outcome.file}")
                true
            }
            is CodexHooksOutcome.Current -> {
                prompts.frame.step("Codex peer hooks in ${outcome.file} are already up to date")
                true
            }
            is CodexHooksOutcome.Refused -> {
                prompts.frame.step("Skipped Codex peer hooks in ${outcome.file} (${outcome.reason})")
                false
            }
        }
    }
}
