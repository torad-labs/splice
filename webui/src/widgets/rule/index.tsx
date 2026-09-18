// The fixed rule: who the console is, what time it is, whether the daemon is
// answering, whether a saved knob is still waiting for a restart, and the plan
// window closest to running out. It never scrolls and it never guesses: every
// figure carries its basis, and a window no head reports says so instead of
// reading zero.
//
// The rule reads entities through their public exports and owns nothing: the
// only state it keeps is the clock, and the only reads it starts are the ones
// nothing else starts for it.
//
// The window derivation is NOT computed here. It arrives from @entities/usage
// (M2-01), which is the slice that owns /api/usage: this widget used to carry
// its own copy, and two implementations of "nearest window" is one more than can
// stay in agreement. The cells are exported so a test can render them from
// payloads rather than from the stores (a static render sees a store's initial
// state and never its current one).
import { useEffect, useState } from 'react';
import { fetchControlStatus, useControlStatus } from '@entities/control-status';
import { useHeads } from '@entities/heads';
import { startAuthPolling, useAuth } from '@entities/auth';
import { headsReportingNone, nearestWindow, startUsagePolling, useUsage } from '@entities/usage';
import { useRestartPending } from '@entities/config';
import { connect, useEvents } from '@entities/events';
import type { ConnectionStatus } from '@entities/events';
import { timeAgo } from '@shared/lib';
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
export type HealthState = 'green' | 'amber' | 'red';

export function healthOf(statusFailed: boolean, anyHeadDown: boolean): HealthState {
  if (statusFailed) return 'red';
  return anyHeadDown ? 'amber' : 'green';
}

/** The plan window nearest exhaustion, at its reported length. */
export function WindowCell({ usage, auth }: { usage: UsagePayload | null; auth: AuthPayload | null }) {
  const nearest = nearestWindow(usage, auth);
  return (
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
  );
}

/**
 * How the live connection is doing, beside health: green while a stream is open, amber while it is
 * between attempts, grey when there is none (no management key yet, or a stale one).
 *
 * The age of the last FRAME is printed beside it, and its BASIS carries the distinction the age
 * alone cannot: a quiet daemon and a dead stream both leave an old frame behind, and only the
 * state word tells them apart. The basis turns `stale` at the same 15 s the console uses
 * everywhere else, so an old number never reads as a fresh one.
 */
export function ConnectionCell({ status, lastFrameAt }: { status: ConnectionStatus; lastFrameAt: number | null }) {
  const edge = status === 'live' ? 'green' : status === 'reconnecting' ? 'amber' : 'grey';
  const word = status === 'live' ? S.live : status === 'reconnecting' ? S.reconnecting : S.off;
  return (
    <p className="myx-rule-cell myx-rule-connection">
      <HolderEdge state={edge} label={word} />
      {lastFrameAt === null ? (
        <span className="myx-rule-absent">no frame yet</span>
      ) : (
        <Figure value={timeAgo(lastFrameAt)} basis={Date.now() - lastFrameAt < 15_000 ? 'measured' : 'stale'} />
      )}
    </p>
  );
}

/** How many heads report no window at all. Null until the route answers. */
export function NoneCell({ usage }: { usage: UsagePayload | null }) {
  const none = headsReportingNone(usage);
  // Nothing at all until the route answers: an empty cell would take a slot in the rule's grid and
  // read as a readout that is present and blank, which is the one thing this bar never does.
  if (none === null) return null;
  return (
    <p className="myx-rule-cell myx-rule-none">
      <Figure value={none} basis="measured" />
      <span className="myx-rule-word">{S.noneTail}</span>
    </p>
  );
}

/**
 * Knobs that were saved and are not in force yet: the daemon snapshots every
 * knob except three at start, so a saved restart-only value does nothing until
 * the daemon restarts, and the console must not let that read as "applied".
 *
 * The cell is the same gesture as everywhere else in this world - a holder edge
 * that cocks, with a printed label and the count of pending keys as a figure. It
 * reads the store a page's save left behind, so the rule starts no route and no
 * poll of its own; when the store clears (what a restart does to it, and the
 * only thing that honestly can) the cell is gone.
 */
export function PendingRestartCell({ pending }: { pending: readonly string[] }) {
  if (pending.length === 0) return null;
  return (
    <p className="myx-rule-cell myx-rule-pending">
      <HolderEdge state="amber" label={S.restartPending} />
      <Figure value={pending.length} basis="measured" />
    </p>
  );
}

export function Rule() {
  const status = useControlStatus((state) => state);
  const heads = useHeads((state) => state.data);
  const usage = useUsage((state) => state.data);
  const auth = useAuth((state) => state.data);
  const pendingRestart = useRestartPending((state) => state.pending);
  const connection = useEvents((state) => state);
  const { local, utc } = useClock();

  useEffect(() => {
    // The daemon's identity and registry are near-static; the two routes the
    // readout needs are polled, because the rule is chrome and outlives every
    // page it is drawn over.
    void fetchControlStatus();
    const stops = [startUsagePolling(15_000), startAuthPolling(30_000)];
    // The live stream is opened here because the rule is the chrome that outlives every page and
    // the surface that prints the connection; connect() is idempotent, so whoever else asks for it
    // gets the same one stream.
    connect();
    return () => stops.forEach((stop) => stop());
  }, []);

  const anyHeadDown = heads !== null && heads.some((head) => !head.running || !head.healthy);
  const health = healthOf(status.error !== null, anyHeadDown);

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

      <ConnectionCell status={connection.status} lastFrameAt={connection.lastFrameAt} />

      <PendingRestartCell pending={pendingRestart} />

      <WindowCell usage={usage} auth={auth} />

      <NoneCell usage={usage} />
    </header>
  );
}
