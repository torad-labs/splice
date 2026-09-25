// NEEDS YOU (V4-219): the page the console opens on. One list, worst first, of everything that needs
// the operator across heads, plans, accounts, turns, sessions, teams, the daemon and doctor, each
// item with its one fix; then every input it could not read, because an unread input hides what it
// would show. The rules are in model.ts; this file reads the stores and draws.
//
// The shell's status strip already polls heads, usage, accounts, sign-ins and the registry of heads
// on every page. This page adds the reads only it needs, at a pace for a list read at a glance: the
// session registry, the teams, the doctor report and /health's topologyStale.
import { useEffect, useState } from 'react';
import { useAccounts } from '@entities/account';
import { useAuth } from '@entities/auth';
import { HeadMark } from '@entities/control-status';
import { probeTopologyStale, useRestartPending } from '@entities/config';
import { startDoctorPolling, useDoctor } from '@entities/doctor';
import { restartHead, startHead, useHeads } from '@entities/heads';
import { startSessionsPolling, useSessionRegistry } from '@entities/session';
import { fetchTeams, useTeams } from '@entities/team';
import { useUsage } from '@entities/usage';
import { DaemonRestart } from '@features/daemon-restart';
import { DoctorFix } from '@features/doctor-fix';
import { clockText } from '@widgets/rule';
import { Confirm, Copy, Key, KeyLink } from '@shared/controls';
import { poll, timeAgo } from '@shared/lib';
import { Badge, DataTable, Empty, InfoTip, PageHeader, Section } from '@shared/ui';
import type { Column, Tone } from '@shared/ui';
import { needsOf } from './model';
import type { Fix, Need, NeedsList, Read, Reading, ReadState } from './model';
import { H, S, U } from './strings';
import './needs-you.css';

export { needsOf, readingOf, INPUTS } from './model';
export type { Fix, Need, NeedInputs, NeedsList, Read, Reading, ReadState } from './model';

/** The registry and the doctor at a list's pace: the doctor runs its checks on every read. */
const SESSIONS_MS = 15_000;
const TEAMS_MS = 30_000;
const DOCTOR_MS = 60_000;
const TOPOLOGY_MS = 30_000;

const STATE_WORD: Record<Exclude<ReadState, 'read'>, string> = { reading: S.reading, failed: S.failed, unserved: S.unserved };
const STATE_TONE: Record<Exclude<ReadState, 'read'>, Tone> = { reading: 'neutral', failed: 'danger', unserved: 'neutral' };

/** A head's start or restart, made here: the daemon's refusal prints beside the key, in its words. */
function HeadWrite({ head, kind }: { head: string; kind: 'start' | 'restart' }) {
  const [busy, setBusy] = useState(false);
  const [fault, setFault] = useState<string | null>(null);
  const run = () => {
    setBusy(true);
    setFault(null);
    (kind === 'start' ? startHead : restartHead)(head).then(
      () => setFault(null),
      (err: unknown) => setFault(err instanceof Error ? err.message : String(err)),
    ).finally(() => setBusy(false));
  };
  return (
    <span className="myx-ny-fix">
      {kind === 'start'
        ? <Key onClick={run} busy={busy}>{S.start}</Key>
        : <Confirm label={S.restart} confirmLabel={S.confirmRestart} busy={busy} onConfirm={run} />}
      {fault === null ? null : <span className="myx-ny-fault" role="alert">{fault}</span>}
    </span>
  );
}

/** A remedy's command, with its copy key, or with why there is none when the redaction reached it. */
function FixCommand({ command, masked }: { command: string; masked: boolean }) {
  return (
    <span className="myx-ny-fix">
      <code className="myx-ny-command">{command}</code>
      {masked ? <InfoTip text={H.masked} label={S.maskedWhy} /> : <Copy value={command} />}
    </span>
  );
}

export function FixCell({ fix }: { fix: Fix }) {
  switch (fix.kind) {
    case 'start':
    case 'restart':
      return <HeadWrite head={fix.head} kind={fix.kind} />;
    case 'restart-daemon':
      return <DaemonRestart />;
    case 'copy':
    case 'masked':
      return <FixCommand command={fix.command} masked={fix.kind === 'masked'} />;
    case 'doctor-fix':
      return (
        <>
          <FixCommand command={fix.command} masked={fix.masked} />
          <DoctorFix id={fix.id} />
        </>
      );
    case 'open':
      return <KeyLink href={fix.href}>{fix.label}</KeyLink>;
  }
}

