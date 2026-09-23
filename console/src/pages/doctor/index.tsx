// The doctor page: every check as a strip, every remedy copyable, and the playground behind a
// Reveal.
//
// Two rules this page enforces rather than hopes for. The report is GATED on redaction: the payload
// is walked for credential shapes before anything renders, and a payload that still carries one is
// refused rather than shown, because a console that painted a leaked token into a strip would be
// the leak. And the playground never stores a body: its request and response live in one reducer's
// state, are dropped the moment a new run starts, and touch no store and no storage.
import { useEffect, useMemo, useState } from 'react';
import { useLocation } from 'react-router';
import { checkFix, fetchUpgrade, startDoctorPolling, useDoctor, useUpgrade, upgradeVerdict } from '@entities/doctor';
import type { DoctorCheck, DoctorPayload, UpgradePayload } from '@entities/doctor';
import { fetchHeads, useHeads } from '@entities/heads';
import { runPlayground } from '@entities/playground';
import { DaemonRestart } from '@features/daemon-restart';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { Bay, Empty, FieldBox, HolderEdge, Reveal, Strip, StripField } from '@shared/ui';
import { Blank, Fault } from '@shared/controls';
import { EMPTIES, attentionCount, canSend, gateReport, groupChecks, playgroundNext, reportFacts, statusEdge, wantsAttention, IDLE_PLAYGROUND } from './model';
import type { PlaygroundEvent } from './model';
import { fixtureDoctor } from './fixtures/doctor';
import { fixtureName } from './model';
import { dispositions } from './coverage';
import { S } from './strings';
import './doctor.css';

export { dispositions };

const PAGE_ID = 'doctor';
const POLL_MS = 60000;
/** The check's id and its remedy. The widths are ch, so the two racks below stay a grid at every
 *  breakpoint; a rack that does not fit its column scrolls (`.myx-bay-rows`) rather than clipping. */
const WIDE = 22;
const NARROW = 10;
/** The report's own facts: the field's own name, and its value. Sized to the longest of each the
 *  payload can carry -- `schema_version` at 14 and `2026-09-18T07:45:00Z` at 20. */
const FACT_KEY = 16;
const FACT_VALUE = 22;

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'attention-first', name: 'attention first', layout: 'bay', filter: {}, sort: { field: 'status', dir: 'desc' }, group: 'section', fields: [] },
  { id: 'by-section', name: 'by section', layout: 'bay', filter: {}, sort: null, group: 'section', fields: [] },
];

/** A copy affordance that admits when the clipboard is unavailable instead of silently doing
 *  nothing: a console served over plain http has no navigator.clipboard. */
function CopyFix({ command }: { command: string }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className="myx-doc-btn"
      onClick={() => {
        void navigator.clipboard?.writeText(command).then(() => setDone(true), () => setDone(false));
      }}
    >
      {done ? S.copied : S.copy}
    </button>
  );
}

export function CheckStrip({ check, selected, onOpen }: { check: DoctorCheck; selected: boolean; onOpen: () => void }) {
  const fix = checkFix(check);
  return (
    <Strip
      edge={statusEdge(check.status)}
      edgeLabel={check.status}
      cocked={wantsAttention(check.status)}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={check.id}
    >
      {/* NO PER-CELL LABEL: the rack prints its column names once (B9), and this is the rack B9
          measured on ("doctor.png: three x fourteen"). The stack is what made every check two
          lines of type in a 64px row where one line of 16px fits. */}
      <StripField w={WIDE} value={check.id} mono={false} />
      {/* No status field: the holder edge above prints the identical word on every strip (m1
          design review B10). A check with nothing to fix prints the absence glyph in the fix
          cell; the sentence `no fix offered` is what the opened check's note says, which is where
          a Doctor fix's paragraph belongs. */}
      <StripField w={WIDE} value={fix ?? S.absent} mono={false} />
    </Strip>
  );
}

/** The report's own facts, one row each: the payload's field name beside its value. A HOMOGENEOUS
 *  rack, so its column names print once on the bay and no cell carries a label (B9). */
function FactStrip({ field, value }: { field: string; value: string }) {
  return (
    <Strip edge="grey" edgeLabel="" ariaLabel={field}>
      <StripField w={FACT_KEY} value={field} mono={false} />
      <StripField w={FACT_VALUE} value={value} mono={false} />
    </Strip>
  );
}

/** A rack's column names, once, at the same ch widths as the cells they name.
 *
 *  THE GROWTH IS THE HALF THAT IS EASY TO MISS, and the names were 71px and 44px off their own
 *  columns before it was added. `strip-field.tsx` sets `flexGrow` to the field's OWN ch so the
 *  cells share their rack's slack in proportion to their declared widths (M1-73), which means a
 *  cell is never its declared width -- so a name row fixed at `w ch` drifts away from the column
 *  under it, and drifts further the more slack the rack has. The name takes the same growth for
 *  the same reason. Measured at 1536, after: the checks rack's names sit 1px from their cells. */
