// NEW: the splice CLI verbs modeled as a closed sealed hierarchy (was `object` singletons dispatched
// by a stringly-typed map). argv parses into a typed case (Install/Login carry their arg as data) and
// run() is total — adding a verb is a compile error until every site handles it.
package splice.app.cli

import kotlinx.coroutines.runBlocking

/** The splice CLI verbs as a closed, exhaustively-dispatched hierarchy: argv is parsed into a typed
 *  case (so args like the install target are data, not positional lookups), and run() is total —
 *  adding a verb is a compile error until every site handles it. Replaces the old string→object map. */
public sealed class Command {
    public abstract fun run()

    public data object Doctor : Command() { override fun run(): Unit = doctor() }
    public data object Version : Command() { override fun run(): Unit = println("splice kt-1") }
    public data object Init : Command() { override fun run(): Unit = init() }
    public data class Install(val target: String?) : Command() { override fun run(): Unit = install(target) }
    public data class Uninstall(val target: String?) : Command() { override fun run(): Unit = uninstall(target) }
    public data class Login(val head: String?) : Command() { override fun run(): Unit = runBlocking { login(head) } }
    public data object Setup : Command() { override fun run(): Unit = runBlocking { setup() } }
    public data object Status : Command() { override fun run(): Unit = status() }
    public data object Dashboard : Command() { override fun run(): Unit = dashboard() }

    public companion object {
        // parse table (verb -> factory): a map keeps parse() at trivial complexity (no 10-arm `when`,
        // which would trip CyclomaticComplexMethod). The COMMANDS are the sealed type; this is just parsing.
        private val verbs: Map<String, (Array<String>) -> Command> = mapOf(
            "doctor" to { Doctor }, "version" to { Version }, "init" to { Init },
            "install" to { a -> Install(a.getOrNull(1)) },
            "uninstall" to { a -> Uninstall(a.getOrNull(1)) },
            "login" to { a -> Login(a.getOrNull(1)) },
            "setup" to { Setup }, "status" to { Status }, "dashboard" to { Dashboard },
        )

        /** argv -> Command, or null for an unknown/empty verb (caller prints usage). */
        public fun parse(args: Array<String>): Command? = verbs[args.firstOrNull()]?.invoke(args)
    }
}
