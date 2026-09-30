// The project as the daemon writes it: ProjectsRoutes. A project is a git root (the registry sessions' repos plus every
// team's declared repo) and its id IS that root.
//   GET /api/projects              {projects: ProjectRow[]}
//   GET /api/projects/{id}         one ProjectRow, the same row the list carries
//   GET /api/projects/{id}/files   ProjectFilesPayload

/** A compaction rule as ProjectsRoutes writes it. */
export interface ProjectCompactionRule {
  scope: 'global' | 'model' | 'project' | 'project-model';
  /** Core's composed label (`global`, `model:<id>`, `project:/abs/path`, ...). */
  source: string;
  /** Live length: 0 for an explicit opt-out, null when the rule's file cannot be read. */
  chars: number | null;
}

/** Which entry of the trusted set covers the repo. */
export type TrustedRootEntry = 'home' | 'tmp' | 'statuslineGitRoots';

export interface ProjectStatuslineRoot {
  head: string;
  /** The realpath of the covering trusted root; null when the repo lies outside every one. */
  root: string | null;
  entry: TrustedRootEntry | null;
}

export interface ProjectRow {
  id: string;
  root: string;
  live_sessions: number;
  /** Unarchived teams whose declared repo this is. */
  teams: number;
  turns_today: number;
  /** USD today of the priced turns, null when there are turns and none was priced: never a cost of zero. */
  cost_today_usd: number | null;
  unpriced_turns_today?: number;
  /** The start of the day those counts cover, epoch ms. */
  day_start: number;
  last_activity: number | null;
  /** `[]` means no rule applies and the client's own instructions stand; `null` means the daemon never wired its table. */
  compaction: ProjectCompactionRule[] | null;
  statusline_roots: ProjectStatuslineRoot[];
}

export interface ProjectsPayload {
  projects: ProjectRow[];
}

export type ProjectFileKind = 'instructions' | 'memory';

export interface ProjectFile {
  kind: ProjectFileKind;
  /** The absolute path that was read: the point of the row is that the operator can see exactly which file answered. */
  path: string;
  /** The head whose config dir the file came from, or null for the repo's own files. */
  head: string | null;
  text: string;
}

export interface ProjectFilesPayload {
  id: string;
  files: ProjectFile[];
  /** The directories the reader looked in, so an empty list can name where it searched. */
  looked_in: string[];
  auto_memory_enabled?: boolean;
}
