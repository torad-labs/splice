// The opened project: its own row (GET /api/projects/{id}), read while its detail is open, and what
// governs the repo from that same row.
//
// The list already carries these counts, and the detail reads them again on purpose: the detail
// route is the daemon's answer for THIS root alone (ProjectsRoutes.project), it moves while the
// detail is open, and a root the daemon no longer knows is a 404 whose sentence the operator should
// read rather than a row that silently stops updating. It also carries what the table has no column
// for: the day the counts start at, which the daemon sends rather than letting the console guess a
// boundary. Until that read lands, the list's own row for the same id stands in: it is the same
// shape (ProjectView.row), never another project's answer.
//
// Below it, what governs the repo: the compaction rules a compaction here resolves to and, per head,
// the trusted root its statusline probes the repo under. Both are pure over the row, so a test
// renders them from data.
import { useEffect } from 'react';
import { HeadMark } from '@entities/control-status';
import { startProjectPolling, useProject } from '@entities/project';
import type { ProjectCompactionRule, ProjectRow, ProjectStatuslineRoot, TrustedRootEntry } from '@entities/project';
import { CompactionRules } from '@widgets/compaction-rule';
import { Fault } from '@shared/controls';
import { timeAgo } from '@shared/lib';
import { Badge, DataTable, Empty } from '@shared/ui';
import type { Column } from '@shared/ui';
import { H, S } from './strings';

/** The UTC day the daemon counted (ProjectsRoutes: "TODAY IS THE UTC DAY"), printed as that day
 *  and that zone, so it never reads as the reader's local midnight. */
export function dayText(dayStart: number): string {
  return `${new Date(dayStart).toISOString().slice(0, 10)} UTC`;
}

/** USD, two places; null when no head that ran here declares rates, which is no figure, never $0. */
export function costText(usd: number | null): string {
  return usd === null ? S.absent : `$${usd.toFixed(2)}`;
}

/** The opened project's activity, as label and value pairs. Pure, so a test reads them. */
export function detailRowsOf(row: ProjectRow, now = Date.now()): [string, string][] {
  return [
    [S.running, String(row.live_sessions)],
    [S.teams, String(row.teams)],
    [S.turns, String(row.turns_today)],
    [S.cost, costText(row.cost_today_usd)],
    [S.day, dayText(row.day_start)],
    [S.last, row.last_activity === null ? S.absent : timeAgo(row.last_activity, now)],
  ];
}

/** The opened project's row: the sample's own when a fixture fed the page (nothing is read), else
 *  the detail route's answer for THIS id, polled while it is open, else the list's row for it. */
export function useOpenProject(open: ProjectRow | null, sample: boolean): {
  row: ProjectRow | null;
  error: string | null;
  lastRead: number | null;
} {
  const state = useProject((s) => s);
  const id = open?.id ?? null;
  useEffect(() => (id === null || sample ? undefined : startProjectPolling(id)), [id, sample]);
  if (open === null || sample) return { row: open, error: null, lastRead: null };
  const read = state.data !== null && state.data.id === open.id ? state.data : null;
  return { row: read ?? open, error: state.error, lastRead: state.lastUpdated };
}

/** Something is running in the repo now, or nothing is. */
export function StateBadge({ row }: { row: ProjectRow }) {
  return row.live_sessions > 0 ? <Badge tone="ok" quiet>{S.busy}</Badge> : <Badge tone="neutral" quiet>{S.quiet}</Badge>;
}

// ── what governs the repo (FEATURES.md 4.14: "its compaction scope and the effective instructions,
// the statusline roots entry") ───────────────────────────────────────────────────────────────────

export type CompactionView =
  | { kind: 'unwired' }
  | { kind: 'client' }
  | { kind: 'rules'; rules: ProjectCompactionRule[] };

/** The three different facts the row's `compaction` can carry, kept apart: no table wired is the
 *  daemon's failure to report, and printing "no rule" there would be a confident false negative. */
export function compactionViewOf(row: ProjectRow): CompactionView {
  if (row.compaction === null) return { kind: 'unwired' };
  return row.compaction.length === 0 ? { kind: 'client' } : { kind: 'rules', rules: row.compaction };
}

export function ProjectCompaction({ row }: { row: ProjectRow }) {
  const view = compactionViewOf(row);
  if (view.kind === 'unwired') return <Fault message={H.unwired} />;
  if (view.kind === 'client') return <Empty text={S.noRule} source={H.clientOwn} />;
  return <CompactionRules rules={view.rules} />;
}

/** The entry of a head's trusted set that covers the repo, in words: the home directory, the temp
 *  directory, or a root the operator listed in `statuslineGitRoots`. */
const ENTRY: Record<TrustedRootEntry, string> = { home: S.home, tmp: S.tmp, statuslineGitRoots: S.gitRoots };

/** One entry per head: the root that covers the repo and, as a badge, which trusted entry it is,
 *  or that none covers it and the statusline shows no branch here. */
export function ProjectStatusline({ row }: { row: ProjectRow }) {
  if (row.statusline_roots.length === 0) {
    return <Empty text={S.noHeads} source={H.noHeads} action={<a href="#/settings">{S.settings}</a>} />;
  }
  const columns: Column<ProjectStatuslineRoot>[] = [
    { key: 'head', label: S.head, width: '30%', cell: (entry) => <HeadMark head={entry.head} /> },
    { key: 'root', label: S.root, mono: true, wrap: true, cell: (entry) => entry.root ?? S.absent },
    {
      key: 'trust',
      label: S.trust,
      width: '28%',
      cell: (entry) => (entry.root === null || entry.entry === null
        ? <Badge tone="neutral" quiet>{S.untrusted}</Badge>
        : <Badge tone="ok" quiet>{ENTRY[entry.entry]}</Badge>),
    },
  ];
  return <DataTable columns={columns} rows={row.statusline_roots} rowKey={(entry) => entry.head} label={S.statusline} />;
}
