package splice.app.seatd

import splice.core.install.SEATD_PIPE_EXPLANATION
import splice.core.install.seatdPipeRepository
import splice.lifecycle.seatd.SeatdInstaller.Result

internal object SeatdAdvice {
    const val SETUP = "splice setup --update"
    const val DESKTOP = "splice desktop on"
    const val SEATD_INSTALL_QUESTION = "Install seatd-pipe?"
    const val WITHOUT = "If you decline, Claude Code Desktop and Gemini aren't supported through Splice. " +
        "Terminal claude and other providers still work."

    val about: String get() = "$SEATD_PIPE_EXPLANATION $seatdPipeRepository"
    val how: String get() = "Run `$SETUP` or `$DESKTOP` in a terminal to install it."

    fun lines(result: Result): List<String> = when (result) {
        is Result.AlreadyInstalled -> emptyList()
        is Result.Installed -> listOfNotNull(
            "Installed seatd-pipe ${result.location.version}.",
            result.cleanupNotice,
        )
        is Result.Failed -> listOf(result.message)
    }
}
