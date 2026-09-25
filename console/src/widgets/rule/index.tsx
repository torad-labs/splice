// The status strip (docs/design/DESIGN.md section 6): whether the link is live, whether the daemon is
// answering, the plan window closest to running out and when it resets, whether a saved knob is
// still waiting for a restart, and the time. It sits over every page, never scrolls and never
// guesses: a window no head reports says so instead of reading zero.
//
// The rule reads entities through their public exports and owns nothing: the
// only state it keeps is the clock, and the only reads it starts are the ones
// nothing else starts for it.
//
// The window derivation is NOT computed here. It arrives from @features/nearest-limit,
// the one definition the fleet and accounts pages print too: this widget used to
// carry its own copy, and then read only /api/usage while the accounts page read
// every pooled account, and two definitions of "nearest limit" printed two numbers
// (review of #264). The cells are exported so a test can render them from
// payloads rather than from the stores (a static render sees a store's initial
// state and never its current one).
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { CheckCircleIcon } from '@phosphor-icons/react/dist/csr/CheckCircle';
import { GaugeIcon } from '@phosphor-icons/react/dist/csr/Gauge';
import { KeyIcon } from '@phosphor-icons/react/dist/csr/Key';
import { TimerIcon } from '@phosphor-icons/react/dist/csr/Timer';
import { WarningCircleIcon } from '@phosphor-icons/react/dist/csr/WarningCircle';
import { XCircleIcon } from '@phosphor-icons/react/dist/csr/XCircle';
import { HeadMark, hueClass, startControlStatusPolling, useControlStatus, useHues, type Hue } from '@entities/control-status';
import { startHeadsPolling, useHeads } from '@entities/heads';
import { startAccountsPolling, useAccounts } from '@entities/account';
import type { AccountRow } from '@entities/account';
import { startAuthPolling, useAuth } from '@entities/auth';
import { headsReportingNone, planLevel, startUsagePolling, useUsage } from '@entities/usage';
import { nearestLimit } from '@features/nearest-limit';
import { useRestartPending } from '@entities/config';
import { useSession } from '@entities/session';
import { connect, useEvents } from '@entities/events';
import { wireLive } from './wire';
import type { ConnectionStatus } from '@entities/events';
import { timeAgo } from '@shared/lib';
import { Badge, Braid, Meter, Tip } from '@shared/ui';
import type { Strand, Tone } from '@shared/ui';
import type { AuthPayload, HeadStatus, UsagePayload } from '@shared/api';
import { S, U } from './strings';
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

/** A glyph per health state, so the state reads in greyscale as well as in colour. */
const HEALTH_GLYPH: Record<HealthState, ReactNode> = {
  green: <CheckCircleIcon weight="fill" aria-hidden="true" />,
  amber: <WarningCircleIcon weight="fill" aria-hidden="true" />,
  red: <XCircleIcon weight="fill" aria-hidden="true" />,
  grey: <KeyIcon aria-hidden="true" />,
};

export function healthOf(statusFailed: boolean, anyHeadDown: boolean, locked: boolean): HealthState {
  // A 401 is the daemon ANSWERING: without the key the console cannot say how the daemon is, and
  // the red "unreachable" it printed over the key gate was a claim the answer had just disproved.
  if (locked) return 'grey';
  if (statusFailed) return 'red';
  return anyHeadDown ? 'amber' : 'green';
}

/** The nearest limit (the one definition the fleet and accounts pages print): the head, the account,
 *  the window, how full it is as a meter and a figure, and when it resets. What the strip does not
 *  print (how many heads report no limit) shows on hover and focus. */
