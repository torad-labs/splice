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

    /** The Projects folders in force for [head]; empty means a project is the git repo. */
    public fun projectFolders(head: String?): List<String> = emptyList()
}

/** No configuration: transcripts on, no extra git roots. */
internal object NoSessionSettings : SessionSettings {
    override fun transcriptView(): Boolean = true
    override fun gitRoots(head: String?): List<String> = emptyList()
}

/** [settings], with each answer read once for a listing. A listing asks the same three settings for every row, and
 *  each ask reads the configuration again: 3,201 rows asked about 16,000 times for one /api/sessions reply (Nov 12,
 *  2026). Inside [listing] an answer is read at its first ask and held for the thread building that listing; outside it
 *  every ask reads through, so a setting changed between two replies shows in the next one. */
internal class ListingSettings(private val settings: SessionSettings) : SessionSettings {
    private class Held {
        var view: Boolean? = null
        val roots = HashMap<String?, List<String>>()
        val folders = HashMap<String?, List<String>>()
    }

    private val held = ThreadLocal<Held?>()

    /** Holds the answers for the listing this thread builds, until the handle it returns is closed. A listing begun
     *  inside another shares the outer one's answers, and closing its handle leaves them held. */
    fun listing(): AutoCloseable {
        if (held.get() != null) return AutoCloseable {}
        held.set(Held())
        return AutoCloseable { held.remove() }
    }

    override fun transcriptView(): Boolean {
        val now = held.get() ?: return settings.transcriptView()
        return now.view ?: settings.transcriptView().also { now.view = it }
    }

    override fun gitRoots(head: String?): List<String> =
        held.get()?.roots?.getOrPut(head) { settings.gitRoots(head) } ?: settings.gitRoots(head)

    override fun projectFolders(head: String?): List<String> =
        held.get()?.folders?.getOrPut(head) { settings.projectFolders(head) } ?: settings.projectFolders(head)
}

/** The settings as the daemon's live configuration holds them. */
public class ConfigSessionSettings(private val config: ConfigService) : SessionSettings {
    override fun transcriptView(): Boolean = config.getConfig().transcriptView
    override fun gitRoots(head: String?): List<String> = config.getConfig(head).statuslineGitRoots
    override fun projectFolders(head: String?): List<String> = config.getConfig(head).projectsFolders
}
