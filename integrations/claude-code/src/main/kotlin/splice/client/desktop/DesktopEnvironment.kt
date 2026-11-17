// NEW: desktop-door (docs/specs/desktop-door.md, decisions 4 and 8). What the Code tab's Claude Code is given to run on
// splice's door, in Claude Desktop's own environment store: Settings, Claude Code, Environment variables. Desktop gives
// what is set there to each Code tab session and to nothing else, so no terminal session reads it. Desktop keeps that
// store encrypted with the system keyring, so a person sets it once; splice never writes it. Read on Nov 16, 2026 from
// Desktop 5.11492.5 and its Claude Code 2.1.325: a Code tab session starts from Desktop's own environment, takes the
// stored variables over it, then Desktop's own session values, which in its claude.ai mode set no provider switch.
package splice.client.desktop

/** The variables a person sets once in Desktop's environment store, and the names they remove there to leave. */
public class DesktopEnvironment {
    /** Each variable with its value, in the order a person sets them: Mantle on, the door at [door], the desktop key
     *  [key], the window [window] every count the door reports is a share of, and auto mode's classifier off. */
    public fun of(door: String, key: String, window: Long): List<Pair<String, String>> = listOf(
        DESKTOP_USE_MANTLE to "1",
        DESKTOP_MANTLE_URL to door,
        DESKTOP_BEARER to key,
        DESKTOP_WINDOW to window.toString(),
        DESKTOP_AUTO_MODE to "0",
    )

    /** The variables' names alone: removed from Desktop's store, they put the Code tab back on the person's own
     *  Claude login. */
    public val names: List<String> =
        listOf(DESKTOP_USE_MANTLE, DESKTOP_MANTLE_URL, DESKTOP_BEARER, DESKTOP_WINDOW, DESKTOP_AUTO_MODE)
}

/** Where a person sets them, in Desktop's own words. */
public const val DESKTOP_ENV_PLACE: String = "Claude Desktop → Settings → Claude Code → Environment variables"