export function WindowCell({ accounts, usage, auth }: {
  accounts: readonly AccountRow[];
  usage: UsagePayload | null;
  auth: AuthPayload | null;
}) {
  const nearest = nearestLimit({ accounts, usage, auth }, Date.now());
  const none = headsReportingNone(usage);
  const tip = none === null || none === 0 ? S.limit : `${S.limit}, ${none} ${U.withoutLimit}`;
  const glyph = <Tip text={tip} side="bottom"><GaugeIcon className="myx-rule-glyph" aria-label={S.limit} /></Tip>;
  if (nearest === null) {
    return (
      <p className="myx-rule-cell myx-rule-window">
        {glyph}
        <span className="myx-rule-absent">{S.noLimit}</span>
      </p>
    );
  }
  const tone = pctTone(nearest.pct, usage?.warn_pct ?? 0);
  return (
    <p className="myx-rule-cell myx-rule-window">
      {glyph}
      {nearest.head !== null ? <HeadMark head={nearest.head} /> : null}
      {nearest.account !== null ? <span className="myx-rule-account">{nearest.account}</span> : null}
      <span className="myx-rule-period">{nearest.window}</span>
      <span className="myx-rule-meter">
        <Meter value={nearest.pct / 100} tone={tone === 'neutral' ? 'accent' : tone} label={`${nearest.window} ${nearest.pct}%`} />
      </span>
      <span className={`myx-rule-figure myx-rule-pct-${tone}`}>{nearest.pct}%</span>
      {nearest.reset !== null ? (
        <span className="myx-rule-reset"><TimerIcon className="myx-rule-glyph" aria-label={U.resets} />{nearest.reset}</span>
      ) : null}
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
  const silent = status === 'live' && lastBeatAt !== null && now - lastBeatAt > LINK_SILENT_MS;
  const tone: Tone = silent ? 'warn' : status === 'live' ? 'ok' : status === 'reconnecting' ? 'warn' : 'neutral';
  const word = silent ? S.silent : status === 'live' ? S.live : status === 'reconnecting' ? S.reconnecting : S.off;
  // the age of the last event is detail, not state: it shows on hover and focus
  const tip = lastFrameAt === null ? S.noEvents : `${S.lastEvent} ${timeAgo(lastFrameAt, now)}`;
  return (
    <p className="myx-rule-cell myx-rule-connection">
      <Tip text={tip} side="bottom"><Badge tone={tone} quiet>{word}</Badge></Tip>
    </p>
  );
}

/** The daemon's health as a glyph and a word: the word is the daemon while it is fine, and the
 *  state itself when it is not, so a problem reads without hovering. */
export function HealthCell({ health }: { health: HealthState }) {
  return (
    <p className={`myx-rule-cell myx-rule-health myx-rule-health-${HEALTH_TONE[health]}`}>
      <Tip text={S.health[health]} side="bottom">
        <span className="myx-rule-state">
          {HEALTH_GLYPH[health]}
          <span>{health === 'green' ? S.daemon : S.health[health]}</span>
        </span>
      </Tip>
    </p>
  );
}

/** Every running head as a strand of its colour, as long as its turns in flight, pulsing when one
 *  lands (the gate's `released` count moves). Registry order, so the strands keep their places. */
export function strandsOf(heads: readonly HeadStatus[] | null, hueOfHead: (head: string) => Hue | number): Strand[] {
  if (heads === null) return [];
  return heads.filter((head) => head.running).map((head) => ({
    key: head.key,
    name: head.label,
    hue: hueClass(hueOfHead(head.key)),
    count: head.gate?.inflight ?? 0,
    landed: head.gate?.released ?? 0,
  }));
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
  const pools = useAccounts((state) => state.data);
  const pendingRestart = useRestartPending((state) => state.pending);
  const locked = useSession((state) => state.locked);
  const connection = useEvents((state) => state);
  const hues = useHues();
  const { local, utc } = useClock();

  useEffect(() => {
    // The daemon's identity and registry are near-static, but the health cell is whether it
    // answers, so the status read is polled with the routes the readout needs: the strip is
    // chrome and outlives every page it is drawn over. The heads read feeds the braid: a turn
    // ending arrives as an event (wire.ts), a turn starting only on the next read.
    const stops = [
      startControlStatusPolling(10_000),
      startUsagePolling(15_000),
      startAccountsPolling(15_000),
      startAuthPolling(30_000),
      startHeadsPolling(5_000),
    ];
    // The live stream is opened here because the strip is the chrome that outlives every page and
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
  const strands = strandsOf(heads, hues);

  return (
    <header className="myx-rule">
      {strands.length === 0 ? null : (
        <div className="myx-rule-cell myx-rule-braid">
          <Braid strands={strands} label={S.braid} unit={U.inFlight} />
        </div>
      )}

      <ConnectionCell status={connection.status} lastFrameAt={connection.lastFrameAt} lastBeatAt={connection.lastBeatAt} />

      <HealthCell health={health} />

      <WindowCell accounts={pools !== null && 'accounts' in pools ? pools.accounts : []} usage={usage} auth={auth} />

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
