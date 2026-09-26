// V4-313: the opened project's standing prompt ([projects."<root>"] system_prompt, V4-124) and its own
// compaction rule (the [[compaction.project]] row whose path is the root and which names no model),
// edited from the project and written through PUT /api/topology: the structured writer the Settings
// topology uses, which backs the file up, keeps every other byte, and refuses anything that does not
// parse back to what was asked, leaving the file as it was.
//
// BOTH ARE READ AT BOOT. The daemon builds its prompt layers and its compaction rules from the topology
// it started with (Daemon.kt: HeadPromptInputs, CompactionInstructions); only windows reload live
// (V4-162). So the save says what it reaches: the repo's live sessions, at their first turn after the
// daemon restarts, which stays the Fleet page's to do.
//
// A FILE-BACKED FIELD IS SHOWN, NOT EDITED. Inline text beside `system_prompt_file` or a rule's `file`
// is refused ("cannot set both", HeadSystemPrompt), so the form prints the path and leaves that field
// to the file or to the Settings topology.
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchSessions, sessionLabel, useSessionRegistry } from '@entities/session';
import type { SessionRow } from '@entities/session';
import { fetchTopology, saveTopology, useTopology } from '@entities/topology';
import type { TopologyWriteResult } from '@entities/topology';
import { Confirm, Fault } from '@shared/controls';
import { Badge, Empty, InfoTip } from '@shared/ui';
import { H, S } from './strings';

type Table = Record<string, unknown>;

/** What splice.toml holds for one repo: each field's inline text ('' when unset), or the file it is
 *  read from instead. */
export interface Standing {
  prompt: string;
  promptFile: string | null;
  compaction: string;
  compactionFile: string | null;
}

/** The two texts a save writes; '' removes the key. */
export interface StandingEdit {
  prompt: string;
  compaction: string;
}

function tableOf(value: unknown): Table {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Table) : {};
}

