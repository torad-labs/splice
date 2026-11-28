package splice.app.cli.setup

import splice.core.install.SEATD_PIPE_EXPLANATION
import splice.core.install.seatdPipeRepository
import splice.lifecycle.seatd.SeatdInstaller

private const val SEATD_LATER = "To add seatd-pipe later, run: splice setup --update"

/** install.sh calls this after the base install. The bundled pin supplies the link and the installer owns the bytes. */
internal class SeatdInstallCommand {
    fun run(): Int {
        val installer = SeatdInstaller()
        if (installer.installed() != null) return 0
        if (!confirmed()) {
            println(SEATD_LATER)
            return 0
        }
        return when (val result = installer.install()) {
            is SeatdInstaller.Result.Installed -> {
                println("Installed seatd-pipe ${result.location.version}.")
                result.cleanupNotice?.let(::println)
                0
            }
            is SeatdInstaller.Result.AlreadyInstalled -> 0
            is SeatdInstaller.Result.Failed -> {
                System.err.println(result.message)
                1
            }
        }
    }

    private fun confirmed(): Boolean {
        val console = System.console() ?: return false
        println(SEATD_PIPE_EXPLANATION)
        println(seatdPipeRepository)
        println(
            "If you say No, Claude Code Desktop and Gemini are not supported; " +
                "terminal claude and all other providers work normally.",
        )
        val answer = console.readLine("Install seatd-pipe? [y/N] ")?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }
}
