package splice.app.cli.setup

import splice.app.seatd.SeatdAdvice
import splice.app.seatd.SeatdChoice
import splice.app.seatd.SeatdConsent
import splice.core.util.EnvReader
import splice.lifecycle.seatd.SeatdCompanion
import splice.lifecycle.seatd.SeatdInstaller

/** The companion question is separate from confirming the setup plan. Only applying a plan records the answer. */
internal class SetupSeatd(
    private val prompts: SetupPrompts,
    private val env: EnvReader,
    private val installer: SeatdCompanion,
) {
    fun installed(): Boolean = installer.installed() != null

    fun offer(): SeatdChoice? {
        if (!prompts.hasConsole() || installed()) return null
        prompts.frame.step(SeatdAdvice.about)
        prompts.frame.step(SeatdAdvice.WITHOUT)
        val yes = prompts.frame.confirm(SeatdAdvice.SEATD_INSTALL_QUESTION, false)
        return if (yes) SeatdChoice.OPTED_IN else SeatdChoice.DECLINED
    }

    fun summary(choice: SeatdChoice?): List<String> = when (choice) {
        SeatdChoice.OPTED_IN -> listOf("Install the optional seatd-pipe companion")
        SeatdChoice.DECLINED -> listOf("Leave the optional seatd-pipe companion uninstalled")
        null -> emptyList()
    }

    /** What `splice setup --update`'s companion step came to. */
    enum class UpdateOutcome(val offer: SetupOffer, val open: Int = 0) {
        NOTHING(SetupOffer.NOTHING),
        DECLINED(SetupOffer.DECLINED),
        DONE(SetupOffer.DONE),
        FAILED(SetupOffer.DECLINED, open = 1),
    }

    fun update(): UpdateOutcome {
        val choice = offer() ?: return UpdateOutcome.NOTHING
        val applied = apply(choice)
        return when {
            choice != SeatdChoice.OPTED_IN -> UpdateOutcome.DECLINED
            applied -> UpdateOutcome.DONE
            else -> UpdateOutcome.FAILED
        }
    }

    fun apply(choice: SeatdChoice?): Boolean {
        if (choice == null) return true
        val frame = prompts.frame
        SeatdConsent(env).save(choice)?.let(frame::step)
        if (choice != SeatdChoice.OPTED_IN) return true
        val result = installer.install(SeatdAdvice.SETUP)
        SeatdAdvice.lines(result).forEach(frame::step)
        return result !is SeatdInstaller.Result.Failed
    }
}
