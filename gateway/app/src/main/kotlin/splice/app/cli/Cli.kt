// NEW: splice CLI dispatch (P5-CLI grows this). In cli/ so the walls exempt its runBlocking use
// (admin one-shots, not daemon hot path). :app is exempt from no-println — a terminal tool writes
// to stdout. Verbs live in Command.kt; doctor's checks in DoctorCommand.kt.
package splice.app.cli

public fun runCli(args: Array<String>): Int {
    val command = Command.parse(args) ?: run {
        System.err.println(
            "usage: splice [setup|status|restart|dashboard|login <head>|install|uninstall|init|doctor|daemon|version]",
        )
        return 2
    }
    return command.run()
}