function ColumnNames({ columns }: { columns: readonly { w: number; label: string }[] }) {
  return (
    <>
      {columns.map((column) => (
        <span
          key={column.label}
          className="myx-doc-col"
          style={{ width: `${column.w}ch`, flexGrow: column.w }}
        >
          {column.label}
        </span>
      ))}
    </>
  );
}

/** The playground. A Reveal panel, not a page: the rail has thirteen addresses and no room for a
 *  fourteenth, and a prompt sent once is not a destination.
 *
 *  THE SEND IS ONE POST /api/playground (M4-03): one prompt through the named head, and the daemon
 *  hands back the request it sent upstream and the response it got, neither recorded. Both land in
 *  the reducer's state for the run that asked, and a refusal lands as the daemon's own sentence. */
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

  return (
    <Reveal label={S.playground}>
      <div className="myx-doc-section">
        <div className="myx-doc-row">
          <FieldBox
            label={S.head}
            value={state.head}
            provenance="state file"
            hot
            onChange={(value) => send({ kind: 'head', value })}
          />
        </div>
        <p className="myx-doc-note">
          {heads.length === 0 ? 'no heads loaded' : heads.join(' ')}
        </p>
        <FieldBox
          label={S.prompt}
          value={state.prompt}
          provenance="state file"
          hot
          onChange={(value) => send({ kind: 'prompt', value })}
        />
        <div className="myx-doc-row">
          <button type="button" className="myx-doc-btn" disabled={!canSend(state)} onClick={start}>
            {state.step === 'sending' ? S.sending : S.send}
          </button>
          <button type="button" className="myx-doc-btn" onClick={() => send({ kind: 'reset' })}>{S.clear}</button>
        </div>
        {/* The bodies are printed from THIS component's state and written nowhere: no store, no
            storage, no history. A second send drops them at the only moment a run begins. */}
        {state.response === null ? null : (
          <>
            <p className="myx-doc-note">{S.request}</p>
            <pre className="myx-doc-raw">{JSON.stringify(state.request, null, 2)}</pre>
            <p className="myx-doc-note">{S.response}</p>
            <pre className="myx-doc-raw">{JSON.stringify(state.response, null, 2)}</pre>
          </>
        )}
        {state.note === null || state.step !== 'failed' ? null : (
          <p className="myx-doc-note" role="alert">{state.note}</p>
        )}
      </div>
    </Reveal>
  );
}

/** The board, drawn from a report it is handed rather than from the store, so a test can plant a
 *  payload in it (a static render only ever sees a store's initial state). Which check is open is
 *  the page's state, handed in beside the report, so a render can show an opened check too. */
