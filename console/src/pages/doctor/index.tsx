// Doctor: every check the daemon ran, drawn by status. The figures lead with the checks split by
// status and how many want the operator; the checks are one table, each section's own split bar over
// its rows under `By section`, and the worst first under `Attention first`. An opened check holds its
// finding and its fix with a copy key. The report's own fields, the upgrade and the playground sit
// under the table.
//
// Two rules this page enforces rather than hopes for. The report is GATED on redaction: the payload
// is walked for credential shapes before anything renders, and a payload that still carries one is
// refused rather than shown, because a console that painted a leaked token into a row would be the
// leak. And the playground never stores a body: its request and response live in one reducer's
// state, are dropped the moment a new run starts, and touch no store and no storage.
import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { checkFinding, checkSection, fetchUpgrade, startDoctorPolling, useDoctor, useUpgrade, upgradeVerdict } from '@entities/doctor';
import type { DoctorCheck, DoctorPayload, UpgradePayload } from '@entities/doctor';
import { fetchHeads, useHeads } from '@entities/heads';
import { runPlayground } from '@entities/playground';
import { DaemonRestart } from '@features/daemon-restart';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { Blank, Choice, Copy, Fault, Input, Key, KeyLink } from '@shared/controls';
import { ABSENT, fmtInt, timeAgo } from '@shared/lib';
import { Badge, DataTable, DetailPanel, Empty, KeyValue, PageHeader, Section, StackedBar, Stat, StatRow } from '@shared/ui';
import type { Column, RowGroup, Tone } from '@shared/ui';
import {
  EMPTIES, IDLE_PLAYGROUND, TONE, attentionCount, attentionParts, canSend, claudeVersionText, collapseChecks, gateReport, groupChecks, latestText, logsHeadOf,
  playgroundNext, reportFacts, rollbackText, rowTone, statusParts, subjectOf,
} from './model';
import type { CheckRow, PlaygroundEvent } from './model';
import { fixtureDoctor } from './fixtures/doctor';
import { fixtureName } from './model';
import { dispositions } from './coverage';
import { H, S } from './strings';
import './doctor.css';

export { dispositions };

const PAGE_ID = 'doctor';
const POLL_MS = 60000;

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'attention-first', name: S.attentionFirst, layout: 'bay', filter: {}, sort: { field: 'status', dir: 'desc' }, group: 'section', fields: [] },
  { id: 'by-section', name: S.bySection, layout: 'bay', filter: {}, sort: null, group: 'section', fields: [] },
];

/** What opening a row records: its first check's id, not the row key. The key carries the status,
 *  so a check that went from warn to fail between polls closed its own detail as it got worse;
 *  the board resolves an open row by key or by any member's id. */
export function openIdOf(row: CheckRow): string {
  return row.members[0]?.id ?? row.key;
}

function StatusBadge({ row, quiet = false }: { row: CheckRow; quiet?: boolean }) {
  return <Badge tone={TONE[row.status]} quiet={quiet}>{S.statusName[row.status]}</Badge>;
}

function checkColumns(): Column<CheckRow>[] {
  return [
    { key: 'check', label: S.check, width: '36%', primary: true, mono: true, cell: (row) => row.label },
    { key: 'state', label: S.state, width: 'calc(7 * var(--u))', cell: (row) => <StatusBadge row={row} quiet /> },
    // A check with nothing to fix prints the absence glyph; `No fix offered` is the opened check's.
    { key: 'fix', label: S.fix, mono: true, cell: (row) => row.fix ?? ABSENT },
  ];
}

/** What an opened check found, and where. A family named by its ids' subjects
 *  (`configuration/system-prompt:<head>`) says which heads and one finding; a family whose ids carry
 *  no subject (`installation/wrapper`, one per launcher) differs only in its findings, so it lists
 *  each one. */
