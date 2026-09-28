// The two sections that are not the knob rack: the topology document and the Claude head's mode.
//
// Both are exported standalone rather than inlined into the page so the tests can render them
// directly with a fixture payload — a page that reads the router cannot be static-rendered, and a
// section that has to be reached through one would be untestable for no reason.
import { useEffect, useState } from 'react';
import { Badge, Bay, Empty, Figure, HolderEdge, InfoTip, Reveal } from '@shared/ui';
import { Choice, Confirm, Fault, Flag, Input, Key } from '@shared/controls';
import { fmtInt } from '@shared/lib';
import type { ClaudeHeadActionResult, ClaudeHeadPayload } from '@entities/claude-head';
import { previewInstructionFile, validateTopology } from '@entities/topology';
import type { InstructionFilePreview, TopologyState, TopologyWriteResult } from '@entities/topology';
import { TomlEditor, TomlMerge } from '@widgets/toml-editor';
import { changedPaths, coerce, commandInstructionsOf, headOverrideOf, parseList, setAtPath, topologyTables, toToml, withCommandInstructions, withDefaultInstructions } from './model';
import type { CommandInstruction, TopologyField, TopologyTable } from './model';
import { H, S } from './strings';

/** A list's line, edited as text and written back on leaving the box: parsing on every key would
 *  eat the comma the operator just typed before the next item. */
function ListInput({ field, onCommit }: { field: TopologyField; onCommit: (next: (string | number)[]) => void }) {
  const items = field.value as readonly (string | number)[];
  const [text, setText] = useState(items.join(', '));
  return (
    <span onBlur={() => onCommit(parseList(text, items))}>
      <Input label={field.key} value={text} onChange={setText} w={Math.min(72, Math.max(24, text.length + 2))} placeholder={S.listHint} />
    </span>
  );
}

/** One value's control, chosen by what the value is. */
function TopologyControl({ field, onChange }: { field: TopologyField; onChange: (value: unknown) => void }) {
  if (field.kind === 'flag') {
    return (
      <span className="myx-topo-flag">
        <span className="myx-topo-key">{field.key}</span>
        <Flag on={field.value === true} onLabel={S.on} offLabel={S.off} ariaLabel={field.key} onChange={onChange} />
      </span>
    );
  }
  if (field.kind === 'choice') {
    const current = String(field.value);
    const values = field.choices?.includes(current) ? field.choices : [...(field.choices ?? []), current];
    return <Choice label={field.key} value={current} options={values.map((value) => ({ value, label: value }))} onChange={onChange} w={24} />;
  }
  if (field.kind === 'list') {
    return <ListInput key={(field.value as readonly unknown[]).join(',')} field={field} onCommit={onChange} />;
  }
  const text = String(field.value);
  return (
    <Input
      label={field.key}
      value={text}
      numeric={field.kind === 'number'}
      onChange={(raw) => onChange(headOverrideOf(field.path) !== null && raw.trim() === ''
        ? '' : coerce(raw, field.value as string | number))}
      w={Math.min(72, Math.max(field.kind === 'number' ? 10 : 16, text.length + 2))}
    />
  );
}

/** The table's name under its group's bay: `claudex.overrides` under `heads`, nothing for the
 *  group's own table (`daemon`), whose fields sit directly under the bay's label. */
function tableTitle(table: TopologyTable, group: string): string {
  return table.path === group ? '' : table.path.slice(group.length + 1);
}

/**
 * The document as forms: a bay per top-level table, a block per table inside it, and one control
 * per value, a switch, a picker, a number, a line or a list.
 *
 * The validator's findings are shown BESIDE the draft rather than blocking the write: the daemon's
 * own writer re-validates and its refusal is the authority, so a console that refused first would
 * only be adding a second opinion to the same question.
 */
