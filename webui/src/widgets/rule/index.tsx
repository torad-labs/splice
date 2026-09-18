// The fixed rule: who the console is, what time it is, whether the daemon is
// answering, and the plan window closest to running out. It never scrolls and
// it never guesses: every figure carries its basis, and a window no head
// reports says so instead of reading zero.
//
// The rule reads entities through their public exports and owns nothing: the
// only state it keeps is the clock, and the only reads it starts are the ones
// nothing else starts for it.
import { useEffect, useState } from 'react';
import { fetchControlStatus, useControlStatus } from '@entities/control-status';
import { useHeads } from '@entities/heads';
import { startAuthPolling, useAuth } from '@entities/auth';
import { startUsagePolling, useUsage } from '@entities/usage';
import { Figure, HolderEdge } from '@shared/ui';
import type { AuthPayload, UsagePayload } from '@shared/api';
import { S } from './strings';
import './rule.css';

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS, local or UTC. The rule prints both, the way the strip bay does. */
export function clockText(epochMs: number, utc: boolean): string {
  const at = new Date(epochMs);
  const hours = utc ? at.getUTCHours() : at.getHours();
  const minutes = utc ? at.getUTCMinutes() : at.getMinutes();
  const seconds = utc ? at.getUTCSeconds() : at.getSeconds();
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
}

function useClock(): { local: string; utc: string } {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  return { local: clockText(now, false), utc: clockText(now, true) };
}

/**
 * The daemon's health, from the two things it says about itself: whether it
 * answered at all (the status read), and whether every head it registers is
 * running and healthy (`/api/heads`). A head that is down is the daemon
 * degraded, not the console guessing.
 */
type HealthState = 'green' | 'amber' | 'red';

function healthOf(statusFailed: boolean, anyHeadDown: boolean): HealthState {
  if (statusFailed) return 'red';
  return anyHeadDown ? 'amber' : 'green';
}

/** The plan window nearest exhaustion, at its reported length. */
export interface NearestWindow {
  head: string;
  /** The account's masked id or login, where the head reports one. */
  account: string | null;
  window: string;
  pct: number;
  reset: string | null;
}

/**
 * One window, from the two routes that carry one: /api/usage names the head and
 * its percentage, /api/auth names the account behind that head.
 *
 * A head whose warn source is `none` has no window to be nearest, so it is not
 * a candidate — an absence cannot be close to exhaustion.
 */
export function nearestWindowOf(usage: UsagePayload | null, auth: AuthPayload | null): NearestWindow | null {
  if (usage === null) return null;
  let best: NearestWindow | null = null;
  for (const entry of usage.heads) {
    const head = entry.usage;
    if (head === null || head.warn.source === 'none') continue;
    if (best !== null && head.warn.pct <= best.pct) continue;
    const card = auth?.[entry.key];
    best = {
      head: entry.key,
      account: card?.account_id_masked ?? card?.login ?? null,
      window: `${usage.window_hours}h`,
      pct: head.warn.pct,
      reset: head.warn.reset,
    };
  }
  return best;
}

/** How many heads report no window at all. Null until the route answers. */
export function headsReportingNone(usage: UsagePayload | null): number | null {
  if (usage === null) return null;
  return usage.heads.filter((entry) => entry.usage === null || entry.usage.warn.source === 'none').length;
}

export function Rule() {
  const status = useControlStatus((state) => state);
  const heads = useHeads((state) => state.data);
  const usage = useUsage((state) => state.data);
  const auth = useAuth((state) => state.data);
  const { local, utc } = useClock();

  useEffect(() => {
    // The daemon's identity and registry are near-static; the two routes the
    // readout needs are polled, because the rule is chrome and outlives every
    // page it is drawn over.
    void fetchControlStatus();
    const stops = [startUsagePolling(15_000), startAuthPolling(30_000)];
    return () => stops.forEach((stop) => stop());
  }, []);

  const anyHeadDown = heads !== null && heads.some((head) => !head.running || !head.healthy);
  const health = healthOf(status.error !== null, anyHeadDown);
  const nearest = nearestWindowOf(usage, auth);
  const none = headsReportingNone(usage);

  return (
    <header className="myx-rule">
      <h1 className="myx-rule-cell myx-rule-wordmark">{S.wordmark}</h1>

      <p className="myx-rule-cell myx-rule-clocks">
        <span className="myx-rule-clock">{local}</span>
        <span className="myx-rule-clock-word">{S.local}</span>
        <span className="myx-rule-clock">{utc}</span>
        <span className="myx-rule-clock-word">{S.utc}</span>
      </p>

      <div className="myx-rule-cell myx-rule-health">
        <HolderEdge state={health} label={S.health[health]} />
      </div>

      <p className="myx-rule-cell myx-rule-window">
        <span className="myx-rule-word">{S.nearest}</span>
        {nearest === null ? (
          <span className="myx-rule-absent">no window reported</span>
        ) : (
          <>
            <span className="myx-rule-head">{nearest.head}</span>
            {nearest.account !== null ? <span className="myx-rule-account">{nearest.account}</span> : null}
            <span className="myx-rule-period">{nearest.window}</span>
            <Figure value={nearest.pct} unit="%" basis="measured" />
            {nearest.reset !== null ? <span className="myx-rule-reset">resets {nearest.reset}</span> : null}
          </>
        )}
      </p>

      <p className="myx-rule-cell myx-rule-none">
        {none === null ? null : (
          <>
            <Figure value={none} basis="measured" />
            <span className="myx-rule-word">{S.noneTail}</span>
          </>
        )}
      </p>
    </header>
  );
}
