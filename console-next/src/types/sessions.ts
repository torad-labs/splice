// The Claude Code session registry and its transcript, typed from the routes that serve them.

/** What the daemon writes when splice did not launch the session (SessionsRoutes.kt:12). */
export const UNKNOWN_HEAD = 'unknown head';

/** Derived by the daemon, never trusted from the file: a registration whose pid is gone is GONE
 *  whatever its status says, and one that has not been heard from inside the stale window is STALE. */
/** SessionActivity.json: the newest thing the session said, was told or called; never a tool result or a system note. On a call
 *  (`tool` set) the text is what it was for, its description or the name of what it touched, and empty when it says nothing. */
export interface SessionLast {
  role: 'user' | 'assistant';
  tool: string | null;
  text: string;
  ts: number | null;
  /** On an AskUserQuestion call, the questions it asks (AskedQuestions.kt). Absent on anything else and from a daemon older than it. */
  asks?: SessionAsk[];
}

/** One question a session asked through AskUserQuestion: the question whole, its options' labels, and whether several may be chosen.
 *  The descriptions under each option stay in the client where the question is answered. */
export interface SessionAsk {
  question: string;
  options: string[];
  multi: boolean;
}

export type SessionAvailability = 'live' | 'stale' | 'gone';

/**
 * The git root a session groups under, resolved by the daemon inside the trusted
 * root set ($HOME, /tmp, statuslineGitRoots) through a cached resolver, with worktrees folded into
 * their shared repo (FEATURES.md 4.4 Group by repo).
 *
 * Section 6 leaves the shape open ("GET /api/sessions/{id}/repo (or a `repo` field on
 * /api/sessions)"). The console requires the FIELD form: grouping by repo needs the root of every
 * row in one payload, and a per-id route would be N requests for one board.
 */
export interface SessionRepo {
  /** The git common root, shared by a worktree and its main checkout. */
  root: string;
  /** The URL the repo was cloned from (`origin`), credentials removed, when the daemon reports it; its last piece names the repo. */
  remote?: string;
  /** The worktree's own path when the session's cwd is a linked worktree of [root]. */
  worktree?: string;
  /** Why the row groups where it does, when the cwd sits outside the trusted root set and the
   *  resolver returned the cwd itself instead of failing. */
  reason?: string;
}

/** How the daemon read the session's start: through a head (`head`), with `claude` run directly
 *  and no SPLICE=1 in its environment (`direct`), or not readable (`unknown`). */
export type SessionRoute = 'head' | 'direct' | 'unknown';

/** One registration, as /api/sessions returns it. */
export interface SessionRow {
  pid: number | null;
  session_id: string | null;
  name: string | null;
  /** Claude Code's own kind, free text ("interactive" on every registration on this machine,
   *  31 of 31 at 2026-09-18): the registry file is the client's, so this stays a string. */
  kind: string | null;
  version: string | null;
  cwd: string | null;
  /** The client's own status word ("busy", "shell", ...), not a console state. */
  status: string | null;
  /** What a waiting session waits for, in Claude Code's words ("input needed", "permission prompt"). Absent from a daemon older than
   *  it, and null when the client wrote none. */
  waiting_for?: string | null;
  /** How the session was started, in Claude Code's words: "cli" is a terminal, another client names itself ("eli-telegram"). */
  entrypoint?: string | null;
  status_updated_at: number | null;
  started_at: number | null;
  updated_at: number | null;
  /** The `uds:<path>` address another session can SendMessage to, when the client minted a socket. */
  address: string | null;
  /** The head key that launched the session, or UNKNOWN_HEAD. Never absent on the wire. */
  head: string;
  /** Why `head` is what it is (ProcessEnvironment.route). Absent from a daemon older than it. */
  route?: SessionRoute;
  availability: SessionAvailability;
  /** Absent until the daemon resolves it. */
  repo?: SessionRepo;
  /** The team the operator assigned the session to (FEATURES.md 4.13, 6: "the bound team id or
   *  null"): the daemon binds sessions to slots, and the console groups by that binding. Null for a
   *  session bound to no team, which is most of them. */
  team?: string | null;
  /** The newest message in the session's transcript, clipped to one line by the daemon and redacted. Null when the transcript view is
   *  off or nothing is readable; absent from a daemon older than it. */
  last?: SessionLast | null;
  /** The selected login for this session when the daemon can attribute one, not the head's
   *  currently selected account for an unrelated session. */
  account?: string | null;
  /** Which durable sources found a historical session. Absent on live registry-only rows. */
  source?: 'history+transcript' | 'history-only' | 'transcript-only' | 'registry-only';
  /** A primary file may exist but have no conversation bytes yet. Such a row is counted, not offered
   *  as a resumable session. Live rows carry it too (): false when no head's tree holds a
   *  transcript with conversation, which a registry-only session such as a bridge process never has.
   *  Absent on older daemons that did not measure it, with no head tree to ask, or with the transcript
   *  view off: absence claims nothing, so the row keeps its Resume. */
  resumable?: boolean;
}

export interface SessionHistoryOff {
  state: 'off';
  reason: string;
}

export interface SessionHistoryPayload {
  /** A page, not every stored session. Cursor continues after its last sort key. */
  sessions: SessionRow[];
  next: string | null;
  /** A source that could not be listed. Partial rows remain visible, never passed off as complete. */
  errors?: string[];
  skipped?: Record<string, number>;
}

export type SessionHistoryRead = SessionHistoryPayload | SessionHistoryOff;

