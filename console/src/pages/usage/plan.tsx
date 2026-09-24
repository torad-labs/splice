// The plan limits bay: every head's own 5h and 7d plan windows, from /api/usage `quota`.
//
// This is the number the operator hunts for before a long session ("which login still has room"),
// and until 2026-09-24 no page printed it for a single-login head: the accounts route fills its
// windows only for pooled accounts, and the rule bar read `warn`, which ignores the plan windows.
//
// A window whose reset time has passed is printed as reset, never as its old figure: splice reads a
// window from a turn's response headers or the usage poll, so a head that has not run since its
// window reset still holds the figure from before it (claude-muse read 99% of a 7d window that had
// reset 3.7 days earlier, live /api/usage 2026-09-24).
import type { ReactNode } from 'react';
import { planLevel, planWindows, resetsInText } from '@entities/usage';
import type { PlanWindow } from '@entities/usage';
import type { HeadUsageEntry, UsagePayload } from '@shared/api';
import { ABSENT, timeAgo } from '@shared/lib';
import { Bay, Strip, StripField } from '@shared/ui';
import type { Edge } from '@shared/ui';
import { Blank } from '@shared/controls';
import { EMPTIES } from './model';
import { S } from './strings';

/** The rack's widths in ch, shared by the name row and the cells (see ColumnNames in index.tsx). */
export const PLAN_COLS = [18, 8, 9, 14, 9, 14, 10] as const;

interface PlanRow {
  entry: HeadUsageEntry;
  windows: PlanWindow[];
  /** The fullest window that has not reset, or null when every window has. */
  live: PlanWindow | null;
}

/** The heads that track a plan window, fullest live window first, then by key. */
export function planRows(usage: UsagePayload, nowMs: number): PlanRow[] {
  const rows: PlanRow[] = [];
  for (const entry of usage.heads) {
    const windows = planWindows(entry.usage, nowMs);
    if (windows.length === 0) continue;
    const live = windows.filter((window) => !window.stale)
      .reduce<PlanWindow | null>((held, next) => (held === null || next.pct > held.pct ? next : held), null);
    rows.push({ entry, windows, live });
  }
  return rows.sort((left, right) => (right.live?.pct ?? -1) - (left.live?.pct ?? -1) || left.entry.key.localeCompare(right.entry.key));
}

/** The edge for a row: the fullest live window's level, grey when every window has reset. */
export function planEdge(row: PlanRow, warnPct: number): { edge: Edge; label: string } {
  if (row.live === null) return { edge: 'grey', label: S.stale };
  const level = planLevel(row.live.pct, warnPct);
  return { edge: level === 'critical' ? 'red' : level === 'warn' ? 'amber' : 'green', label: `${row.live.pct}%` };
}

/** One window's two cells: how much is used and when it resets. */
export function windowCells(window: PlanWindow | undefined, nowMs: number): { used: string; resets: string } {
  if (window === undefined) return { used: ABSENT, resets: ABSENT };
  if (window.stale) return { used: S.unknown, resets: S.alreadyReset };
  return { used: `${window.pct}%`, resets: resetsInText(window.resetsAt, nowMs) ?? ABSENT };
}

/** When splice last read the head's windows: the newest reading across them. */
export function readText(windows: readonly PlanWindow[], nowMs: number): string {
  const newest = windows.reduce<number | null>(
    (held, window) => (window.observedAt !== null && (held === null || window.observedAt > held) ? window.observedAt : held),
    null,
  );
  return newest === null ? ABSENT : timeAgo(newest * 1000, nowMs);
}

function PlanStrip({ row, warnPct, now }: { row: PlanRow; warnPct: number; now: number }) {
  const { edge, label } = planEdge(row, warnPct);
  const five = windowCells(row.windows.find((window) => window.window === '5h'), now);
  const seven = windowCells(row.windows.find((window) => window.window === '7d'), now);
  return (
    <Strip edge={edge} edgeLabel={label} ariaLabel={`${S.planLimits} ${row.entry.label}`}>
      <StripField w={PLAN_COLS[0]} value={row.entry.label} mono={false} />
      <StripField w={PLAN_COLS[1]} value={row.entry.usage?.quota?.plan ?? ABSENT} mono={false} />
      <StripField w={PLAN_COLS[2]} value={five.used} />
      <StripField w={PLAN_COLS[3]} value={five.resets} mono={false} />
      <StripField w={PLAN_COLS[4]} value={seven.used} />
      <StripField w={PLAN_COLS[5]} value={seven.resets} mono={false} />
      <StripField w={PLAN_COLS[6]} value={readText(row.windows, now)} />
    </Strip>
  );
}

export function PlanBay({ usage, now, names }: { usage: UsagePayload | null; now: number; names: ReactNode }) {
  if (usage === null) return <Blank strips={2} />;
  const rows = planRows(usage, now);
  return (
    <Bay
      label={S.planLimits}
      count={rows.length}
      empty={{ text: EMPTIES.noPlan.text, source: EMPTIES.noPlan.source }}
      fields={names}
    >
      {rows.map((row) => <PlanStrip key={row.entry.key} row={row} warnPct={usage.warn_pct} now={now} />)}
    </Bay>
  );
}
