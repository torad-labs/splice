// Settings: the console's half of "no reason to open splice.toml by hand" (shape brief section 2).
//
// The page is FORMS FIRST, which is what the row asks for and what the world allows: a strip is a
// focusable button, so it cannot hold an input. Everything here is a bay of field boxes instead,
// and the strip vocabulary arrives through the bay frames, the holder edges and the figure face.
//
// Three sources, three shapes, one page:
//   * the runtime knobs, from GET /api/config, each with the layer its value came from and whether
//     saving a new one does anything now;
//   * the topology document, from GET /api/topology (PENDING V4-128), as forms per key plus the
//     raw text and a diff behind reveals;
//   * the Claude head's mode, from GET /api/claude-head (PENDING V4-129).
//
// A route that does not exist yet renders the honest empty naming its v0.4.0 row. Nothing on this
// page is ever mocked.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import {
  applyConfigPatch,
  fetchConfig,
  headOptions,
  knobDispositions,
  useConfig,
  useRestartPending,
} from '@entities/config';
import type { ConfigValue } from '@shared/api';
// `fetchClaudeHead` is not imported: the poll below runs it on its own first tick.
import { startClaudeHeadPolling, unwrapClaudeHead, useClaudeHead, wrapClaudeHead } from '@entities/claude-head';
import {
  fetchTopology,
  saveTopology,
  startTopologyPolling,
  useTopology,
} from '@entities/topology';
import type { TopologyWriteResult } from '@entities/topology';
import { HeadAddForm, HeadEditRow, headRows } from '@features/head-edit';
import { useViews, ViewTabs } from '@features/views';
import { Bay, Btn, Empty, ErrorNote, HolderEdge, SkeletonRows } from '@shared/ui';
import { KnobRack } from '@widgets/knob-form';
import { dispositions } from './coverage';
import { DEFAULT_VIEWS, EMPTIES, knobsForView } from './model';
import { ClaudeModeSection, TopologySection } from './sections';
import { fixtureConfig, fixtureName, fixtureTopology } from './fixtures/settings';
import { S } from './strings';
import './settings.css';

export { dispositions };

const PAGE_ID = 'settings';
const POLL_MS = 30000;

