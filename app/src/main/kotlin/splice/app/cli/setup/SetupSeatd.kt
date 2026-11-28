package splice.app.cli.setup

import splice.app.seatd.SeatdAdvice
import splice.app.seatd.SeatdChoice
import splice.app.seatd.SeatdConsent
import splice.core.util.EnvReader
import splice.lifecycle.seatd.SeatdCompanion

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
        return if (prompts.frame.confirm(SeatdAdvice.QUESTION, false)) SeatdChoice.OPTED_IN else SeatdChoice.DECLINED
    }

    fun summary(choice: SeatdChoice?): List<String> = when (choice) {
        SeatdChoice.OPTED_IN -> listOf("Install the optional seatd-pipe companion")
        SeatdChoice.DECLINED -> listOf("Leave the optional seatd-pipe companion uninstalled")
        null -> emptyList()
    }

    fun update(): SetupOffer {
        val choice = offer() ?: return SetupOffer.NOTHING
        apply(choice)
        return if (choice == SeatdChoice.OPTED_IN && installed()) SetupOffer.DONE else SetupOffer.DECLINED
    }

    fun apply(choice: SeatdChoice?) {
        if (choice == null) return
        val frame = prompts.frame
        SeatdConsent(env).save(choice)?.let(frame::step)
        if (choice == SeatdChoice.OPTED_IN) {
            SeatdAdvice.lines(installer.install(SeatdAdvice.SETUP)).forEach(frame::step)
        }
    }
}
