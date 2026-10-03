// Project membership comes from the registry's resolved common root, never from a name or a saved team.
import type { HandedEdge, SessionEdge, SessionRow } from '../types/sessions';
import type { TurnRow } from '../types/perf';

export interface ProjectTeam {
  root: string;
  remote?: string;
  sessions: SessionRow[];
}

/** The daemon's common root wins; its reported working folder is the explicit fallback used by Sessions too. */
export const projectRoot = (row: SessionRow): string | null => {
  const root = row.repo?.root || row.cwd;
  return root == null || root === '' ? null : root;
};

export function projectTeams(rows: readonly SessionRow[]): { groups: ProjectTeam[]; unresolved: SessionRow[] } {
  const groups = new Map<string, ProjectTeam>();
  const unresolved: SessionRow[] = [];
  for (const row of rows) {
    if (row.availability === 'gone') continue;
    const root = projectRoot(row);
    if (root === null) {
      unresolved.push(row);
      continue;
    }
    const group = groups.get(root) ?? { root, sessions: [] };
    if (row.repo?.remote !== undefined) group.remote ??= row.repo.remote;
    group.sessions.push(row);
    groups.set(root, group);
  }
  return { groups: [...groups.values()].sort((a, b) => b.sessions.length - a.sessions.length || a.root.localeCompare(b.root)), unresolved };
}

/** Only full session attribution can assign recorded request usage to a project. */
export function projectUsage(sessions: readonly SessionRow[], records: readonly TurnRow[]) {
  const ids = new Set(sessions.map(row => row.session_id).filter(id => id !== null));
  const rows = records.filter(row => row.local_step !== 1 && row.session_id !== undefined && ids.has(row.session_id));
  const models: Record<string, { head: string; model: string }> = {};
  const newest = new Map<string, number>();
  let input: number | null = null;
  let output: number | null = null;
  let cost: number | null = null;
  let unpriced = 0;
  let missingInput = 0;
  let missingOutput = 0;
  for (const row of rows) {
    if (row.in_tokens === undefined) missingInput++;
    else input = (input ?? 0) + row.in_tokens;
    if (row.out_tokens === undefined) missingOutput++;
    else output = (output ?? 0) + row.out_tokens;
    if (row.cost_usd == null) unpriced++;
    else cost = (cost ?? 0) + row.cost_usd;
    const id = row.session_id;
    if (id !== undefined && row.compact !== true && row.model !== null && row.ts >= (newest.get(id) ?? -Infinity)) {
      models[id] = { head: row.head, model: row.model };
      newest.set(id, row.ts);
    }
  }
  return { requests: rows.length, input, output, cost, unpriced, missingInput, missingOutput, models };
}

/** Addresses and unresolved names are recipient selectors, not sender session ids. */
function recipient(rows: readonly SessionRow[], to: string): SessionRow | null {
  const addressed = rows.filter(row => row.address !== null && row.address === to);
  const matches = addressed.length > 0 ? addressed : rows.filter(row => row.name !== null && row.name === to);
  return matches.length === 1 ? matches[0] ?? null : null;
}

/** Only observed messages between members, deduplicated across the sender's and recipient's edge lists. */
export function projectHandoffs(rows: readonly SessionRow[], board: Readonly<Record<string, SessionEdge[]>>): SessionEdge[] {
  const handoffs = new Map<string, SessionEdge>();
  for (const row of rows) {
    if (row.session_id === null) continue;
    for (const edge of board[row.session_id] ?? []) {
      const sender = rows.find(member => member.session_id === edge.from);
      const receiver = recipient(rows, edge.to);
      if (sender === undefined || receiver === null) continue;
      const key = JSON.stringify([edge.from, receiver.session_id ?? receiver.pid, edge.at]);
      const held = handoffs.get(key);
      if (held === undefined || edge.direction === 'out') handoffs.set(key, { ...edge, direction: 'out' });
    }
  }
  return [...handoffs.values()].sort((a, b) => b.at - a.at);
}

export const handoffText = (edge: SessionEdge, messages: readonly HandedEdge[]): HandedEdge | null =>
  messages.find(message => message.from === edge.from && message.to === edge.to && message.at === edge.at) ?? null;
