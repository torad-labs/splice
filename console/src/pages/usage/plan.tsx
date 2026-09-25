// The plan limits: every login's own plan windows, one card per head and account, with each
// window's share large, its meter and its reset.
//
// This is the number the operator hunts for before a long session ("which login still has room"),
// and until 2026-09-24 no page printed it for a single-login head: the accounts route fills its
// windows only for pooled accounts, and the rule bar read `warn`, which ignores the plan windows.
//
// TWO ROUTES CARRY WINDOWS, READ BY THE SAME RULE AS THE STATUS STRIP (features/nearest-limit): a
// head that rides account rows is read from the rows, which hold every account of its pool, one card
// per account named by its label; /api/usage reports only a head's SELECTED account, so reading it
// for a pooled head showed one unnamed login's 21% under a strip naming another's 64% (demo stack,
// 2026-09-25). A head no account row reports windows for keeps its own /api/usage card.
//
// A window whose reset time has passed is printed as reset, never as its old figure: splice reads a
// window from a turn's response headers or the usage poll, so a head that has not run since its
// window reset still holds the figure from before it (claude-muse read 99% of a 7d window that had
// reset 3.7 days earlier, live /api/usage 2026-09-24).
import { TimerIcon } from '@phosphor-icons/react/dist/csr/Timer';
import { HeadMark } from '@entities/control-status';
import { isStale, slotWindows, windowLengthText } from '@entities/account';
import type { AccountRow, AccountWindow } from '@entities/account';
import { planLevel, planWindows, resetsInText } from '@entities/usage';
import type { PlanWindow } from '@entities/usage';
import type { UsagePayload } from '@shared/api';
import { ABSENT, timeAgo } from '@shared/lib';
import { Badge, Empty, Meter, Section } from '@shared/ui';
import type { Tone } from '@shared/ui';
import { Blank } from '@shared/controls';
import { H, S, U } from './strings';

/** A window as a card prints it: its slot, the length it reports (Grok's long window is 30d, never
 *  assumed from the slot), and its reading. */
export interface CardWindow extends PlanWindow {
  length: string;
}

export interface PlanRow {
  key: string;
  /** The head the login serves, for its mark. */
  head: string;
  label: string;
  /** The pool's name for the login, when the card is read from an account row. */
  account: string | null;
  plan: string | null;
  windows: CardWindow[];
  /** The fullest window that has not reset, or null when every window has. */
  live: CardWindow | null;
}

/** An account row's slot window as a card window; null when the slot holds none or the provider
 *  reported no usage for it. */
function accountWindow(window: AccountWindow | null, slot: PlanWindow['window'], observedAt: number | null, nowMs: number): CardWindow | null {
  if (window === null || window.used_percent === null) return null;
  return {
    window: slot,
    length: windowLengthText(window.seconds),
    pct: window.used_percent,
    resetsAt: window.reset_epoch_seconds,
    observedAt,
    stale: isStale(window, nowMs),
  };
}

const liveOf = (windows: readonly CardWindow[]): CardWindow | null => windows.filter((window) => !window.stale)
  .reduce<CardWindow | null>((held, next) => (held === null || next.pct > held.pct ? next : held), null);

/** Every login that tracks a plan window, fullest live window first, then by key. */
export function planRows(usage: UsagePayload, accounts: readonly AccountRow[], nowMs: number): PlanRow[] {
  const rows: PlanRow[] = [];
  const labelOf = (head: string): string => usage.heads.find((entry) => entry.key === head)?.label ?? head;
  const pooled = new Set<string>();
  for (const account of accounts) {
    const { short, long } = slotWindows(account);
    const observedAt = account.observed_at_epoch_seconds ?? null;
    const windows = [accountWindow(short, '5h', observedAt, nowMs), accountWindow(long, '7d', observedAt, nowMs)]
      .filter((window): window is CardWindow => window !== null);
    if (windows.length === 0) continue;
    for (const head of account.heads) {
      pooled.add(head);
      rows.push({
        key: `${head}:${account.label ?? account.kind}`,
        head,
        label: labelOf(head),
        account: account.label,
        plan: account.plan ?? null,
        windows,
        live: liveOf(windows),
      });
    }
  }
  for (const entry of usage.heads) {
    if (pooled.has(entry.key)) continue;
    const windows = planWindows(entry.usage, nowMs).map((window) => ({ ...window, length: window.window }));
    if (windows.length === 0) continue;
    rows.push({ key: entry.key, head: entry.key, label: entry.label, account: null, plan: entry.usage?.quota?.plan ?? null, windows, live: liveOf(windows) });
  }
  return rows.sort((left, right) => (right.live?.pct ?? -1) - (left.live?.pct ?? -1) || left.key.localeCompare(right.key));
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

/** One window as the card prints it: its name, its share large, the meter, and when it resets. A
 *  slot the login does not track still draws, as the absence mark, so every card has two. */
function WindowFigure({ slot, window, warnPct, now }: { slot: PlanWindow['window']; window: CardWindow | undefined; warnPct: number; now: number }) {
  const label = `${window?.length ?? slot} ${U.window}`;
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

/** One login's plan: its head's mark, the account's name, its plan, when splice read it, and each
 *  window side by side, the shape claude-code-router gives a provider's several quotas (DESIGN.md
 *  section 7). */
function PlanCard({ row, warnPct, now }: { row: PlanRow; warnPct: number; now: number }) {
  const name = row.account === null ? row.label : `${row.label} ${row.account}`;
  return (
    <article className="myx-plan" aria-label={`${S.planLimits} ${name}`}>
      <header className="myx-plan-head">
        <HeadMark head={row.head}>{row.label}</HeadMark>
        {row.account === null ? null : <span className="myx-plan-account">{row.account}</span>}
        {row.plan === null ? null : <Badge tone="neutral">{row.plan}</Badge>}
        <span className="myx-plan-read">{`${S.read} ${readText(row.windows, now)}`}</span>
      </header>
      <div className="myx-plan-windows">
        <WindowFigure slot="5h" window={row.windows.find((window) => window.window === '5h')} warnPct={warnPct} now={now} />
        <WindowFigure slot="7d" window={row.windows.find((window) => window.window === '7d')} warnPct={warnPct} now={now} />
      </div>
    </article>
  );
}

export function PlanBay({ usage, accounts = [], error = null, now }: {
  usage: UsagePayload | null;
  /** Every account row (/api/accounts, which the status strip polls on every page); empty until it
   *  answers, and then every head reads from /api/usage alone. */
  accounts?: readonly AccountRow[];
  /** The read's failure, which the page prints as a fault: a failed read is not still loading. */
  error?: string | null;
  now: number;
}) {
  if (usage === null) return error === null ? <Blank strips={2} /> : null;
  const rows = planRows(usage, accounts, now);
  return (
    <Section title={S.planLimits} count={rows.length}>
      {rows.length === 0 ? (
        <Empty text={S.noPlan} source={H.noPlan} />
      ) : (
        <div className="myx-plans">
          {rows.map((row) => <PlanCard key={row.key} row={row} warnPct={usage.warn_pct} now={now} />)}
        </div>
      )}
    </Section>
  );
}
