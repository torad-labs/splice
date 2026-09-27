// NEW: V4-218 — the one home every splice path resolves `~` against. Every path used to read the JVM's
// user.home, which is the passwd entry's home and ignores the environment: a run under another HOME (a
// sandbox, CI, a second profile) read and wrote the real home's config, credentials and state. Found
// 2026-09-25 building the isolated film stack, where `splice add codex` under HOME=/tmp/film-home would
// have resolved its ~/ paths into the operator's real home. HOME is what the user's shell names, so it
// wins; user.home stays the answer where HOME is unset or blank. The launch shim passes the same answer
// to the JVMs it starts (-Duser.home), and kt-user-home-single-resolver keeps every other read out.
package splice.core.config

import splice.core.util.EnvReader
import java.nio.file.Path
import java.nio.file.Paths

public object UserHome {

    /** Set only inside [within]: the home a test gives the whole process. */
    @PublishedApi
    @Volatile
    internal var redirected: Path? = null

    /** The home: [env]'s HOME unless it is unset or blank, then the JVM's user.home. A caller that
     *  already reads its environment through an [EnvReader] passes it, so one environment decides both
     *  HOME and the variables read beside it (XDG_CONFIG_HOME, SPLICE_STATE_DIR). */
    public fun dir(env: EnvReader = EnvReader(System::getenv)): Path =
        redirected ?: Paths.get(of(env, System.getProperty(USER_HOME_PROPERTY)))

    /** The shell's HOME when it names a directory; null means a pasted `$HOME` path cannot work. */
    public fun environmentHome(env: EnvReader): String? = env(HOME)?.takeIf(String::isNotBlank)

    /** The rule with both sources passed in: HOME when it names something, else [userHome]. */
    public fun of(env: EnvReader, userHome: String): String = environmentHome(env) ?: userHome

    /** `~/rest` against [dir]; any other path as written. */
    public fun expand(raw: String, env: EnvReader = EnvReader(System::getenv)): String =
        if (raw.startsWith("~/")) dir(env).toString() + raw.substring(1) else raw

    /** The test seam: [block] runs with [home] as the home of every resolution in this process, then the
     *  environment answers again. Process-wide on purpose: a CLI verb builds its paths deep inside, on
     *  whatever thread, where no injected value reaches, which is how the user.home property this
     *  replaces reached them. Tests may no longer move user.home: HOME would outrank it. */
    public inline fun <T> within(home: Path, block: () -> T): T {
        val previous = redirected
        redirected = home
        try {
            return block()
        } finally {
            redirected = previous
        }
    }
}

private const val HOME = "HOME"
private const val USER_HOME_PROPERTY = "user.home"