export function TopologySection({ state, loaded, draft, onDraft, onWrite, busy, result, scope = 'all' }: {
  state: TopologyState;
  loaded: Record<string, unknown> | null;
  draft: Record<string, unknown> | null;
  onDraft: (next: Record<string, unknown>) => void;
  onWrite: () => void;
  busy: boolean;
  result: TopologyWriteResult | null;
  scope?: 'all' | 'instructions' | 'other';
}) {
  if (typeof state === 'object' && state !== null && 'pending' in state) {
    return <Empty text={S.topologyUnavailable} source={H.topologyUnavailable} />;
  }
  if (draft === null) return null;

  const groups = new Map<string, TopologyTable[]>();
  for (const table of topologyTables(draft)) {
    const group = table.path.split(/[.[]/)[0] ?? '';
    if (scope === 'instructions' && group !== 'compaction') continue;
    if (scope === 'other' && group === 'compaction') continue;
    groups.set(group, [...(groups.get(group) ?? []), table]);
  }

  const changed = loaded === null ? [] : changedPaths(loaded, draft)
    .filter((path) => scope === 'all' || (path.startsWith('compaction.') === (scope === 'instructions')));
  const findings = validateTopology(draft).filter((finding) => scope === 'all'
    || (finding.path.startsWith('compaction') === (scope === 'instructions')));

  return (
    <div className="myx-settings-topology">
      {/* The file and what writing it does, once for the section: the path, the backup and the
          layout kept behind the info mark (FEATURES 4.7: "Backs the file up first"), and the
          restart as the edge a stale file carries. */}
      <div className="myx-settings-file">
        <span className="myx-settings-label">{S.file}</span>
        <span className="myx-settings-path">{state.path}</span>
        <InfoTip text={H.topologyWrite} label={S.topologyWhy} />
        {state.stale ? <HolderEdge state="amber" label={S.restart} /> : null}
      </div>

      {scope === 'instructions' && !topologyTables(draft).some((table) => table.fields.some((field) => field.path === 'compaction.instructions')) ? (
        <Empty text={S.noInstructions} source={H.noInstructions}
          action={<Key onClick={() => onDraft(withDefaultInstructions(draft))}>{S.addInstructions}</Key>} />
      ) : null}
      {[...groups.entries()].map(([group, tables]) => (
        <Bay key={group} label={group === 'compaction' && scope === 'instructions' ? S.compactionInstructions
          : group === '' ? S.topLevel : group} count={tables.length}>
          {tables.map((table) => (
            <section key={table.path} className="myx-topo-table" aria-label={table.path || S.topLevel}>
              {tableTitle(table, group) === '' ? null : <h4 className="myx-topo-title">{tableTitle(table, group)}</h4>}
              <div className="myx-topo-fields">
                {table.fields.map((field) => headOverrideOf(field.path) === null ? (
                  <TopologyControl key={field.path} field={field} onChange={(value) => onDraft(setAtPath(draft, field.path, value))} />
                ) : (
                  <span key={field.path} className="myx-settings-row myx-topo-override">
                    <TopologyControl field={field} onChange={(value) => onDraft(setAtPath(draft, field.path, value))} />
                    <Key ariaLabel={`Remove ${field.key} override`} disabled={busy}
                      onClick={() => onDraft(setAtPath(draft, field.path, ''))}>{S.removeOverride}</Key>
                  </span>
                ))}
              </div>
            </section>
          ))}
        </Bay>
      ))}

      {findings.length === 0 ? null : (
        <ul className="myx-settings-findings" role="alert">
          {findings.map((finding) => (
            <li key={finding.path}>{finding.path}: {finding.message}</li>
          ))}
        </ul>
      )}

      <div className="myx-settings-actions">
        <Figure value={changed.length} basis="measured" />
        <span className="myx-settings-note">{S.changed}</span>
        <Key busy={busy} disabled={changed.length === 0} onClick={onWrite}>{S.write}</Key>
      </div>

      <Reveal label={S.rawToml}>
        <TomlEditor text={toToml(draft)} />
      </Reveal>

      {changed.length === 0 || loaded === null ? null : (
        <Reveal label={S.showDiff}>
          <TomlMerge original={toToml(loaded)} modified={toToml(draft)} />
        </Reveal>
      )}

      {result === null ? null : (
        <p className="myx-settings-result" role="status">
          <Badge tone={result.ok ? 'ok' : 'danger'}>{result.ok ? S.written : S.refused}</Badge>
          {result.backup_path === undefined ? null : <span className="myx-settings-path">{result.backup_path}</span>}
        </p>
      )}
    </div>
  );
}

/** A command's standing instructions, distinct from compaction instructions below it. */
export function CommandInstructionsSection({ heads, labels, loaded, draft, onDraft, onWrite, busy, result }: {
  heads: readonly string[];
  labels?: Readonly<Record<string, string>>;
  loaded: Record<string, unknown> | null;
  draft: Record<string, unknown> | null;
  onDraft: (next: Record<string, unknown>) => void;
  onWrite: (head: string) => void;
  busy: boolean;
  result: TopologyWriteResult | null;
}) {
  const [picked, setPicked] = useState(heads[0] ?? '');
  const selected = heads.includes(picked) ? picked : heads[0] ?? '';
  const current = draft === null || selected === '' ? null : commandInstructionsOf(draft, selected);
  const asked = current?.source === 'file' && current.text.trim() !== ''
    ? { head: selected, file: current.text, mode: current.mode,
      key: JSON.stringify([selected, current.text, current.mode]) } : null;
  const [fileRead, setFileRead] = useState<{
    key: string; data: InstructionFilePreview | null; error: string | null;
  } | null>(null);
  useEffect(() => {
    if (asked === null) return undefined;
    let live = true;
    setFileRead(null);
    const timer = window.setTimeout(() => {
      void previewInstructionFile(asked.head, asked.file, asked.mode).then(
        (data) => { if (live) setFileRead({ key: asked.key, data, error: null }); },
        (error: unknown) => { if (live) setFileRead({ key: asked.key, data: null, error: error instanceof Error ? error.message : String(error) }); },
      );
    }, 150);
    return () => { live = false; window.clearTimeout(timer); };
  }, [selected, current?.source, current?.text, current?.mode]);
  if (draft === null) return <Empty text={S.topologyUnavailable} source={H.topologyUnavailable} />;
  if (selected === '') return <Empty text={S.noHeads} source={H.noHeads} />;
  const edit = (next: CommandInstruction | null) => onDraft(withCommandInstructions(draft, selected, next));
  const shown = asked === null || fileRead?.key !== asked.key ? null : fileRead;
  const changed = loaded === null ? [] : changedPaths(loaded, draft)
    .filter((path) => path.startsWith(`heads.${selected}.system_prompt`));
  // Save waits for the preview: a file not yet read, or one the preview refused, is not saved (V4-400).
  const fileHeld = asked !== null && (shown === null || shown.error !== null);
  const explanation = current === null ? H.previewUnchanged
    : current.mode === 'replace' ? H.previewReplace
      : current.mode === 'strip' ? H.previewStrip : H.previewAdd;

  return (
    <div className="myx-settings-command">
      <h3 className="myx-settings-command-title">{S.commandInstructions}</h3>
      <Choice label={S.command} value={selected}
        options={heads.map((head) => ({ value: head, label: labels?.[head] ?? head }))} onChange={setPicked} />
      {current === null ? (
        <Key onClick={() => edit({ source: 'inline', text: '', mode: 'append' })}>{S.addCommandInstructions}</Key>
      ) : (
        <>
          <div className="myx-settings-command-pickers">
            <Choice label={S.instructionMode} value={current.mode} options={[
              { value: 'append', label: S.addMode },
              { value: 'replace', label: S.replaceMode },
              { value: 'strip', label: S.stripMode },
            ]} onChange={(mode) => edit({ ...current, mode: mode as CommandInstruction['mode'] })} />
            <Choice label={S.instructionSource} value={current.source} options={[
              { value: 'inline', label: S.inlineSource },
              { value: 'file', label: S.fileSource },
            ]} onChange={(source) => edit({ ...current, source: source as CommandInstruction['source'], text: '' })} />
          </div>
          {current.source === 'file' ? (
            <Input label={S.instructionFile} value={current.text} onChange={(text) => edit({ ...current, text })} w={48} />
          ) : (
            <label className="myx-input myx-settings-instruction-lines">
              <span className="myx-input-label">{current.mode === 'strip' ? S.matchingParagraphs : S.instructionText}</span>
              <textarea className="myx-input-box" rows={4} value={current.text} spellCheck={false}
                onChange={(event) => edit({ ...current, text: event.target.value })} />
            </label>
          )}
          <Key onClick={() => edit(null)}>{S.removeCommandInstructions}</Key>
        </>
      )}
      <section className="myx-settings-preview" role="region" aria-label={S.preview}>
        <h4>{S.preview}</h4>
        <p>{explanation}</p>
        {current === null ? null : current.source === 'file' ? (
          <>
            <code>{current.text}</code>
            {asked === null ? null : shown === null ? <p>{H.previewReading}</p>
              : shown.error !== null ? <Fault message={shown.error} />
                : shown.data === null ? null : (
                  <><pre>{shown.data.text}</pre><p>{`${fmtInt(shown.data.chars)} ${S.chars}`}{shown.data.truncated ? ` · ${S.firstLines}` : ''}</p></>
                )}
          </>
        ) : <pre>{current.text}</pre>}
        {current?.mode === 'replace' ? <p>{H.previewReplaceEffect}</p> : null}
        {current === null ? null : <p>{H.previewRuntime}</p>}
      </section>
      <div className="myx-settings-actions">
        <Key busy={busy} disabled={changed.length === 0 || fileHeld || (current !== null && current.text.trim() === '')}
          onClick={() => onWrite(selected)}>{S.saveCommandInstructions}</Key>
        {result === null ? null : <Badge tone={result.ok ? 'ok' : 'danger'}>{result.ok ? S.written : S.refused}</Badge>}
      </div>
      {result?.findings?.map((finding) => (
        <p key={finding.path} role="alert">{finding.path}: {finding.message}</p>
      ))}
    </div>
  );
}

/**
 * The Claude head's mode. The two modes are two different products, and the section prints the
 * difference instead of hiding it behind a toggle: Separate leaves the `claude` command alone and, by
 * default, shares the operator's `~/.claude` setup and sessions; Wrap also rewrites two files in
 * `~/.claude` and shadows the `claude` command.
 */
export function ClaudeModeSection({ state, result, onWrap, onUnwrap, busy }: {
  state: ClaudeHeadPayload | null;
  result: ClaudeHeadActionResult | null;
  onWrap: () => void;
  onUnwrap: () => void;
  busy: boolean;
}) {
  if (state === null) return <Empty text={S.claudeUnread} source={H.noConfig} />;
  const card = state;
  const wrapping = card.mode === 'wrapped';
  const logins = card.claude_logins;

  return (
    <div className="myx-settings-claude">
      {/* The mode, and its side effects behind the mark beside it, before either action: wrapping
          is not a preference toggle, it edits two files the operator did not ask this console to
          own and shadows a command they run by name. */}
      <div className="myx-settings-row">
        <HolderEdge state={wrapping ? 'amber' : 'green'} label={wrapping ? S.wrapped : S.separate} />
        <InfoTip text={wrapping ? H.wrapped : H.separate} label={S.modeWhy} />
      </div>
      <div className="myx-settings-row">
        <span className="myx-settings-label">{S.onPath}</span>
        {/* The TARGET, not the link, because that is the string an unwrap restores. `null` is the
            daemon saying it could not resolve one — a different fact from an empty string, and the
            only reading under which this line was ever right before V4-175. */}
        <span className="myx-settings-path">{card.resolves_to ?? S.notFound}</span>
      </div>
      <div className="myx-settings-row">
        <span className="myx-settings-label">{S.shim}</span>
        <span className="myx-settings-path">{card.shim_path}</span>
      </div>
      {/* Wrapped only, and printed even when the daemon answers null: an unwrap restores FROM this
          string, so its absence on a wrapped machine is the operator's problem to see, not a row
          to hide. */}
      {wrapping ? (
        <div className="myx-settings-row">
          <span className="myx-settings-label">{S.realBinary}</span>
          <span className="myx-settings-path">{card.real_binary_path ?? S.unknown}</span>
        </div>
      ) : null}

      {/* A Claude head is client-auth: the daemon holds no account, so there are no account rows
          here and none are missing. These are the logins the operator stored, and the constraint
          is the daemon's own sentence. */}
      <div className="myx-settings-row">
        <span className="myx-settings-label">{S.logins}</span>
        <span className="myx-settings-path">{logins.count === 0 ? S.none : logins.labels.join(' ')}</span>
        {/* the daemon's own sentence on how logins switch, behind the mark */}
        <InfoTip text={logins.constraint} label={S.logins} />
      </div>
      <div className="myx-settings-row">
        <span className="myx-settings-label">{S.selected}</span>
        <span className="myx-settings-path">{logins.selected ?? S.none}</span>
      </div>

      <div className="myx-settings-actions">
        {wrapping ? (
          <Confirm label={S.unwrapLabel} confirmLabel={S.confirmUnwrap} busy={busy} onConfirm={onUnwrap} />
        ) : (
          <Confirm label={S.wrap} confirmLabel={S.confirmWrap} busy={busy} onConfirm={onWrap} />
        )}
      </div>

      {/* Wrap's own answer, which unwrap's does not carry: where the two files went before they
          were overwritten. Printed after the action rather than before it because these paths do
          not exist until one runs. */}
      {[result?.settings_backup_path, result?.claude_json_backup_path]
        .filter((path): path is string => path !== undefined)
        .map((path) => (
          <div className="myx-settings-row" key={path}>
            <span className="myx-settings-label">{S.backups}</span>
            <span className="myx-settings-path">{path}</span>
          </div>
        ))}
    </div>
  );
}