export interface SessionsPayload {
  /**
   * The daemon's own sentence about what this registry can never show (SessionsRoutes.kt:13-14):
   * "headless `claude -p` runs never register; gone = the process exited; stale = alive but no
   * registry update inside the stale window".
   *
   * THIS is the headless flag, and it is a payload field rather than a row field on purpose: a
   * headless run never registers, so a per-row boolean could only ever be false (measured
   * 2026-09-18 on this machine: 31 registration files, every one `kind: interactive`). The page
   * prints this sentence where it would otherwise print "no sessions".
   */
  note: string;
  /** The Claude Code versions the daemon sends a note to, oldest first (PeerNoteAbi.AUDITED_VERSIONS). Absent from a daemon that predates it. */
  note_versions?: string[];
  /** Present when the registry DIRECTORY exists but could not be listed: an unreadable directory is
   *  not an empty one, and the console says which happened (SessionRegistry.kt:29-32). */
  error?: string;
  sessions: SessionRow[];
}

/** Which way a message went from the session the edges were asked for. */
export type EdgeDirection = 'out' | 'in';

/**
 * One message edge: that a session sent a message and to which address, read on the wire from the
 * SendMessage tool call's input (FEATURES.md 4.13). NO TEXT: the message itself is read from the
 * transcripts on demand and never stored, which is why this carries an address and a time and
 * nothing else. Written by ActivityRoutes' EdgeIndex.edgesOf () for both edge routes.
 *
 * THE TWO ENDS ARE DIFFERENT KINDS OF VALUE, and reading one as the other is a wrong peer.
 */
export interface SessionEdge {
  /** The SESSION ID of the session that sent the message (MessageEdgeStore: "[from] is the sending
   *  session id"), never an address. */
  from: string;
  /** What the SendMessage call named: an address (`uds:<socket path>`), or a name the daemon
   *  resolved to the registry address that answers to it, else the name verbatim. */
  to: string;
  /** Epoch ms of the call. */
  at: number;
  direction: EdgeDirection;
}

/** One of a session's own edges (): the stored edge with the text its sender handed off, read
 *  on demand from the sender's transcript through the redacted read team chat uses (ActivityRoutes'
 *  HandedText). A text not found is `text` null with `missing_reason` saying why, never ''. */
export interface HandedEdge extends SessionEdge {
  text: string | null;
  /** The transcript the text was read from; null with the text. */
  text_source: string | null;
  missing_reason: string | null;
}

/** GET /api/sessions/{id}/edges: one session's edges, oldest first, each with what it handed off. */
export interface SessionEdgesPayload {
  session_id: string;
  state?: 'on' | 'off' | 'deleted' | null;
  reason?: string | null;
  edges: HandedEdge[];
}

/** GET /api/sessions/{id}/resume?head=<key> (ResumeRecipeRoute, ): what `<command> -r <id>` on
 *  that head does, read without doing it. The launch asks the same resolution and acts on it; this
 *  copies and rewrites nothing. A refusal is the control plane's `{"error": <sentence>}`. */
export interface ResumeRecipe {
  session_id: string;
  head: string;
  /** What the operator runs: the head's wrapper command, `-r`, the id. */
  argv: string[];
  /** The transcript the resume reads. */
  from: string;
  /** The directory of the head's own tree the session resumes from. */
  to_tree: string;
  /** True when the launch copies the transcript in from another head's tree; false when the head
   *  already holds it and it resumes where it lies. */
  copies: boolean;
  /** The head's pinned model, which the transcript's assistant rows are moved onto. */
  model: string;
  /** Whether the original session runs now, by the registry; null when the daemon has none wired. A
   *  running original and its resumed copy diverge from the first turn. */
  live: boolean | null;
}

/** GET /api/sessions/edges (ActivityRoutes.boardEdges): every registry session's edges in one read,
 *  keyed by session id, empty arrays included, each in the per-session route's edge shape. */
export interface BoardEdgesPayload {
  state?: 'on' | 'off' | 'deleted' | null;
  reason?: string | null;
  sessions: Record<string, SessionEdge[]>;
}

/** Who spoke. The daemon's reader folds the client's own event kinds into these four, because the
 *  page renders a conversation and "tool result" is a participant's turn, not a JSONL event. */
export type TranscriptRole = 'user' | 'assistant' | 'system' | 'tool';

export interface TranscriptMessage {
  /** Position in the transcript, counted from 0. Stable across pages, which is what lets a loaded
   *  page be appended without renumbering what is already on screen. */
  index: number;
  role: TranscriptRole;
  /** Epoch ms when the transcript carries it; the client does not stamp every event. */
  ts?: number;
  /** The message text, already redacted daemon-side. */
  text: string;
  /** The tool's name on a tool call or a tool result. */
  tool?: string;
  /** The client's call identity, shared by that call and its result even across transcript pages. */
  tool_use_id?: string;
  /** True on a tool RESULT, false on the call that produced it; absent on everything else. */
  result?: boolean;
}

export interface TranscriptPage {
  session_id: string;
  /** The file that answered this page. FEATURES.md 4.4: the reader falls back from the head's own
   *  config dir to the vanilla ~/.claude/projects tree, and "shows which path it read", so the page
   *  prints this rather than assuming. */
  path: string;
  messages: TranscriptMessage[];
  /** Opaque token for the next page. Absent or null means this was the last page. The console
   *  never parses it: it is the daemon's cursor to mint. */
  next?: string | null;
  /** On a page read from the end of the conversation: the cursor of the page before it, null at the start. */
  earlier?: string | null;
  /** The number of messages in the whole transcript, when the daemon counted them. */
  total?: number;
}

/** The live global switch denied reading before a transcript file was opened. */
export interface TranscriptOff {
  state: 'off';
  reason: string;
}

export type TranscriptRead = TranscriptPage | TranscriptOff;



/** The daemon's answer to a note: it wrote the note to the session's inbox, and the client gives no receipt, so delivery is never known. */
export interface NoteAnswer {
  submitted: true;
  message_id: string;
  delivery: 'unknown';
}