/** What an item is about: its head's mark with the item's own name, or the name alone. */
function Subject({ need }: { need: Need }) {
  if (need.head === null) return <>{need.subject}</>;
  return need.source === 'heads' ? <HeadMark head={need.head} /> : <HeadMark head={need.head}>{need.subject}</HeadMark>;
}

const NEED_COLUMNS: Column<Need>[] = [
  { key: 'item', label: S.item, primary: true, width: '22%', cell: (need) => <Subject need={need} /> },
  { key: 'finding', label: S.finding, wrap: true, cell: (need) => need.finding },
  { key: 'page', label: S.page, width: '10%', cell: (need) => S.sources[need.source] },
  { key: 'fix', label: S.fix, width: '30%', wrap: true, cell: (need) => <FixCell fix={need.fix} /> },
];

function readingColumns(now: number): Column<Reading>[] {
  return [
    { key: 'input', label: S.input, primary: true, width: '22%', cell: (reading) => S.inputs[reading.input] },
    {
      key: 'state',
      label: S.unread,
      width: '14%',
      cell: (reading) => (reading.state === 'read' ? null : <Badge tone={STATE_TONE[reading.state]} quiet>{STATE_WORD[reading.state]}</Badge>),
    },
    { key: 'why', label: S.why, wrap: true, cell: (reading) => reading.reason ?? '' },
    { key: 'at', label: S.lastRead, width: '14%', cell: (reading) => (reading.at === null ? '' : timeAgo(reading.at, now)) },
  ];
}

/** The list as the page draws it, from a list already derived: the test renders this from
 *  payloads, since a static render never sees a store past its first state. */
export function NeedsYouBoard({ list, now }: { list: NeedsList; now: number }) {
  const unread = list.readings.filter((reading) => reading.state !== 'read');
  const all = list.readAt !== null;
  return (
    <div className="myx-ny">
      <PageHeader title={S.title} info={{ text: H.about, label: S.about }} />
      <Section
        title={S.items}
        count={list.needs.length}
        {...(all ? { actions: <span className="myx-ny-read">{`${U.read} ${clockText(list.readAt ?? now, false)}`}</span> } : {})}
      >
        {list.needs.length > 0 ? (
          <DataTable
            columns={NEED_COLUMNS}
            rows={list.needs}
            rowKey={(need) => need.key}
            label={S.items}
            rowTone={(need) => need.severity}
          />
        ) : all ? <Empty text={S.nothing} source={H.nothing} /> : <Empty text={S.nothingYet} source={H.nothingYet} />}
      </Section>
      {unread.length === 0 ? null : (
        <Section title={S.unread} count={unread.length} info={{ text: H.unread, label: S.unread }}>
          <DataTable columns={readingColumns(now)} rows={unread} rowKey={(reading) => reading.input} label={S.unread} />
        </Section>
      )}
    </div>
  );
}

const UNREAD: Read<boolean> = { data: null, error: null, lastUpdated: null };

export function NeedsYouPage() {
  const heads = useHeads((state) => state);
  const auth = useAuth((state) => state);
  const accounts = useAccounts((state) => state);
  const usage = useUsage((state) => state);
  const sessions = useSessionRegistry((state) => state);
  const teams = useTeams((state) => state);
  const doctor = useDoctor((state) => state);
  const restartPending = useRestartPending((state) => state.pending);
  const [topology, setTopology] = useState<Read<boolean>>(UNREAD);

  useEffect(() => {
    const stops = [startSessionsPolling(SESSIONS_MS), poll(fetchTeams, TEAMS_MS), startDoctorPolling(DOCTOR_MS)];
    return () => stops.forEach((stop) => stop());
  }, []);

  // /health has no store: the strip's own read of it fails open, and this page must tell a config
  // file that did not change from a /health that did not answer.
  useEffect(() => poll(() => probeTopologyStale().then(
    (stale) => setTopology({ data: stale, error: null, lastUpdated: Date.now() }),
    (err: unknown) => setTopology((was) => ({ ...was, error: err instanceof Error ? err.message : String(err) })),
  ), TOPOLOGY_MS), []);

  const now = Date.now();
  const list = needsOf({ heads, auth, accounts, usage, sessions, teams, doctor, topology, restartPending }, now);
  return <NeedsYouBoard list={list} now={now} />;
}

export default NeedsYouPage;