function checkFacts(row: CheckRow): [string, ReactNode][] {
  const first = row.members[0];
  if (first === undefined) return [];
  const section: [string, ReactNode] = [S.section, checkSection(first)];
  if (row.members.length > 1 && row.members.every((member) => member.id.includes(':'))) {
    return [section, [S.appliesTo, row.members.map(subjectOf).join(', ')], [S.finding, checkFinding(first)]];
  }
  if (row.members.length > 1) {
    return [section, ...row.members.map((member, index): [string, ReactNode] => [`${S.finding} ${index + 1}`, checkFinding(member)])];
  }
  return [section, [S.finding, checkFinding(first)]];
}

/** The opened check's fix with its copy key. A remedy that is a `splice logs --head` command also
 *  opens that log here, since this console has the page for it. */
function FixLine({ fix }: { fix: string | null }) {
  if (fix === null) return <Empty text={S.noFix} />;
  const logsHead = logsHeadOf(fix);
  return (
    <p className="myx-dc-fix">
      <code className="myx-dc-command">{fix}</code>
      <Copy value={fix} label={S.copyFix} />
      {logsHead === null ? null : <KeyLink href={`#/logs?head=${encodeURIComponent(logsHead)}`}>{S.openLog}</KeyLink>}
    </p>
  );
}

function verdictTone(upgrade: UpgradePayload): Tone {
  const verdict = upgradeVerdict(upgrade);
  return verdict === 'behind' ? 'warn' : verdict === 'current' ? 'ok' : 'neutral';
}

/** The figures the page leads with. Every one reads the GATED report: a count about a report the
 *  page refused would be a claim about something it did not read, so each prints the absence. */
function Figures({ shown, checks, upgrade }: { shown: DoctorPayload | null; checks: readonly DoctorCheck[]; upgrade: UpgradePayload | null }) {
  const attention = shown === null ? null : attentionCount(checks);
  const parts = shown === null ? null : attentionParts(checks);
  const failing = checks.some((check) => check.status === 'fail');
  return (
    <StatRow>
      <Stat
        label={S.checks}
        value={shown === null ? ABSENT : fmtInt(checks.length)}
        {...(shown === null ? {} : { chart: <StackedBar parts={statusParts(checks)} label={S.checks} legend format={fmtInt} /> })}
      />
      <Stat
        label={S.needAttention}
        value={attention === null ? ABSENT : fmtInt(attention)}
        {...(attention === null || attention === 0 ? {} : { tone: failing ? 'danger' as const : 'warn' as const })}
        {...(parts === null ? {} : { sub: parts })}
      />
      <Stat
        label={S.installed}
        value={shown?.splice.version ?? ABSENT}
        {...(upgrade === null ? {} : { sub: <Badge tone={verdictTone(upgrade)}>{S.verdictName[upgradeVerdict(upgrade)]}</Badge> })}
      />
      <Stat label={S.claudeCode} value={shown === null ? ABSENT : claudeVersionText(shown.claude_code.version)} />
    </StatRow>
  );
}

/** The playground. One POST /api/playground (M4-03): one prompt through the named head, and the
 *  daemon hands back the request it sent upstream and the response it got, neither recorded. Both
 *  land in the reducer's state for the run that asked, and a refusal lands as the daemon's own
 *  sentence. */
