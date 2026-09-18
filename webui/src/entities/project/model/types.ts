// The project entity, typed from FEATURES.md 4.14 and section 6. A project is already a daemon
// scope ([[compaction.project]] is keyed by path, SessionProject.kt resolves a session to its cwd
// for it), and the operator asked for a view per git repo, so this is a page's data, not a filter.
//
// PENDING V4-131. Neither route exists, so these are the contracts the console builds against, and
// the store holds the pending state instead of a mocked list.
import type { PendingRoute } from '@shared/api';

/** One repo seen in the trusted root set (FEATURES.md 4.14 Project list). */
export interface ProjectRow {
  /** The git root, which is also the route's id. */
  id: string;
  root: string;
  /** Live sessions on this repo right now. */
  live_sessions: number;
  teams: number;
  turns_today: number;
  /** USD today, or null when no head declares rates for the models it ran: absent rates mean "no
   *  dollar figure" (FEATURES.md 2.3), never a cost of zero. */
  cost_today_usd: number | null;
  /** The start of the day those counts cover, epoch ms. Sent rather than assumed, because the
   *  console must not print "today" over a boundary it guessed (a local midnight, a rolling 24h
   *  and a UTC day all render differently and only the daemon knows which one it counted). */
  day_start: number;
  /** Epoch ms of the newest activity on any session in this repo, or null when the repo has none
   *  registered. Null is a real answer ("no session has touched it"), never a zero timestamp. */
  last_activity: number | null;
}

export interface ProjectsPayload {
  projects: ProjectRow[];
}

/** A file the console reads for a project, read-only, path shown (FEATURES.md 4.14). */
export type ProjectFileKind = 'instructions' | 'memory';

export interface ProjectFile {
  kind: ProjectFileKind;
  /** The absolute path that was read, printed by the page: the point of the row is that the
   *  operator can see exactly which file answered. */
  path: string;
  /** The head whose config dir the file came from, or null for the repo's own files. Client memory
   *  is per head (each head has its own config dir), so one project can answer several times. */
  head: string | null;
  text: string;
}

export interface ProjectFilesPayload {
  id: string;
  files: ProjectFile[];
  /** The directories the reader looked in. FEATURES.md 4.14 requires the empty to name the exact
   *  directory it searched, so the daemon sends them and the page prints them. */
  looked_in: string[];
  /** The client's own per-project memory switch, when the daemon can read it. FEATURES.md 4.14
   *  names `autoMemoryEnabled` as the thing an empty memory list must blame when it is the reason,
   *  rather than leaving the operator to guess why the directory was empty. */
  auto_memory_enabled?: boolean;
}

export type ProjectsSlice = ProjectsPayload | PendingRoute;

export type ProjectFilesSlice = ProjectFilesPayload | PendingRoute;
