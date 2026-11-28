package splice.launch

import kotlinx.serialization.Serializable

/** Launch settings used by the daemon, not part of the environment it returns to the client. */
@Serializable
public data class LaunchModSettings(
    val labels: Map<String, String> = emptyMap(),
    val modsOff: ModsOff = ModsOff(),
)

/** A launch may silence all mods or just the named ones, including ones enabled after it starts. */
@Serializable
public data class ModsOff(val all: Boolean = false, val names: Set<String> = emptySet()) {
    public fun suppresses(name: String): Boolean = all || name in names
}

/** The owner is the launch's identity, not the session id or the PID it will exec into. */
@Serializable
public data class LaunchNotice(
    val owner: String,
    val pid: Long?,
    val cwd: String?,
    val settings: LaunchModSettings,
)

/** A daemon receives the launch settings before returning the recipe, then may learn its session from a hook. */
public interface LaunchNotices {
    public fun launched(launch: LaunchNotice)

    public fun sessionStarted(owner: String?, sessionId: String, cwd: String)

    /** Names on the off list that are not installed now. They still stay on the off list. */
    public fun unknownMods(names: Set<String>): Set<String>
}
