// NEW: the transcript-view switch as a rig flips it, with no git roots: the settings port a route test passes when it
// only needs the switch.
package splice.sessions.http

import splice.sessions.transcript.SessionTranscriptViewEnabled

internal class TranscriptViewSettings(private val enabled: SessionTranscriptViewEnabled) : SessionSettings {
    override fun transcriptView(): Boolean = enabled()
    override fun gitRoots(head: String?): List<String> = emptyList()
}