function Playground({ heads }: { heads: readonly string[] }) {
  const [state, dispatch] = useState(IDLE_PLAYGROUND);
  const send = (event: PlaygroundEvent) => dispatch((current) => playgroundNext(current, event));

  const start = () => {
    if (!canSend(state)) return;
    // The run this request belongs to is the one the `send` below opens, so its answer can be told
    // apart from the answer to any request the operator has since walked away from.
    const run = state.run + 1;
    send({ kind: 'send' });
    runPlayground(state.head.trim(), state.prompt).then(
      (wire) => send({ kind: 'answered', run, request: wire.request, response: wire.response }),
      (err: unknown) => send({ kind: 'failed', run, note: err instanceof Error ? err.message : String(err) }),
    );
  };
  const sending = state.step === 'sending';

  return (
    <Section title={S.playground} info={{ text: H.playground, label: S.aboutPlayground }}>
      <div className="myx-dc-play">
        <Choice
          label={S.head}
          value={state.head}
          options={[{ value: '', label: S.pickHead }, ...heads.map((head) => ({ value: head, label: head }))]}
          onChange={(value) => send({ kind: 'head', value })}
          w={24}
        />
        <Input label={S.prompt} value={state.prompt} onChange={(value) => send({ kind: 'prompt', value })} placeholder={H.prompt} w={48} />
        <span className="myx-dc-keys">
          {/* Busy is not disabled: a working key keeps focus and says it is working. */}
          <Key busy={sending} disabled={!sending && !canSend(state)} onClick={start}>{S.send}</Key>
          <Key onClick={() => send({ kind: 'reset' })}>{S.clear}</Key>
        </span>
      </div>
      {/* The bodies are printed from THIS component's state and written nowhere: no store, no
          storage, no history. A second send drops them at the only moment a run begins. */}
      {state.response === null ? null : (
        <div className="myx-dc-pair">
          <Section title={S.request}>
            <pre className="myx-dc-raw">{JSON.stringify(state.request, null, 2)}</pre>
          </Section>
          <Section title={S.response}>
            <pre className="myx-dc-raw">{JSON.stringify(state.response, null, 2)}</pre>
          </Section>
        </div>
      )}
      {state.note === null || state.step !== 'failed' ? null : <Fault message={state.note} />}
    </Section>
  );
}

/** The board, drawn from a report it is handed rather than from the store, so a test can plant a
 *  payload in it (a static render only ever sees a store's initial state). Which check is open is
 *  the page's state, handed in beside the report, so a render can show an opened check too. */
