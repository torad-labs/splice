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
//   * the Claude head's mode, from GET /api/claude-head, switched through POST /wrap and /unwrap.
//
// A route that does not exist yet renders the honest empty naming its v0.4.0 row. Nothing on this
// page is ever mocked.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import {
  applyConfigPatch,
  fetchConfig,
  globalValueOf,
  headOptions,
  knobDispositions,
  markRestartPending,
  shadowOfOverride,
  useConfig,
  useRestartPending,
} from '@entities/config';
import type { KnobDisposition } from '@entities/config';
import type { ConfigPayload, ConfigValue } from '@shared/api';
// `fetchClaudeHead` is not imported: the poll below runs it on its own first tick.
import { startClaudeHeadPolling, unwrapClaudeHead, useClaudeHead, wrapClaudeHead } from '@entities/claude-head';
import type { ClaudeHeadActionResult } from '@entities/claude-head';
import {
  fetchTopology,
  saveTopology,
  startTopologyPolling,
  useTopology,
} from '@entities/topology';
import type { TopologyWriteResult } from '@entities/topology';
import { HeadAddForm, HeadEditRow, headRows } from '@features/head-edit';
import { useViews, ViewTabs } from '@features/views';
import { cx } from '@shared/lib';
import { Bay, Empty, HolderEdge } from '@shared/ui';
import { Blank, Fault, Input } from '@shared/controls';
import { HEAD_WORDING, KnobRack, knobMatches } from '@widgets/knob-form';
import { dispositions } from './coverage';
import { changedPaths, DEFAULT_VIEWS, EMPTIES, knobsForView, withHeadOverride } from './model';
import { ClaudeModeSection, TopologySection } from './sections';
import { S } from './strings';
import './settings.css';

export { dispositions };

/** The name this page accepts in the hash query, declared HERE rather than in the fixture module:
 *  importing that module for one constant is enough to make the whole fixture a build dependency
 *  (CONTRACTS.md section 4). */
const FIXTURE = 'settings';

/** Whether the address asks for THIS page's fixture, by that fixture's own FILE name. Exported
 *  because the capture marker's whole value rests on it (law 23): a name this page does not carry
 *  is not a fixture, so the page must end with no marker rather than a stale one, and a test pins
 *  that here rather than inferring it from a rendered label. */
export function wantsFixture(search: string): boolean {
  return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;
}


