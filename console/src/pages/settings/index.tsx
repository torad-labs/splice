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
import { HeadMark } from '@entities/control-status';
import { cx } from '@shared/lib';
import type { ClaudeHeadPayload } from '@entities/claude-head';
import { Badge, Bay, Empty, InfoTip, PageHeader, Section } from '@shared/ui';
import { Blank, Fault, Input } from '@shared/controls';
import { HEAD_WORDING, KnobRack, knobMatches } from '@widgets/knob-form';
import { dispositions } from './coverage';
import { changedPaths, DEFAULT_VIEWS, knobsForView, withHeadOverride } from './model';
import { ClaudeModeSection, TopologySection } from './sections';
import { H, S } from './strings';
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
  claude: ClaudeHeadPayload;
}

const PAGE_ID = 'settings';
const POLL_MS = 30000;

/**
 * Save one knob in the global view, and what to print under it: the daemon's refusal of this key,
 * or why the request failed; null when it was applied (V4-305). This was `void
 * applyConfigPatch(...).finally(...)`, the shape V4-175 removed from the page's other writes: a
 * failed PATCH was an unhandled rejection behind a spinner that cleared as if saved, and a 200 whose
 * `rejected` named the key read as saved.
 */
export async function saveGlobalKnob(key: string, value: ConfigValue): Promise<string | null> {
  try {
    const result = await applyConfigPatch({ [key]: value });
    return result.rejected[key] ?? null;
  } catch (err) {
    return err instanceof Error ? err.message : String(err);
  }
}

/**
 * The draft a head's knob save leaves once its write has answered (V4-303). The save PUTs the loaded
 * file plus the override, never the draft, and a draft holding edits of its own was kept as it was,
 * seeded before the override existed: the next Write PUT it and reverted the knob the page had just
 * reported saved. A saved override now goes into that draft too, so both writes start from one base.
 * A draft with no edits re-seeds from the file the save wrote (null); a refused save leaves it as is.
 */
export function draftAfterKnobSave(
  loaded: Record<string, unknown>,
  draft: Record<string, unknown> | null,
  head: string,
  key: string,
  override: ConfigValue,
  saved: boolean,
): Record<string, unknown> | null {
  if (draft === null || changedPaths(loaded, draft).length === 0) return null;
  return saved ? withHeadOverride(draft, head, key, override) : draft;
}

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
  const [knobFaults, setKnobFaults] = useState<ReadonlyMap<string, string>>(new Map());
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
      fixtureClaudeHead?: ClaudeHeadPayload;
    }) => {
      if (module.fixtureConfig === undefined || module.fixtureTopology === undefined || module.fixtureClaudeHead === undefined) {
        setSample(null);
        return;
      }
      setSample({ name: FIXTURE, payload: { config: module.fixtureConfig, topology: module.fixtureTopology, claude: module.fixtureClaudeHead } });
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
    void saveGlobalKnob(key, value)
      .then((fault) => setKnobFaults((held) => {
        const next = new Map(held);
        if (fault === null) next.delete(key);
        else next.set(key, fault);
        return next;
      }))
      .finally(() => setBusyKey(null));
  };

  const saveForHead = (key: string, value: ConfigValue) => {
    if (loaded === null || configPayload === null) return;
    const fallback = globalValueOf(key, configPayload);
    const override = value === null || value === fallback ? null : value;
    setBusyKey(key);
    void saveTopology(withHeadOverride(loaded, head, key, override))
      .then((result) => {
        setWriteResult(result);
        if (result.ok) markRestartPending([key]);
        setDraft((current) => draftAfterKnobSave(loaded, current, head, key, override, result.ok));
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
      if (shadow === 'console') return H.shadowConsole;
      if (shadow === 'environment') return H.shadowEnv;
      return null;
    }
    const by = knob.overriddenBy;
    return by.length === 0 ? null : `${S.overridden} ${by.join(', ')}. ${H.overridden}`;
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
      <PageHeader title={S.title} info={{ text: H.about, label: S.about }} actions={sample === null ? undefined : <Badge tone="neutral">{S.sample}</Badge>}>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {config.error === null ? null : <Fault message={config.error} lastRead={sample === null ? config.lastUpdated : null} />}
      {topology.error === null ? null : <Fault message={topology.error} lastRead={sample === null ? topology.lastUpdated : null} />}
      {claude.error === null ? null : <Fault message={claude.error} lastRead={sample === null ? claude.lastUpdated : null} />}
      {claudeFault === null ? null : <Fault message={claudeFault} />}

      <Section title={S.knobs} {...(configPayload === null ? {} : { count: shown.length })} className="myx-settings-section">
        {/* The scope: every head's own values, or the global ones. Each head wears its colour mark
            (DESIGN.md section 5); which one is chosen is the pressed state and the active ground. */}
        <div className="myx-settings-scope-row">
          <div className="myx-settings-scope" role="group" aria-label={S.scope}>
            {headOptions(configPayload?.layers.perHead, heads.map((row) => row.key)).map((option) => (
              <button
                key={option}
                type="button"
                className={cx('myx-settings-head-option', option === head && 'myx-settings-head-option-active')}
                aria-pressed={option === head}
                onClick={() => setHead(option)}
              >
                {option === 'global' ? S.global : <HeadMark head={option} />}
              </button>
            ))}
          </div>
          {/* What saving reaches in this view, behind the mark beside the scope it describes. */}
          <InfoTip text={perHeadView ? H.headScope : H.globalScope} label={S.scopeWhy} side="bottom" />
        </div>
        <div className="myx-settings-tools">
          <Input label={S.find} value={query} onChange={setQuery} w={32} placeholder={S.findHint} />
          {pendingRestart.length === 0 ? null : (
            <p className="myx-settings-pending">
              <Badge tone="warn">{S.restart}</Badge>
              <span className="myx-settings-note">{pendingRestart.join(', ')}</span>
            </p>
          )}
        </div>
        {configPayload === null ? <Blank strips={6} /> : shown.length === 0 ? (
          <Empty text={S.noKnobs} source={H.noKnobs} />
        ) : (
          <KnobRack
            dispositions={shown}
            pending={pendingRestart}
            busyKey={busyKey}
            onSave={perHeadView ? saveForHead : saveGlobal}
            scopeNote={scopeNote}
            {...(perHeadView ? { wording: HEAD_WORDING } : { faultOf: (knob: KnobDisposition) => knobFaults.get(knob.key) ?? null })}
            perHead={perHeadView}
          />
        )}
      </Section>

      <Section title={S.topology} className="myx-settings-section">
        {topologyState === null ? <Blank strips={4} /> : null}
        {fixture === null && topologyState === null ? (
          <Empty text={S.noConfig} source={H.noConfig} />
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
              empty={{ text: S.noHeads, source: H.noHeads }}
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
      </Section>

      <Section title={S.claudeHead} className="myx-settings-section">
        <ClaudeModeSection
          state={fixture === null ? claude.data : fixture.claude}
          result={claudeResult}
          busy={busyClaude}
          onWrap={() => action(wrapClaudeHead)}
          onUnwrap={() => action(unwrapClaudeHead)}
        />
      </Section>
    </div>
  );
}

export default SettingsPage;
