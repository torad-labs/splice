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
import { checkFix, fetchUpgrade, isRedacted, leaksIn, startDoctorPolling, useDoctor, useUpgrade, upgradeVerdict } from '@entities/doctor';
import type { DoctorCheck } from '@entities/doctor';
import { fetchHeads, useHeads } from '@entities/heads';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { Bay, Empty, FieldBox, HolderEdge, Reveal, Strip, StripField } from '@shared/ui';
import { Blank, Fault } from '@shared/controls';
import { EMPTIES, attentionCount, canSend, groupChecks, playgroundNext, statusEdge, wantsAttention, IDLE_PLAYGROUND } from './model';
import type { PlaygroundEvent } from './model';
import { fixtureDoctor } from './fixtures/doctor';
import { fixtureName } from './model';
import { dispositions } from './coverage';
import { S } from './strings';
import './doctor.css';

export { dispositions };

const PAGE_ID = 'doctor';
const POLL_MS = 60000;
const WIDE = 22;
const NARROW = 10;

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
      <StripField w={WIDE} label={S.checks} value={check.id} mono={false} />
      {/* No status field: the holder edge above prints the identical word on every strip (m1
          design review B10). A check with nothing to fix prints the absence glyph in the fix
          cell; the sentence `no fix offered` is what the opened check's note says, which is where
          a Doctor fix's paragraph belongs. */}
      <StripField w={WIDE} label={S.fix} value={fix ?? S.absent} mono={false} />
    </Strip>
  );
}

/** The playground. A Reveal panel, not a page: the rail has thirteen addresses and no room for a
 *  fourteenth, and a prompt sent once is not a destination. */
function Playground({ heads }: { heads: readonly string[] }) {
  const [state, dispatch] = useState(IDLE_PLAYGROUND);
  const send = (event: PlaygroundEvent) => dispatch((current) => playgroundNext(current, event));

  if (state.step === 'pending') {
    return <Empty text={EMPTIES.playground.text} source={`row ${state.note ?? 'V4-133'}`} />;
  }

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
          <button type="button" className="myx-doc-btn" disabled={!canSend(state)} onClick={() => send({ kind: 'send' })}>
            {S.send}
          </button>
          <button type="button" className="myx-doc-btn" onClick={() => send({ kind: 'reset' })}>{S.clear}</button>
        </div>
        {/* The bodies are printed from THIS component's state and written nowhere: no store, no
            storage, no history. A second send drops them at the only moment a run begins. */}
        {state.response === null ? null : (
          <>
            <p className="myx-doc-note">{S.request}</p>
            <pre className="myx-doc-fix">{JSON.stringify(state.request, null, 2)}</pre>
            <p className="myx-doc-note">{S.response}</p>
            <pre className="myx-doc-fix">{JSON.stringify(state.response, null, 2)}</pre>
          </>
        )}
        {state.note === null || state.step !== 'failed' ? null : (
          <p className="myx-doc-note" role="alert">{state.note}</p>
        )}
      </div>
    </Reveal>
  );
}

export function DoctorPage() {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const active = views.active;
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
  const payload = report ?? (doctor.data !== null && 'checks' in doctor.data ? doctor.data : null);
  const pending = report === null && doctor.data !== null && 'pending' in doctor.data ? doctor.data.pending : null;

  // The redaction gate, computed once per payload. A leak is reported as a COUNT and a PATH set,
  // never the value: a leak reporter that echoed the match would be the leak.
  const leaks = useMemo(() => (payload === null ? [] : leaksIn(payload)), [payload]);
  const clean = payload === null ? true : isRedacted(payload);

  const checks = payload?.checks ?? [];
  const groups = groupChecks(checks, active);
  const opened = checks.find((check) => check.id === openKey) ?? null;
  const fixes = checks.filter((check) => checkFix(check) !== null);
  const upgradePayload = upgrade.data !== null && 'installed' in upgrade.data ? upgrade.data : null;
  const headsKeys = (heads ?? []).map((head) => head.key);

  return (
    <div
      className="myx-doc"
      {...(import.meta.env.DEV && report !== null && fixture !== null ? { 'data-sample': fixture } : {})}
    >
      <header className="myx-doc-head">
        <h1 className="myx-doc-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {doctor.error === null ? null : <Fault message={doctor.error} />}

      {pending !== null ? <Empty text={EMPTIES.noReport.text} source={`row ${pending}`} /> : null}
      {payload === null && pending === null ? <Blank strips={4} /> : null}

      {/* The gate. A payload that still carries a credential shape is refused, by path, and never
          rendered: the strips below would be the leak. */}
      {clean ? null : (
        <Empty text="report refused, leaks found" source={leaks.map((leak) => `${leak.kind} at ${leak.where}`).join('; ')} />
      )}

      <div className="myx-doc-body">
        <div className="myx-doc-bays">
          {payload === null || !clean ? null : checks.length === 0 ? (
            <Empty text={EMPTIES.noChecks.text} source={EMPTIES.noChecks.source} />
          ) : (
            groups.map((group) => (
              <Bay key={group.key} label={group.key} count={group.checks.length}>
                {group.checks.map((check) => (
                  <CheckStrip
                    key={check.id}
                    check={check}
                    selected={openKey === check.id}
                    onOpen={() => toggle(check.id)}
                  />
                ))}
              </Bay>
            ))
          )}
        </div>

        <aside className="myx-doc-detail" aria-label={S.detail}>
          {/* Rendered whether or not the report itself has landed: these three empties name rows
              the operator is waiting on, and hiding them behind the report hid them entirely. */}
          <section className="myx-doc-section">
            <h2 className="myx-doc-section-title">{S.version}</h2>
            <div className="myx-doc-row">
              <Strip
                edge={upgradePayload === null ? 'grey' : upgradeVerdict(upgradePayload) === 'behind' ? 'amber' : 'green'}
                edgeLabel={upgradePayload === null ? S.absent : upgradeVerdict(upgradePayload)}
                ariaLabel={S.upgrade}
              >
                <StripField w={NARROW} label={S.installed} value={payload?.splice.version ?? S.absent} />
                <StripField w={NARROW} label={S.latest} value={upgradePayload?.latest ?? S.absent} />
                <StripField w={NARROW} label={S.rollback} value={upgradePayload === null ? S.absent : String(upgradePayload.rollback_available)} mono={false} />
              </Strip>
            </div>
            <p className="myx-doc-note">{`claude code ${payload?.claude_code.version ?? S.absent}`}</p>
            <p className="myx-doc-note">{`${attentionCount(checks)} need attention`}</p>
            <Empty text={EMPTIES.upgrade.text} source={EMPTIES.upgrade.source} />
            <Empty text={EMPTIES.restart.text} source={EMPTIES.restart.source} />
            <Empty text={EMPTIES.capture.text} source={EMPTIES.capture.source} />
          </section>

          <section className="myx-doc-section">
            <h2 className="myx-doc-section-title">{S.fix}</h2>
            {fixes.length === 0 ? (
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

          <Playground heads={headsKeys} />
        </aside>
      </div>
    </div>
  );
}

export default DoctorPage;
