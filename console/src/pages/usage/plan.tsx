// The plan limits: every head's own 5h and 7d plan windows, from /api/usage `quota`, one card per
// head with each window's share large, its meter and its reset.
//
// This is the number the operator hunts for before a long session ("which login still has room"),
// and until 2026-09-24 no page printed it for a single-login head: the accounts route fills its
// windows only for pooled accounts, and the rule bar read `warn`, which ignores the plan windows.
//
// A window whose reset time has passed is printed as reset, never as its old figure: splice reads a
// window from a turn's response headers or the usage poll, so a head that has not run since its
// window reset still holds the figure from before it (claude-muse read 99% of a 7d window that had
// reset 3.7 days earlier, live /api/usage 2026-09-24).
import { TimerIcon } from '@phosphor-icons/react/dist/csr/Timer';
import { HeadMark } from '@entities/control-status';
import { planLevel, planWindows, resetsInText } from '@entities/usage';
import type { PlanWindow } from '@entities/usage';
import type { HeadUsageEntry, UsagePayload } from '@shared/api';
import { ABSENT, timeAgo } from '@shared/lib';
import { Badge, Empty, Meter, Section } from '@shared/ui';
import type { Tone } from '@shared/ui';
import { Blank } from '@shared/controls';
import { H, S, U } from './strings';

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

/** A window's tone at its share: danger at the critical line, warn past the daemon's warn line,
 *  and the neutral ink below both (DESIGN.md section 7: the word says it, the colour repeats it). */
export function windowTone(pct: number, warnPct: number): Tone {
  const level = planLevel(pct, warnPct);
  return level === 'critical' ? 'danger' : level === 'warn' ? 'warn' : 'neutral';
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

/** One window as the card prints it: its name, its share large, the meter, and when it resets. */
function WindowFigure({ label, window, warnPct, now }: { label: string; window: PlanWindow | undefined; warnPct: number; now: number }) {
  const cells = windowCells(window, now);
  const tone = window === undefined || window.stale ? 'neutral' : windowTone(window.pct, warnPct);
  return (
    <div className={`myx-plan-window myx-plan-${tone}`}>
      <p className="myx-plan-label">{label}</p>
      <p className="myx-plan-pct">{cells.used}</p>
      <Meter value={window === undefined || window.stale ? 0 : window.pct / 100} tone={tone} label={`${label} ${cells.used}`} />
      <p className="myx-plan-resets">
        {window === undefined ? ABSENT : window.stale ? S.alreadyReset : (
          <><TimerIcon className="myx-plan-glyph" aria-label={U.resets} />{cells.resets}</>
        )}
      </p>
    </div>
  );
}

/** One head's plan: its mark, its plan name, when splice read it, and each window side by side, the
 *  shape claude-code-router gives a provider's several quotas (DESIGN.md section 7). */
function PlanCard({ row, warnPct, now }: { row: PlanRow; warnPct: number; now: number }) {
  return (
    <article className="myx-plan" aria-label={`${S.planLimits} ${row.entry.label}`}>
      <header className="myx-plan-head">
        <HeadMark head={row.entry.key}>{row.entry.label}</HeadMark>
        {row.entry.usage?.quota?.plan === undefined ? null : <Badge tone="neutral">{row.entry.usage.quota.plan}</Badge>}
        <span className="myx-plan-read">{`${S.read} ${readText(row.windows, now)}`}</span>
      </header>
      <div className="myx-plan-windows">
        <WindowFigure label={S.fiveWindow} window={row.windows.find((window) => window.window === '5h')} warnPct={warnPct} now={now} />
        <WindowFigure label={S.sevenWindow} window={row.windows.find((window) => window.window === '7d')} warnPct={warnPct} now={now} />
      </div>
    </article>
  );
}

export function PlanBay({ usage, error = null, now }: {
  usage: UsagePayload | null;
  /** The read's failure, which the page prints as a fault: a failed read is not still loading. */
  error?: string | null;
  now: number;
}) {
  if (usage === null) return error === null ? <Blank strips={2} /> : null;
  const rows = planRows(usage, now);
  return (
    <Section title={S.planLimits} count={rows.length}>
      {rows.length === 0 ? (
        <Empty text={S.noPlan} source={H.noPlan} />
      ) : (
        <div className="myx-plans">
          {rows.map((row) => <PlanCard key={row.entry.key} row={row} warnPct={usage.warn_pct} now={now} />)}
        </div>
      )}
    </Section>
  );
}
