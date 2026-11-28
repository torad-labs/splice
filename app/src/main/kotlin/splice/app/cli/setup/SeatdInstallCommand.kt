package splice.app.cli.setup

import splice.app.seatd.SeatdAdvice
import splice.app.seatd.SeatdChoice
import splice.core.util.EnvReader
import splice.lifecycle.seatd.SeatdCompanion
import splice.lifecycle.seatd.SeatdInstaller

/** install.sh uses the same offer and record as setup. The shell can explicitly request advice only. */
internal class SeatdInstallCommand(
    env: EnvReader = EnvReader(System::getenv),
    private val prompts: SetupPrompts = SetupPrompts(),
    installer: SeatdCompanion = SeatdInstaller(env),
) {
    private val seatd = SetupSeatd(prompts, env, installer)

    fun run(showOnly: Boolean): Int {
        if (seatd.installed()) return 0
        val choice = if (showOnly || !prompts.hasConsole()) {
            prompts.frame.step(SeatdAdvice.about)
            null
        } else {
            seatd.offer()
        }
        val installed = seatd.apply(choice)
        if (choice != SeatdChoice.OPTED_IN) {
            prompts.frame.step("To add seatd-pipe later, run: ${SeatdAdvice.SETUP}")
        }
        return if (installed) 0 else 1
    }
}
