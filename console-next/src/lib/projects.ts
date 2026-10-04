// The Projects page's arithmetic: a repo's name and sentence, and the standing prompt and compaction rule read from and written
// into splice.toml. Pure over the daemon's payloads and the parsed topology.
import { ABSENT, fmtUsd } from './format';
import { repoNameOf } from './repo';
import { sessionLabel } from './sessions';
import { P } from './words-projects';
import type { SessionRow } from '../types/sessions';
import type { ProjectRow, ProjectStatuslineRoot } from '../types/projects';

/** A repo as a person says it: the name it was cloned as, else its folder's. */
export const repoLabel = (root: string, remote?: string): string => repoNameOf(root, remote);

/** The sentence under a project's name. */
export function projectLede(row: ProjectRow, runningSessions = row.live_sessions): string {
  const running = runningSessions === 0 ? P.noneRunning : P.running(runningSessions);
  const teams = row.teams === 0 ? '' : ` ${P.teams(row.teams)}`;
  const today = row.turns_today === 0 ? P.noTurnsToday : row.cost_today_usd === null ? `${P.turnsToday(row.turns_today)} ${P.costUnpriced}` : `${P.turnsToday(row.turns_today)} ${P.costToday(fmtUsd(row.cost_today_usd))}`;
  return `${running}${teams}. ${today}`;
}

export const costText = (row: ProjectRow): string => (row.cost_today_usd === null ? ABSENT : fmtUsd(row.cost_today_usd));

/** Commands that find the repo under the same trusted folder, as one line each: eleven commands under one home folder are one fact, not eleven. The folder's path is kept apart, for the page to show on request. */
export function rootGroups(
  entries: readonly ProjectStatuslineRoot[],
  labelOf: (head: string) => string,
): { names: string[]; kind: string; root: string | null }[] {
  const groups = new Map<string, { names: string[]; kind: string; root: string | null }>();
  for (const entry of entries) {
    const kind = entry.root === null ? P.noBranch : entry.entry === null ? '' : P.trusted[entry.entry];
    const key = `${entry.root ?? ''}\n${kind}`;
    const held = groups.get(key);
    if (held === undefined) groups.set(key, { names: [labelOf(entry.head)], kind, root: entry.root });
    else held.names.push(labelOf(entry.head));
  }
  return [...groups.values()];
}

/** The sessions running in the repo now, by the name the console gives them everywhere else. */
export const sessionsIn = (rows: readonly SessionRow[], root: string): SessionRow[] => rows.filter((row) => row.availability !== 'gone' && row.repo?.root === root);
export const liveNames = (rows: readonly SessionRow[], root: string): string[] => sessionsIn(rows, root).map(sessionLabel);

// ── the standing prompt and rule ─────────────────────────────────────────────────────────────────

type Table = Record<string, unknown>;

/** What splice.toml holds for one repo: each field's inline text ('' when unset), or the file it is read from instead. */
export interface Standing {
  prompt: string;
  promptFile: string | null;
  compaction: string;
  compactionFile: string | null;
}

/** The two texts a save writes; '' removes the key. */
export interface StandingEdit {
  prompt: string;
  compaction: string;
}

const tableOf = (value: unknown): Table => (typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Table) : {});
const textOf = (value: unknown): string | null => (typeof value === 'string' ? value : null);
const without = (table: Table, key: string): Table => Object.fromEntries(Object.entries(table).filter(([name]) => name !== key));
/** A copy of [table] with [key] set to [value], or without it when [value] is '': an absent prompt leaves the file as the operator wrote it. */
const withKey = (table: Table, key: string, value: string): Table => (value === '' ? without(table, key) : { ...table, [key]: value });

/** Every [[compaction.project]] row, in the file's order. */
function projectRules(topology: Table): Table[] {
  const rows = tableOf(topology['compaction'])['project'];
  return Array.isArray(rows) ? rows.map(tableOf) : [];
}

/** The repo's own rule: its path, and no model (a model's row is a narrower rule this form leaves). */
const isOwnRule = (root: string) => (row: Table): boolean => row['path'] === root && row['model'] === undefined;

export function standingOf(topology: Table, root: string): Standing {
  const project = tableOf(tableOf(topology['projects'])[root]);
  const rule = projectRules(topology).find(isOwnRule(root)) ?? {};
  return { prompt: textOf(project['system_prompt']) ?? '', promptFile: textOf(project['system_prompt_file']), compaction: textOf(rule['instructions']) ?? '', compactionFile: textOf(rule['file']) };
}

function withPrompt(topology: Table, root: string, prompt: string): Table {
  const projects = tableOf(topology['projects']);
  const project = withKey(tableOf(projects[root]), 'system_prompt', prompt);
  const all = Object.keys(project).length === 0 ? without(projects, root) : { ...projects, [root]: project };
  return Object.keys(all).length === 0 ? without(topology, 'projects') : { ...topology, projects: all };
}

function withRule(topology: Table, root: string, instructions: string): Table {
  const rows = projectRules(topology);
  const at = rows.findIndex(isOwnRule(root));
  const own = withKey(rows[at] ?? { path: root }, 'instructions', instructions);
  // A row left with its path alone says nothing, so it goes rather than stay as an empty rule.
  const kept = Object.keys(own).some((key) => key !== 'path') ? [own] : [];
  const project = at === -1 ? [...rows, ...kept] : [...rows.slice(0, at), ...kept, ...rows.slice(at + 1)];
  const compaction = project.length === 0 ? without(tableOf(topology['compaction']), 'project') : { ...tableOf(topology['compaction']), project };
  return Object.keys(compaction).length === 0 ? without(topology, 'compaction') : { ...topology, compaction };
}

/** [topology] with the repo's two fields set to [next]; a file-backed field is left as it is (inline text beside a file is
 *  refused by the daemon). Never mutates its input. */
export function withStanding(topology: Table, root: string, next: StandingEdit): Table {
  const held = standingOf(topology, root);
  const prompted = held.promptFile === null ? withPrompt(topology, root, next.prompt) : topology;
  return held.compactionFile === null ? withRule(prompted, root, next.compaction) : prompted;
}

/** The topology keys a standing save touches, for the restart note. */
export const standingKeys = (root: string): string[] => [`projects.${root}.system_prompt`, 'compaction.project[].instructions'];