export function DoctorBoard({ report, pending = null, error = null, upgrade = null, heads = [], openKey = null, onToggle, sample }: {
  report: DoctorPayload | null;
  pending?: string | null;
  error?: string | null;
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
  // the rack, the facts, the version strip, the attention count, the fix list and the opened
  // check's detail all draw from `shown`. The rack used to be the only surface behind the gate while
  // the fix list and the detail read the payload as served, so a secret the gate caught still
  // printed in the aside. A leak is reported as a PATH set, never the value: a leak reporter that
  // echoed the match would be the leak.
  const { shown, leaks } = useMemo(() => gateReport(report), [report]);

  const checks = shown?.checks ?? [];
  const groups = groupChecks(checks, active);
  const opened = checks.find((check) => check.id === openKey) ?? null;
  const fixes = checks.filter((check) => checkFix(check) !== null);

  return (
    <div
      className="myx-doc"
      {...(sample === undefined ? {} : { 'data-sample': sample })}
    >
      <header className="myx-doc-head">
        <h1 className="myx-doc-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {error === null ? null : <Fault message={error} />}

      {pending !== null ? <Empty text={EMPTIES.noReport.text} source={`row ${pending}`} /> : null}
      {report === null && pending === null ? <Blank strips={4} /> : null}

      {/* The gate. A payload that still carries a credential shape is refused, by path, and never
          rendered: every surface below would be the leak. */}
      {leaks.length === 0 ? null : (
        <Empty text="report refused, leaks found" source={leaks.map((leak) => `${leak.kind} at ${leak.where}`).join('; ')} />
      )}

      <div className="myx-doc-body">
        {/* ---- M2-22: TWO COLUMNS, ONE TABLE, AND THE FACTS THE PAGE WAS ALREADY SERVED --------
            WHAT WAS HERE: one column of seven section bays. Each was a plate, a column-name row,
            rails and 32px of padding top and bottom -- measured 141px of chrome for a bay holding
            ONE check -- and the seven of them stacked to a 1344px body in a 1024px frame, so six
            of the ten checks were in frame and the rest were below the fold. That is the shape
            splice-design named on accounts the same night: "60px of band above each group header
            to show one data row, five times".
            WHAT IS HERE: the checks are ONE table with its column names printed once, and the
            report's own facts -- which the page was served and printed NOWHERE -- are a second
            table beside it.
            THE SECTION IS NOT LOST WITH THE BAYS. A check id IS "<section>/<name>", so the section
            is printed in the first cell of every row, and `groupChecks` still decides the ORDER --
            worst section first under `attention first`, alphabetical under `by section` -- which is
            what both views' `group: 'section'` meant. The plates were the sections' only other job.
            THE DETAIL COLUMN IS UNTOUCHED: it carries real content at rest (M1-112) and this row
            says so; nothing below the grid changed. */}
        <div className="myx-doc-bays">
          {shown === null ? null : checks.length === 0 ? (
            <Empty text={EMPTIES.noChecks.text} source={EMPTIES.noChecks.source} />
          ) : (
            <>
              <Bay
                className="myx-doc-checks"
                label={S.checks}
                count={checks.length}
                fields={<ColumnNames columns={[{ w: WIDE, label: S.check }, { w: WIDE, label: S.fix }]} />}
              >
                {groups.flatMap((group) => group.checks).map((check) => (
                  <CheckStrip
                    key={check.id}
                    check={check}
                    selected={openKey === check.id}
                    onOpen={() => onToggle(check.id)}
                  />
                ))}
              </Bay>

              <Bay
                className="myx-doc-report"
                label={S.report}
                count={reportFacts(shown).length}
                fields={<ColumnNames columns={[{ w: FACT_KEY, label: S.field }, { w: FACT_VALUE, label: S.value }]} />}
              >
                {reportFacts(shown).map((fact) => (
                  <FactStrip key={fact.field} field={fact.field} value={fact.value} />
                ))}
              </Bay>
            </>
          )}
        </div>

        <aside className="myx-doc-detail" aria-label={S.detail}>
          {/* Rendered whether or not the report itself has landed: the upgrade strip reads its own
              route (GET /api/upgrade), and the restart is an action on the daemon rather than on
              the report, so hiding either behind the report hid it entirely. What IS read off the
              report -- the installed version, claude code's, the attention count, the fixes and the
              opened check -- reads `shown`, and prints the absence glyph while there is none: a
              count or a "no fix offered" about a report the page refused would be a claim about
              something it did not read. The `upgrade status not built` empty that stood here
              beside the live strip is gone (M4-07): the route it named as a row is served. */}
          <section className="myx-doc-section">
            <h2 className="myx-doc-section-title">{S.version}</h2>
            <div className="myx-doc-row">
              <Strip
                edge={upgrade === null ? 'grey' : upgradeVerdict(upgrade) === 'behind' ? 'amber' : 'green'}
                edgeLabel={upgrade === null ? S.absent : upgradeVerdict(upgrade)}
                ariaLabel={S.upgrade}
              >
                <StripField w={NARROW} label={S.installed} value={shown?.splice.version ?? S.absent} />
                <StripField w={NARROW} label={S.latest} value={upgrade?.latest ?? S.absent} {...(upgrade === null ? {} : { basis: upgrade.latest_basis })} />
                <StripField
                  w={NARROW}
                  label={S.rollback}
                  value={upgrade === null || upgrade.rollback_basis !== 'measured' ? S.absent : (upgrade.rollback_target ?? S.none)}
                  {...(upgrade === null ? {} : { basis: upgrade.rollback_basis })}
                  mono={false}
                />
              </Strip>
            </div>
            <p className="myx-doc-note">{`claude code ${shown?.claude_code.version ?? S.absent}`}</p>
            <p className="myx-doc-note">{`${shown === null ? S.absent : attentionCount(checks)} need attention`}</p>
            {/* The draining restart (WC-08), the same control the fleet's head detail mounts. */}
            <DaemonRestart />
            <Empty text={EMPTIES.capture.text} source={EMPTIES.capture.source} />
          </section>

          <section className="myx-doc-section">
            <h2 className="myx-doc-section-title">{S.fix}</h2>
            {shown === null ? (
              <p className="myx-doc-note">{S.absent}</p>
            ) : fixes.length === 0 ? (
              <p className="myx-doc-note">{S.noFix}</p>
            ) : (
              fixes.map((check) => (
                <div key={check.id} className="myx-doc-row">
                  <span className="myx-doc-note">{check.id}</span>
                  <code className="myx-doc-fix">{checkFix(check)}</code>
                  <CopyFix command={checkFix(check) ?? ''} />
                </div>
              ))
            )}
          </section>

          {opened === null ? null : (
            <section className="myx-doc-section">
              <div className="myx-doc-row">
                <HolderEdge state={statusEdge(opened.status)} label={opened.status} />
                <span className="myx-doc-note">{opened.id}</span>
              </div>
              <p className="myx-doc-note">{opened.detail}</p>
            </section>
          )}

          <Playground heads={heads} />
        </aside>
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
      upgrade={upgrade.data !== null && 'installed' in upgrade.data ? upgrade.data : null}
      heads={(heads ?? []).map((head) => head.key)}
      openKey={openKey}
      onToggle={toggle}
      sample={import.meta.env.DEV && report !== null && fixture !== null ? fixture : undefined}
    />
  );
}

export default DoctorPage;