export function DoctorBoard({ report, pending = null, error = null, lastRead = null, upgrade = null, heads = [], openKey = null, onToggle, sample }: {
  report: DoctorPayload | null;
  pending?: string | null;
  error?: string | null;
  /** When the report on screen was read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
  upgrade?: UpgradePayload | null;
  heads?: readonly string[];
  openKey?: string | null;
  onToggle: (key: string) => void;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise. */
  sample?: string | undefined;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);

  // THE GATE, ONCE, AND EVERY SURFACE BELOW READS ITS OUTPUT (M4-07). `shown` is the report only
  // when it carries no credential shape, and nothing on this board reads `report` for its content:
  // the figures, the table, the report's fields and the opened check all draw from `shown`. A leak
  // is reported as a PATH set, never the value: a leak reporter that echoed the match would be the
  // leak.
  const { shown, leaks } = useMemo(() => gateReport(report), [report]);

  const checks = shown?.checks ?? [];
  const sections = groupChecks(checks, active);
  const rows = collapseChecks(sections.flatMap((group) => group.checks));
  // `By section` draws each section as a run with its own split bar: the report's state grid, one
  // bar per area. `Attention first` is one run, worst first, so it needs no titles.
  const groups: RowGroup<CheckRow>[] | null = active.sort?.field === 'status' ? null : sections.map((group) => ({
    key: group.key,
    title: group.key,
    count: group.checks.length,
    note: <StackedBar parts={statusParts(group.checks)} label={group.key} format={fmtInt} />,
    rows: collapseChecks(group.checks),
  }));
  // The open key names a row by its grouping key, or by the id of a check inside it: a row's key is
  // `status|family|fix`, which nothing outside this page knows, while a check id is what a report,
  // a test or a link carries.
  const opened = openKey === null ? null
    : rows.find((row) => row.key === openKey) ?? rows.find((row) => row.members.some((member) => member.id === openKey)) ?? null;
  const checkedAt = upgrade?.checked_at_epoch_millis ?? null;

  return (
    <div className="myx-dc" {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}>
      <PageHeader title={S.title} {...(sample === undefined ? {} : { actions: <Badge tone="neutral">{S.sample}</Badge> })}>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {error === null ? null : <Fault message={error} lastRead={lastRead} />}
      {pending === null ? null : <Empty text={EMPTIES.noReport.text} source={EMPTIES.noReport.source} />}
      {/* The gate. A payload that still carries a credential shape is refused, by path, and never
          rendered: every surface below would be the leak. */}
      {leaks.length === 0 ? null : <Empty text={S.refused} source={leaks.map((leak) => `${leak.kind} at ${leak.where}`).join('; ')} />}

      <div className={opened === null ? 'myx-dc-board' : 'myx-dc-board myx-dc-board-open'}>
        <div className="myx-dc-main">
          {report === null && pending === null ? <Blank strips={4} /> : <Figures shown={shown} checks={checks} upgrade={upgrade} />}

          {shown === null ? null : checks.length === 0 ? (
            <Empty text={EMPTIES.noChecks.text} source={EMPTIES.noChecks.source} />
          ) : (
            <Section title={S.checks} count={checks.length}>
              <DataTable
                columns={checkColumns()}
                {...(groups === null ? { rows } : { groups })}
                rowKey={(row) => row.key}
                label={S.checks}
                onOpen={(row) => onToggle(openIdOf(row))}
                openLabel={(row) => `${S.openCheck} ${row.label}`}
                selectedKey={opened?.key ?? null}
                rowTone={(row) => rowTone(row.status)}
              />
            </Section>
          )}

          <div className="myx-dc-pair">
            {shown === null ? null : (
              <Section title={S.report} info={{ text: H.report, label: S.aboutReport }}>
                <KeyValue rows={reportFacts(shown).map((fact) => [fact.field, fact.value] as const)} />
              </Section>
            )}
            {/* Rendered whether or not the report itself has landed: the upgrade reads its own route
                (GET /api/upgrade), and the restart is an action on the daemon rather than on the
                report, so hiding either behind the report hid it entirely. */}
            <Section title={S.version}>
              <KeyValue rows={[
                [S.latest, latestText(upgrade)],
                [S.rollback, rollbackText(upgrade)],
                [S.lastChecked, checkedAt === null ? ABSENT : timeAgo(checkedAt)],
              ]} />
              {/* The draining restart (WC-08), the same control the fleet's head detail mounts. */}
              <DaemonRestart />
            </Section>
          </div>

          <Playground heads={heads} />
        </div>

        {/* Unmounted at rest: no track and no empty panel until a check is opened. */}
        {opened === null ? null : (
          <DetailPanel
            title={opened.label}
            label={S.detail}
            status={<StatusBadge row={opened} />}
            onClose={() => onToggle(openIdOf(opened))}
            closeLabel={S.close}
          >
            <KeyValue rows={checkFacts(opened)} />
            <FixLine fix={opened.fix} />
          </DetailPanel>
        )}
      </div>
    </div>
  );
}

export function DoctorPage() {
  const doctor = useDoctor((state) => state);
  const upgrade = useUpgrade((state) => state);
  const heads = useHeads((state) => state.data);

  useEffect(() => {
    const stops = [startDoctorPolling(POLL_MS)];
    void fetchUpgrade();
    void fetchHeads();
    return () => stops.forEach((stop) => stop());
  }, []);

  const [openKey, setOpenKey] = useState<string | null>(null);
  const toggle = (key: string) => setOpenKey((current) => (current === key ? null : key));

  const { search } = useLocation();
  const fixture = fixtureName(search, import.meta.env.DEV);
  const report = fixtureDoctor(fixture);

  return (
    <DoctorBoard
      report={report ?? (doctor.data !== null && 'checks' in doctor.data ? doctor.data : null)}
      pending={report === null && doctor.data !== null && 'pending' in doctor.data ? doctor.data.pending : null}
      error={doctor.error}
      lastRead={report === null ? doctor.lastUpdated : null}
      upgrade={upgrade.data !== null && 'installed' in upgrade.data ? upgrade.data : null}
      heads={(heads ?? []).map((head) => head.key)}
      openKey={openKey}
      onToggle={toggle}
      sample={import.meta.env.DEV && report !== null && fixture !== null ? fixture : undefined}
    />
  );
}

export default DoctorPage;