export function SettingsPage() {
  const { search } = useLocation();
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const config = useConfig((state) => state);
  const topology = useTopology((state) => state);
  const claude = useClaudeHead((state) => state);
  const pendingRestart = useRestartPending((state) => state.pending);

  const [head, setHead] = useState('global');
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null);
  const [writeResult, setWriteResult] = useState<TopologyWriteResult | null>(null);
  const [busyTopology, setBusyTopology] = useState(false);
  const [busyClaude, setBusyClaude] = useState(false);

  useEffect(() => startTopologyPolling(POLL_MS), []);
  useEffect(() => startClaudeHeadPolling(POLL_MS), []);
  useEffect(() => {
    void fetchConfig(head === 'global' ? undefined : head);
  }, [head]);

  const fixture = fixtureName(search, import.meta.env.DEV);
  const configPayload = fixture === null ? config.data : fixtureConfig;

  const topologyState = topology.data;
  const loaded =
    fixture !== null
      ? fixtureTopology
      : topologyState !== null && topologyState !== undefined && !('pending' in topologyState)
        ? topologyState.topology
        : null;

  // Seed the draft once per loaded document. The draft is what the forms edit and what the diff
  // and the write are computed from, so it must not be re-seeded under the operator mid-edit.
  useEffect(() => {
    if (loaded !== null && draft === null) setDraft(loaded);
  }, [loaded, draft]);

  const knobs = configPayload === null ? [] : knobDispositions(configPayload, head === 'global' ? undefined : head);
  const shown = knobsForView(knobs, views.active);
  const heads = headRows(draft ?? {});

  const save = (key: string, value: ConfigValue) => {
    setBusyKey(key);
    void applyConfigPatch({ [key]: value }, head === 'global' ? undefined : head).finally(() => setBusyKey(null));
  };

  const writeTopology = () => {
    if (draft === null) return;
    setBusyTopology(true);
    void saveTopology(draft)
      .then((result) => {
        setWriteResult(result);
        // The daemon writes a document it read itself; re-reading is how the page stops showing a
        // draft as if it were the file.
        return fetchTopology();
      })
      .catch((err: unknown) => setWriteResult({ ok: false, restart_required: true, findings: [{ path: '', message: err instanceof Error ? err.message : String(err) }] }))
      .finally(() => setBusyTopology(false));
  };

  const action = (run: () => Promise<unknown>) => {
    setBusyClaude(true);
    void run().finally(() => setBusyClaude(false));
  };

  return (
    <div className="myx-settings">
      <header className="myx-settings-head">
        <h1 className="myx-settings-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {fixture === null ? null : <HolderEdge state="grey" label={S.sample} />}
      </header>

      {config.error === null ? null : <ErrorNote message={config.error} />}
      {topology.error === null ? null : <ErrorNote message={topology.error} />}
      {claude.error === null ? null : <ErrorNote message={claude.error} />}

      <section className="myx-settings-section">
        <h2 className="myx-settings-title">{S.knobs}</h2>
        <div className="myx-settings-row">
          {headOptions(configPayload?.layers.perHead).map((option) => (
            <Btn key={option} kind={option === head ? 'primary' : 'control'} onClick={() => setHead(option)}>
              {option}
            </Btn>
          ))}
        </div>
        {configPayload === null ? <SkeletonRows rows={6} cols={3} /> : null}
        {pendingRestart.length === 0 ? null : (
          <div className="myx-settings-row">
            <HolderEdge state="amber" label={S.restart} />
            <span className="myx-settings-note">{pendingRestart.join(', ')}</span>
          </div>
        )}
        <Bay
          label={S.knobs}
          count={shown.length}
          empty={{ text: EMPTIES.noKnobs.text, source: EMPTIES.noKnobs.source }}
        >
          {configPayload === null ? null : (
            <KnobRack dispositions={shown} pending={pendingRestart} busyKey={busyKey} onSave={save} />
          )}
        </Bay>
      </section>

      <section className="myx-settings-section">
        <h2 className="myx-settings-title">{S.topology}</h2>
        {topologyState === null ? <SkeletonRows rows={4} cols={2} /> : null}
        {fixture === null && topologyState === null ? (
          <Empty text={EMPTIES.noConfig.text} source={EMPTIES.noConfig.source} />
        ) : (
          <>
            <TopologySection
              state={fixture === null ? topologyState ?? { pending: 'V4-128' } : { path: '~/.config/splice/splice.toml', topology: fixtureTopology, stale: false }}
              loaded={loaded}
              draft={draft}
              onDraft={setDraft}
              onWrite={writeTopology}
              busy={busyTopology}
              result={writeResult}
            />
            <Bay
              label={S.topology}
              count={heads.length}
              empty={{ text: EMPTIES.noHeads.text, source: EMPTIES.noHeads.source }}
            >
              {heads.map((row) => (
                <HeadEditRow
                  key={row.key}
                  topology={draft ?? {}}
                  row={row}
                  busy={false}
                  onChange={setDraft}
                />
              ))}
              {draft === null ? null : <HeadAddForm topology={draft} onAdd={setDraft} />}
            </Bay>
          </>
        )}
      </section>

      <section className="myx-settings-section">
        <h2 className="myx-settings-title">{S.claudeHead}</h2>
        <ClaudeModeSection
          state={fixture === null ? (claude.data ?? { pending: 'V4-129' }) : {
            mode: 'separate',
            head: 'claude-splice',
            config_dir: '~/.config/splice/claude-splice',
            auth_kind: 'client',
            claude_on_path: '~/.local/bin/claude',
            wrap_supported: true,
          }}
          busy={busyClaude}
          onWrap={() => action(wrapClaudeHead)}
          onUnwrap={() => action(unwrapClaudeHead)}
        />
      </section>
    </div>
  );
}

export default SettingsPage;
