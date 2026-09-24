// The Claude Code session registry, typed from the daemon that serves it.
//
// GET /api/sessions EXISTS (ControlServer.kt:154 -> SessionsRoutes.sessionsJson) and returns the
// registry read from ~/.claude/sessions/<pid>.json, one file per interactive session. Every field
// is optional on the wire because Claude Code owns that schema and may add, rename or omit keys
// (SessionRegistry.kt:1-8), which is why almost every field below is nullable and why absence has
// to read as "the client did not write it", never as a default.
//
// The V4-130 additions (repo, team) are PENDING and typed from FEATURES.md section 6 and 4.4; the
// fields already on the wire (availability) are typed from the daemon's own enum. Because the route
// itself exists, this store is NOT a PendingRoute union: a 404 here is an error, not "not built
// yet", and the pending part is the two optional fields.

/** What the daemon writes when splice did not launch the session (SessionsRoutes.kt:12). */
export const UNKNOWN_HEAD = 'unknown head';

/** Derived by the daemon, never trusted from the file: a registration whose pid is gone is GONE
 *  whatever its status says, and one that has not been heard from inside the stale window is STALE. */
export type SessionAvailability = 'live' | 'stale' | 'gone';

/**
 * PENDING V4-130. The git root a session groups under, resolved by the daemon inside the trusted
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
  /** PENDING V4-130. Absent until the daemon resolves it. */
  repo?: SessionRepo;
  /** The team the operator assigned the session to (FEATURES.md 4.13, 6: "the bound team id or
   *  null"): the daemon binds sessions to slots, and the console groups by that binding. Null for a
   *  session bound to no team, which is most of them. */
  team?: string | null;
}

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
 * nothing else. Written by ActivityRoutes' EdgeIndex.edgesOf (V4-130) for both edge routes.
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

/** GET /api/sessions/{id}/edges: one session's edges, oldest first. */
export interface SessionEdgesPayload {
  session_id: string;
  edges: SessionEdge[];
}

/** GET /api/sessions/edges (ActivityRoutes.boardEdges): every registry session's edges in one read,
 *  keyed by session id, empty arrays included, each in the per-session route's edge shape. */
export interface BoardEdgesPayload {
  sessions: Record<string, SessionEdge[]>;
}