/** The sample the fixture module hands over once it has loaded. */
interface SettingsFixture {
  config: ConfigPayload;
  topology: Record<string, unknown>;
}

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
  const [query, setQuery] = useState('');
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [draft, setDraft] = useState<Record<string, unknown> | null>(null);
  const [writeResult, setWriteResult] = useState<TopologyWriteResult | null>(null);
  const [busyTopology, setBusyTopology] = useState(false);
  const [busyClaude, setBusyClaude] = useState(false);
  const [claudeResult, setClaudeResult] = useState<ClaudeHeadActionResult | null>(null);
  const [claudeFault, setClaudeFault] = useState<string | null>(null);

  useEffect(() => startTopologyPolling(POLL_MS), []);
  useEffect(() => startClaudeHeadPolling(POLL_MS), []);
  useEffect(() => {
    void fetchConfig(head === 'global' ? undefined : head);
  }, [head]);

  const [sample, setSample] = useState<{ name: string; payload: SettingsFixture } | null>(null);
  const fixture = sample === null ? null : sample.payload;

  // The fixture loads through a DYNAMIC import inside the DEV branch: a static import — even of one
  // constant — is a dependency edge the bundler honours, so the fixture module and its strings
  // would ship inside the single-file console. The page renders the store's payload while the
  // module loads and swaps in the sample when it arrives.
  useEffect(() => {
    if (!wantsFixture(search)) {
    // The address no longer asks for this page's fixture, so the marker must GO: a name that is
    // asked for and then dropped is exactly the stale marker this row exists to prevent (measured
    // in a browser on 2026-09-18 - five pages kept one across a hash change, because the early
    // return left the previous state in place; a static render cannot see an effect, so the suite
    // was green while it happened).
      setSample(null);
      return;
    }
    // The specifier is BUILT AT RUNTIME, not written as a literal: a statically analyzable
    // `import('./fixtures/x')` stays a dependency edge through the single-file build even when the
    // branch around it is dead, so the module's bytes are inlined into dist/index.html (measured
    // 2026-09-18: this page shipped its own literals that way; the pages that compose the specifier
    // at runtime shipped none). CONTRACTS.md section 4 asks for the dynamic import; this is the half
    // of it the bundler can actually drop.
    void import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`).then((module: {
      fixtureConfig?: ConfigPayload;
      fixtureTopology?: Record<string, unknown>;
    }) => {
      if (module.fixtureConfig === undefined || module.fixtureTopology === undefined) {
        setSample(null);
        return;
      }
      setSample({ name: FIXTURE, payload: { config: module.fixtureConfig, topology: module.fixtureTopology } });
    }).catch(() => undefined);
  }, [search]);

  const configPayload = fixture === null ? config.data : fixture.config;

  const topologyState = topology.data;
  const loaded =
    fixture !== null
      ? fixture.topology
      : topologyState !== null && topologyState !== undefined && !('pending' in topologyState)
        ? topologyState.topology
        : null;

  // Seed the draft once per loaded document. The draft is what the forms edit and what the diff
  // and the write are computed from, so it must not be re-seeded under the operator mid-edit.
  useEffect(() => {
    if (loaded !== null && draft === null) setDraft(loaded);
  }, [loaded, draft]);

  const perHeadView = head !== 'global';
  const knobs: KnobDisposition[] = configPayload === null
    ? []
    : knobDispositions(configPayload, perHeadView ? head : undefined).map((knob) =>
      // In a head's view "changed" means "differs from what every other head gets", and the way
      // back is to drop the override, so the reference value is the global one.
      perHeadView ? { ...knob, defaultValue: globalValueOf(knob.key, configPayload) } : knob);
  const shown = knobsForView(knobs, views.active).filter((knob) => knobMatches(knob.key, query));
  const heads = headRows(draft ?? {});

  // THE GLOBAL VIEW SAVES THROUGH PATCH, WHICH REACHES EVERY HEAD. The daemon has no per-head
  // PATCH (ConfigRoutes.patchConfig: "no per-head fanout"), so a head's view used to send the same
  // global PATCH while showing one head's values, and the saved value landed in the state file,
  // which outranks every [heads.<key>.overrides] (FEATURES 2.2). A head's view now writes that
  // head's override into splice.toml through the daemon's structured writer, which keeps every
  // comment and untouched line; saving the global value drops the override instead.
  const saveGlobal = (key: string, value: ConfigValue) => {
    setBusyKey(key);
    void applyConfigPatch({ [key]: value }).finally(() => setBusyKey(null));
  };

  const saveForHead = (key: string, value: ConfigValue) => {
    if (loaded === null || configPayload === null) return;
    const fallback = globalValueOf(key, configPayload);
    const next = withHeadOverride(loaded, head, key, value === null || value === fallback ? null : value);
    const hadEdits = draft !== null && changedPaths(loaded, draft).length > 0;
    setBusyKey(key);
    void saveTopology(next)
      .then((result) => {
        setWriteResult(result);
        if (result.ok) markRestartPending([key]);
        // Re-seed the topology form from the file just written, unless it holds edits of its own.
        if (!hadEdits) setDraft(null);
        return Promise.all([fetchTopology(), fetchConfig(head)]);
      })
      .catch((err: unknown) => setWriteResult({ ok: false, restart_required: true, findings: [{ path: '', message: err instanceof Error ? err.message : String(err) }] }))
      .finally(() => setBusyKey(null));
  };

  /** What saving a knob reaches, when that is more than the knob on this row. */
  const scopeNote = (knob: KnobDisposition): string | null => {
    if (configPayload === null) return null;
    if (perHeadView) {
      const shadow = shadowOfOverride(knob.key, configPayload);
      if (shadow === 'console') return 'A value set in the console for every head outranks this head\'s own. Reset it in the global view first.';
      if (shadow === 'environment') return 'The environment sets this for every head, which outranks this head\'s own value.';
      return null;
    }
    const by = knob.overriddenBy;
    if (by.length === 0) return null;
    return `${by.join(', ')} ${by.length === 1 ? 'sets its own value' : 'set their own values'} in splice.toml. A value saved here replaces ${by.length === 1 ? 'it' : 'them'}.`;
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

  // V4-175: this used to be `void run().finally(...)`, which dropped BOTH halves of the answer —
  // the backup paths a wrap reports, and the 409 sentence a refusal carries ("claude is not
  // currently wrapped"). A refused click printed nothing at all and left an unhandled rejection.
  const action = (run: () => Promise<ClaudeHeadActionResult>) => {
    setBusyClaude(true);
    setClaudeFault(null);
    void run()
      .then(setClaudeResult)
      .catch((err: unknown) => setClaudeFault(err instanceof Error ? err.message : String(err)))
      .finally(() => setBusyClaude(false));
  };

  return (
    <div
      className="myx-settings"
      {...(import.meta.env.DEV && sample !== null ? { 'data-sample': sample.name } : {})}
    >
      <header className="myx-page-head">
        <h1 className="myx-page-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {sample === null ? null : <HolderEdge state="grey" label={S.sample} />}
      </header>

      {config.error === null ? null : <Fault message={config.error} />}
      {topology.error === null ? null : <Fault message={topology.error} />}
      {claude.error === null ? null : <Fault message={claude.error} />}
      {claudeFault === null ? null : <Fault message={claudeFault} />}

      <section className="myx-settings-section">
        <h2 className="myx-settings-title">{S.knobs}</h2>
        <div className="myx-settings-row">
          {/* The rail's selector idiom: the active option prints a green holder edge and the rest
              a grey one, so which head these knobs describe is a printed word and not a colour. */}
          {headOptions(configPayload?.layers.perHead, heads.map((row) => row.key)).map((option) => (
            <button
              key={option}
              type="button"
              className={cx('myx-settings-head-option', option === head && 'myx-settings-head-option-active')}
              aria-pressed={option === head}
              onClick={() => setHead(option)}
            >
              <HolderEdge state={option === head ? 'green' : 'grey'} label={option} />
            </button>
          ))}
        </div>
        {/* What saving reaches in this view. A sentence, not a label (CONTRACTS.md section 4). */}
        <p className="myx-settings-note">
          {perHeadView
            ? `Values saved here become ${head}'s own, written to its overrides in splice.toml. They take effect after a daemon restart.`
            : 'Values saved here apply to every head. Pick a head above to give it a value of its own.'}
        </p>
        <Input label={S.find} value={query} onChange={setQuery} w={32} placeholder={S.findHint} />
        {configPayload === null ? <Blank strips={6} /> : null}
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
            <KnobRack
              dispositions={shown}
              pending={pendingRestart}
              busyKey={busyKey}
              onSave={perHeadView ? saveForHead : saveGlobal}
              scopeNote={scopeNote}
              {...(perHeadView ? { wording: HEAD_WORDING } : {})}
            />
          )}
        </Bay>
      </section>

      <section className="myx-settings-section">
        <h2 className="myx-settings-title">{S.topology}</h2>
        {topologyState === null ? <Blank strips={4} /> : null}
        {fixture === null && topologyState === null ? (
          <Empty text={EMPTIES.noConfig.text} source={EMPTIES.noConfig.source} />
        ) : (
          <>
            <TopologySection
              state={fixture === null ? topologyState ?? { pending: 'V4-128' } : { path: '~/.config/splice/splice.toml', topology: fixture.topology, stale: false }}
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
          state={fixture === null ? claude.data : {
            mode: 'separate',
            resolves_to: '~/.local/share/claude/versions/2.1.257',
            shim_path: '~/.local/share/splice/splice-launch',
            real_binary_path: null,
            claude_logins: {
              count: 2,
              selected: 'work',
              labels: ['personal', 'work'],
              constraint: 'one login per Claude head at a time, chosen at session launch; no mid-session switch',
            },
          }}
          result={claudeResult}
          busy={busyClaude}
          onWrap={() => action(wrapClaudeHead)}
          onUnwrap={() => action(unwrapClaudeHead)}
        />
      </section>
    </div>
  );
}

export default SettingsPage;