function textOf(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/** A copy of [table] without [key]. */
function without(table: Table, key: string): Table {
  return Object.fromEntries(Object.entries(table).filter(([name]) => name !== key));
}

/** A copy of [table] with [key] set to [value], or without it when [value] is '': the daemon reads an
 *  absent prompt and an empty one alike, and an absent one leaves the file as the operator wrote it. */
function withKey(table: Table, key: string, value: string): Table {
  return value === '' ? without(table, key) : { ...table, [key]: value };
}

/** Every [[compaction.project]] row, in the file's order. */
function projectRules(topology: Table): Table[] {
  const rows = tableOf(topology.compaction).project;
  return Array.isArray(rows) ? rows.map(tableOf) : [];
}

/** The repo's own rule: its path, and no model (a model's row is a narrower rule this form leaves). */
function isOwnRule(root: string): (row: Table) => boolean {
  return (row) => row.path === root && row.model === undefined;
}

export function standingOf(topology: Table, root: string): Standing {
  const project = tableOf(tableOf(topology.projects)[root]);
  const rule = projectRules(topology).find(isOwnRule(root)) ?? {};
  return {
    prompt: textOf(project.system_prompt) ?? '',
    promptFile: textOf(project.system_prompt_file),
    compaction: textOf(rule.instructions) ?? '',
    compactionFile: textOf(rule.file),
  };
}

function withPrompt(topology: Table, root: string, prompt: string): Table {
  const projects = tableOf(topology.projects);
  const project = withKey(tableOf(projects[root]), 'system_prompt', prompt);
  const all = Object.keys(project).length === 0 ? without(projects, root) : { ...projects, [root]: project };
  return Object.keys(all).length === 0 ? without(topology, 'projects') : { ...topology, projects: all };
}

function withRule(topology: Table, root: string, instructions: string): Table {
  const rows = projectRules(topology);
  const at = rows.findIndex(isOwnRule(root));
  const own = withKey(rows[at] ?? { path: root }, 'instructions', instructions);
  // A row left with its path alone says nothing, so it goes rather than stay as an empty rule.
  const kept = Object.keys(own).some((key) => key !== 'path') ? [own] : [];
  const project = at === -1 ? [...rows, ...kept] : [...rows.slice(0, at), ...kept, ...rows.slice(at + 1)];
  const compaction = project.length === 0 ? without(tableOf(topology.compaction), 'project') : { ...tableOf(topology.compaction), project };
  return Object.keys(compaction).length === 0 ? without(topology, 'compaction') : { ...topology, compaction };
}

/** [topology] with the repo's two fields set to [next]; a file-backed field is left as it is. Never
 *  mutates its input: the store's copy stays what the daemon last answered. */
export function withStanding(topology: Table, root: string, next: StandingEdit): Table {
  const held = standingOf(topology, root);
  const prompted = held.promptFile === null ? withPrompt(topology, root, next.prompt) : topology;
  return held.compactionFile === null ? withRule(prompted, root, next.compaction) : prompted;
}

/** The sessions running in the repo now, by the name the console gives them everywhere else. */
export function liveSessionsOf(sessions: readonly SessionRow[], root: string): string[] {
  return sessions.filter((row) => row.availability === 'live' && row.repo?.root === root).map(sessionLabel);
}

/** PUT the whole topology with the repo's two fields changed. A refusal rejects with the daemon's
 *  sentence; a key its writer turned down comes back as a finding on the answer. */
export function saveStanding(topology: Table, root: string, next: StandingEdit): Promise<TopologyWriteResult> {
  return saveTopology(withStanding(topology, root, next));
}

/** A multi-line field in the input box's own look, as team-compose's is: the control set has none. */
function Lines({ label, value, onChange }: { label: string; value: string; onChange: (next: string) => void }): ReactNode {
  return (
    <label className="myx-input myx-px-lines">
      <span className="myx-input-label">{label}</span>
      <textarea className="myx-input-box" rows={4} value={value} spellCheck={false} onChange={(event) => onChange(event.target.value)} />
    </label>
  );
}

function FromFile({ label, path }: { label: string; path: string }) {
  return (
    <div className="myx-px-file">
      <span className="myx-input-label">{label}</span>
      <span className="myx-px-file-path">
        <Badge tone="neutral" quiet>{S.fromFile}</Badge> {path}
        <InfoTip text={H.fromFile} label={S.fromFile} />
      </span>
    </div>
  );
}

export function StandingForm({ topology, root, live, busy = false, fault = null, result = null, onSave }: {
  topology: Table;
  root: string;
  /** The repo's live sessions, which the save names before it writes. */
  live: readonly string[];
  busy?: boolean;
  /** The daemon's refusal, verbatim. */
  fault?: string | null;
  result?: TopologyWriteResult | null;
  onSave?: (next: StandingEdit) => void;
}) {
  const held = standingOf(topology, root);
  const [draft, setDraft] = useState<StandingEdit>({ prompt: held.prompt, compaction: held.compaction });
  const changed = draft.prompt !== held.prompt || draft.compaction !== held.compaction;
  return (
    <div className="myx-px-standing">
      {held.promptFile === null
        ? <Lines label={S.prompt} value={draft.prompt} onChange={(prompt) => setDraft({ ...draft, prompt })} />
        : <FromFile label={S.prompt} path={held.promptFile} />}
      {held.compactionFile === null
        ? <Lines label={S.rule} value={draft.compaction} onChange={(compaction) => setDraft({ ...draft, compaction })} />
        : <FromFile label={S.rule} path={held.compactionFile} />}
      <div className="myx-px-reach">
        <span className="myx-input-label">
          {S.reaches}
          <InfoTip text={H.reaches} label={S.reaches} />
        </span>
        <span>{live.length === 0 ? S.noLive : live.join(', ')}</span>
        <span className="myx-px-when">{H.restart}</span>
      </div>
      {changed ? <Confirm label={S.save} confirmLabel={S.confirmSave} busy={busy} onConfirm={() => onSave?.(draft)} /> : null}
      {result === null ? null : (result.findings ?? []).length === 0
        ? <Badge tone="ok" quiet>{S.saved}</Badge>
        : (result.findings ?? []).map((finding) => <Fault key={finding.path} message={`${finding.path}: ${finding.message}`} />)}
      {fault === null ? null : <Fault message={fault} />}
    </div>
  );
}

/** The editor as the page mounts it: the topology and the session registry read when it opens, the
 *  save written through the daemon, and the topology read again after it so the form shows the file. */
export function ProjectStanding({ root }: { root: string }) {
  const topology = useTopology((state) => state);
  // The registry as the store holds it, defaulted here: a selector answering a fresh [] is a new
  // snapshot every render under zustand 5, and the panel re-rendered until it unmounted (V4-318).
  const registry = useSessionRegistry((state) => state.data);
  const sessions = registry?.sessions ?? [];
  const [busy, setBusy] = useState(false);
  const [fault, setFault] = useState<string | null>(null);
  const [result, setResult] = useState<TopologyWriteResult | null>(null);

  useEffect(() => {
    void fetchTopology();
    void fetchSessions();
  }, []);

  if (topology.error !== null) return <Fault message={topology.error} lastRead={topology.lastUpdated} />;
  const payload = topology.data;
  if (payload === null || payload === undefined) return null;
  if ('pending' in payload) return <Empty text={S.unavailable} source={H.unavailable} />;

  const save = (next: StandingEdit) => {
    setBusy(true);
    setFault(null);
    setResult(null);
    void saveStanding(payload.topology, root, next)
      .then(async (written) => {
        setResult(written);
        await fetchTopology();
      })
      .catch((err: unknown) => setFault(err instanceof Error ? err.message : String(err)))
      .finally(() => setBusy(false));
  };

  return (
    <StandingForm
      key={root}
      topology={payload.topology}
      root={root}
      live={liveSessionsOf(sessions, root)}
      busy={busy}
      fault={fault}
      result={result}
      onSave={save}
    />
  );
}
