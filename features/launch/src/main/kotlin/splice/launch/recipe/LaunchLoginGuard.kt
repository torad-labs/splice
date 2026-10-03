// NEW: the native login owner holds a destination while its credential file can be replaced.
package splice.launch.recipe

import java.nio.file.Path

/** A managed launch must not start over a destination undergoing native credential replacement. */
public fun interface LaunchLoginGuard {
    /** Null when launch may proceed; a named refusal while that exact directory is held. */
    public fun refusal(configDir: Path): String?
}
