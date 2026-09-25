// The status strip (docs/design/DESIGN.md section 6): whether the link is live, whether the daemon is
// answering, the plan window closest to running out and when it resets, whether a saved knob is
// still waiting for a restart, and the time. It sits over every page, never scrolls and never
// guesses: a window no head reports says so instead of reading zero.
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
import { HeadMark, startControlStatusPolling, useControlStatus } from '@entities/control-status';
import { useHeads } from '@entities/heads';
import { startAuthPolling, useAuth } from '@entities/auth';
import { headsReportingNone, nearestWindow, planLevel, startUsagePolling, useUsage } from '@entities/usage';
import { useRestartPending } from '@entities/config';
import { useSession } from '@entities/session';
import { connect, useEvents } from '@entities/events';
import { wireLive } from './wire';
import type { ConnectionStatus } from '@entities/events';
import { timeAgo } from '@shared/lib';
import { Badge } from '@shared/ui';
import type { Tone } from '@shared/ui';
import type { AuthPayload, UsagePayload } from '@shared/api';
import { S } from './strings';
import './rule.css';

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS, local or UTC. The strip prints both. */
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
export type HealthState = 'green' | 'amber' | 'red' | 'grey';

/** The status colour each health state prints beside its word. */
const HEALTH_TONE: Record<HealthState, Tone> = { green: 'ok', amber: 'warn', red: 'danger', grey: 'neutral' };

export function healthOf(statusFailed: boolean, anyHeadDown: boolean, locked: boolean): HealthState {
  // A 401 is the daemon ANSWERING: without the key the console cannot say how the daemon is, and
  // the red "unreachable" it printed over the key gate was a claim the answer had just disproved.
  if (locked) return 'grey';
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
        <span className="myx-rule-absent">no head reports a limit</span>
      ) : (
        <>
          <HeadMark head={nearest.head} />
          {nearest.account !== null ? <span className="myx-rule-account">{nearest.account}</span> : null}
          <span className="myx-rule-period">{nearest.window}</span>
          <span className={`myx-rule-figure myx-rule-pct-${pctTone(nearest.pct, usage?.warn_pct ?? 0)}`}>{nearest.pct}%</span>
          <span className="myx-rule-word">{S.used}</span>
          {nearest.reset !== null ? <span className="myx-rule-reset">resets {nearest.reset}</span> : null}
        </>
      )}
    </p>
  );
}

/** A plan share's tone, from the daemon's own lines: danger at the critical line, warn past its warn
 *  line, the plain ink below both. */
export function pctTone(pct: number, warnPct: number): Tone {
  const level = planLevel(pct, warnPct);
  return level === 'critical' ? 'danger' : level === 'warn' ? 'warn' : 'neutral';
}

/** How long the link may be silent before it is in doubt: the daemon writes a heartbeat after
 *  15 s with nothing to send (EventsRoute.kt HEARTBEAT_MS), so a healthy quiet link goes up to 15 s
 *  between bytes, and 35 s without one is a missed heartbeat plus margin. */
export const LINK_SILENT_MS = 35_000;

/**
 * How the live connection is doing, beside health: green while a stream is open, amber while it is
 * between attempts, grey when there is none (no management key yet, or a stale one).
 *
 * Beside it, when something last HAPPENED: the age of the last event. That age alone cannot say
 * whether the link is alive, because a quiet daemon sends no events for minutes; the heartbeat
 * does. So the age turns `stale` only when the link itself has gone silent past LINK_SILENT_MS. It
 * used to turn stale 15 s after the last event, which called every quiet, healthy stream stale
 * (walkthrough S13).
 */
export function ConnectionCell({ status, lastFrameAt, lastBeatAt = null, now = Date.now() }: {
  status: ConnectionStatus;
  lastFrameAt: number | null;
  lastBeatAt?: number | null;
  now?: number;
}) {
  const tone: Tone = status === 'live' ? 'ok' : status === 'reconnecting' ? 'warn' : 'neutral';
  const word = status === 'live' ? S.live : status === 'reconnecting' ? S.reconnecting : S.off;
  const silent = lastBeatAt !== null && now - lastBeatAt > LINK_SILENT_MS;
  return (
    <p className="myx-rule-cell myx-rule-connection">
      <Badge tone={tone} quiet>{word}</Badge>
      {lastFrameAt === null ? (
        <span className="myx-rule-absent">no events yet</span>
      ) : (
        <>
          <span className="myx-rule-word">{S.lastEvent}</span>
          <span className="myx-rule-figure">{timeAgo(lastFrameAt, now)}</span>
          {silent ? <span className="myx-rule-word">{S.stale}</span> : null}
        </>
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
      <span className="myx-rule-figure">{none}</span>
      <span className="myx-rule-word">{S.noneTail}</span>
    </p>
  );
}

/**
 * Knobs that were saved and are not in force yet: the daemon snapshots every
 * knob except three at start, so a saved restart-only value does nothing until
 * the daemon restarts, and the console must not let that read as "applied".
 *
 * The cell is a warn badge with the count of pending keys. It reads the store a
 * page's save left behind, so the strip starts no route and no poll of its own;
 * when the store clears (what a restart does to it, and the only thing that
 * honestly can) the cell is gone.
 */
export function PendingRestartCell({ pending }: { pending: readonly string[] }) {
  if (pending.length === 0) return null;
  return (
    <p className="myx-rule-cell myx-rule-pending">
      <Badge tone="warn">{S.restartPending}</Badge>
      <span className="myx-rule-figure">{pending.length}</span>
    </p>
  );
}

export function Rule() {
  const status = useControlStatus((state) => state);
  const heads = useHeads((state) => state.data);
  const usage = useUsage((state) => state.data);
  const auth = useAuth((state) => state.data);
  const pendingRestart = useRestartPending((state) => state.pending);
  const locked = useSession((state) => state.locked);
  const connection = useEvents((state) => state);
  const { local, utc } = useClock();

  useEffect(() => {
    // The daemon's identity and registry are near-static, but the health cell is whether it
    // answers, so the status read is polled with the two routes the readout needs: the rule is
    // chrome and outlives every page it is drawn over.
    const stops = [startControlStatusPolling(10_000), startUsagePolling(15_000), startAuthPolling(30_000)];
    // The live stream is opened here because the rule is the chrome that outlives every page and
    // the surface that prints the connection; connect() is idempotent, so whoever else asks for it
    // gets the same one stream.
    connect();
    // The entities follow the stream from here: one subscription per (entity, kind), refetching
    // through each entity's own api. See wire.ts for why the wiring is not inside the entities.
    const unwire = wireLive();
    return () => {
      stops.forEach((stop) => stop());
      unwire();
    };
  }, []);

  const anyHeadDown = heads !== null && heads.some((head) => !head.running || !head.healthy);
  const health = healthOf(status.error !== null, anyHeadDown, locked);

  return (
    <header className="myx-rule">
      <ConnectionCell status={connection.status} lastFrameAt={connection.lastFrameAt} lastBeatAt={connection.lastBeatAt} />

      <p className="myx-rule-cell myx-rule-health">
        <Badge tone={HEALTH_TONE[health]} quiet>{S.health[health]}</Badge>
      </p>

      <WindowCell usage={usage} auth={auth} />

      <NoneCell usage={usage} />

      <PendingRestartCell pending={pendingRestart} />

      <p className="myx-rule-cell myx-rule-clocks">
        <span className="myx-rule-figure">{local}</span>
        <span className="myx-rule-word">{S.local}</span>
        <span className="myx-rule-figure">{utc}</span>
        <span className="myx-rule-word">{S.utc}</span>
      </p>
    </header>
  );
}
