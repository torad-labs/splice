// The project entity, typed from the daemon that serves it: ProjectsRoutes
// (features/sessions/src/main/kotlin/splice/sessions/http/ProjectsRoutes.kt, V4-131). A project is
// a git root, the registry sessions' repos plus every team's declared repo, and its id IS that root.
//
//   GET /api/projects              {projects: ProjectRow[]}
//   GET /api/projects/{id}         one ProjectRow, the SAME row the list carries (ProjectView.row);
//                                  an id that is not a root the daemon has seen is a 404 whose
//                                  error names it
//   GET /api/projects/{id}/files   ProjectFilesPayload

/** One repo, as ProjectView.row writes it for both the list and the detail route. */
export interface ProjectRow {
  /** The git root, which is also the route's id. */
  id: string;
  root: string;
  /** Live sessions on this repo right now. */
  live_sessions: number;
  /** Unarchived teams whose declared repo this is. */
  teams: number;
  /** Turns since day_start, joined on the 8-character session tag over the repo's registry sessions
   *  and every session its teams' slots ever held. */
  turns_today: number;
  /** USD today, or null when no head declares rates for the models it ran: absent rates mean "no
   *  dollar figure" (FEATURES.md 2.3), never a cost of zero. */
  cost_today_usd: number | null;
  /** The start of the day those counts cover, epoch ms: the UTC day, sent rather than assumed,
   *  because the console must not print "today" over a boundary it guessed. */
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
