// NEW: the two operator settings the sessions routes read live, per request: whether transcripts may be read at all,
// and which git roots a head's sessions resolve repositories under. One port over the configuration, so a rig
// answers both without a ConfigService and a switch of its own.
package splice.sessions.http

import splice.core.config.ConfigService

public interface SessionSettings {
    /** The global transcript-view switch; off means no reader opens a file. */
    public fun transcriptView(): Boolean

    /** The statusline git roots in force for [head]. */
    public fun gitRoots(head: String?): List<String>
}

/** No configuration: transcripts on, no extra git roots. */
internal object NoSessionSettings : SessionSettings {
    override fun transcriptView(): Boolean = true
    override fun gitRoots(head: String?): List<String> = emptyList()
}

/** The settings as the daemon's live configuration holds them. */
public class ConfigSessionSettings(private val config: ConfigService) : SessionSettings {
    override fun transcriptView(): Boolean = config.getConfig().transcriptView
    override fun gitRoots(head: String?): List<String> = config.getConfig(head).statuslineGitRoots
}
