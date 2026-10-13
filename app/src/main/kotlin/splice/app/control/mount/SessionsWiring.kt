// NEW: V4-444 — what the sessions, teams and projects mounts share: the session reads built once over the
// registry, the heads as sessions see them, and the transcript history's index and roots. Split from
// SessionsMount so each capability's routes sit in their own mount and read the same instances.
package splice.app.control.mount

import splice.app.control.ConsolePorts
import splice.app.control.LimitResume
import splice.app.control.ManagedHead
import splice.app.control.SessionAccountLabels
import splice.app.control.SessionHeadAdapter
import splice.app.control.SessionPerfFacts
import splice.app.control.TranscriptLimitEnding
import splice.client.resume.ModelMoves
import splice.client.resume.MovingTrust
import splice.client.transcript.MovedTranscripts
import splice.client.transcript.TranscriptHistoryIndex
import splice.client.transcript.TranscriptReader
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.perf.KeptHistory
import splice.core.process.LaunchOwners
import splice.core.retired.RetiredHeads
import splice.core.util.LogSink
import splice.sessions.http.ActivitySource
import splice.sessions.http.ConfigSessionSettings
import splice.sessions.http.LaunchedTerminal
import splice.sessions.http.LaunchedTerminals
import splice.sessions.http.MoveLedger
import splice.sessions.http.ProjectSessions
import splice.sessions.http.SessionActed
import splice.sessions.http.SessionDrive
import splice.sessions.http.SessionRowFacts
import splice.sessions.http.SessionsRoutes
import splice.sessions.http.TeamSource
import splice.sessions.http.TerminalSource
import splice.sessions.http.TranscriptRoots
import splice.sessions.note.PeerNoteSocket
import splice.sessions.registry.RecordedSessions
import splice.sessions.registry.SEEN_SESSIONS_DIR
import splice.sessions.registry.SeenSessions
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import splice.sessions.transcript.SessionHistoryRoot
import splice.sessions.transcript.SessionTranscriptViewEnabled
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

/** [routes] is null when no session registry is wired, and every mount built over it then registers nothing.
 *  [ports] is read at CALL time: ControlPlane assigns the activity and team stores after construction. */
internal class SessionsWiring(
    sessions: SessionSource?,
    private val heads: Map<String, ManagedHead>,
    config: ConfigService,
    private val ports: ConsolePorts,
    log: LogSink,
) {
    val sessionHeads = SessionHeadAdapter.adapt(heads)
    val historyIndex = TranscriptHistoryIndex()
    val historyRoots = listOf(SessionHistoryRoot(null, UserHome.claudeDir())) +
        sessionHeads.mapNotNull { (head, source) -> source.transcriptRoot?.let { SessionHistoryRoot(head, it) } } +
        // A removed command's sessions stay readable: its config directory is on the retired record.
        RetiredHeads(config.statePaths.stateDir).all().mapNotNull { retired ->
            retired.configDir?.let { SessionHistoryRoot(retired.key, Path.of(it)) }
        }

    /** The registry joined with durable transcript history: every session the project views and request rows know. */
    fun projectSessions(sessions: SessionSource, config: ConfigService): ProjectSessions = ProjectSessions(
        sessions,
        historyIndex,
        historyRoots,
        SessionTranscriptViewEnabled { config.getConfig().transcriptView },
    )

    private val perfFacts = SessionPerfFacts(heads, ports)
    private val sessionAccounts = SessionAccountLabels(heads, ports)

    /** The launch records splice-launch writes: a session started from the person's own tmux names its pane there. */
    private val launchOwners = LaunchOwners(StatePaths().stateDir)

    /** One session's terminal, by its id: say, answer, stop and its screen (Sessions). Read per call, the [ports] rule.
     *  A session splice did not open is reached through the pane its own launch recorded, while its process runs. */
    val drive = SessionDrive(
        TerminalSource { ports.sessionDriver },
        acted = SessionActed { limitResume?.acted(it) },
        launched = LaunchedTerminals { id ->
            sessions?.read()
                ?.firstOrNull { it.sessionId == id && it.availability != SessionAvailability.GONE }
                ?.process?.pid
                ?.let { pid ->
                    launchOwners.read(pid)?.takeIf { it.kind == "session" }?.terminal
                        ?.let { LaunchedTerminal(pid, it.pane, it.socket) }
                }
        },
    )

    /** What a login added after a plan limit does for the sessions it stopped. Null with no session registry. */
    val limitResume = sessions?.let {
        val io = ProcessDispatchers().io()
        val ended = TranscriptLimitEnding(config, TranscriptReader(), TranscriptRoots(sessionHeads))
        LimitResume(it, perfFacts.endingOf(), PeerNoteSocket(io), LifecycleScope(io), log, ended)
    }

    /** Where each session's moves are kept: the transcript route serves them and Continue on completes them. */
    private val moves = ModelMoves(StatePaths().modelMovesDir)

    /** Which moves are in flight, and the folder each carries his trust for while it is (MovingTrust). */
    private val moving = MovingTrust(StatePaths().movingTrustDir)

    /** The listing Sessions and Requests read: the registry, with the sessions Claude Code already forgot kept as
     *  ended under the one history window (SeenSessions). */
    val listed = sessions?.let {
        // the daemon's own state root, never the process default: a daemon on another root keeps its record there
        val dir = config.statePaths.stateDir.resolve(SEEN_SESSIONS_DIR)
        RecordedSessions(it, SeenSessions(dir, KeptHistory { config.getConfig().historyWindow }))
    }
    val routes: SessionsRoutes? = listed?.let {
        SessionsRoutes(
            it,
            MovedTranscripts(TranscriptReader(), moves),
            TranscriptRoots(sessionHeads),
            ConfigSessionSettings(config),
            ActivitySource { ports.activity },
            teams = TeamSource { ports.teams },
            facts = SessionRowFacts(
                accountOf = sessionAccounts,
                turnsOf = perfFacts.turnsOf(),
                endingOf = perfFacts.endingOf(),
                servedOf = perfFacts.servedOf(moves),
            ),
        )
    }

    /** Continue on's ledger: what a move leaves behind while it is in flight, and nothing after it (ContinueLedger). */
    val moveLedger: MoveLedger = ContinueLedger(moves, moving, modelOf = { routes?.modelOf(it) })
}
