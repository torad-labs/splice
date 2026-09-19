// The two sections that are not the knob rack: the topology document and the Claude head's mode.
//
// Both are exported standalone rather than inlined into the page so the tests can render them
// directly with a fixture payload — a page that reads the router cannot be static-rendered, and a
// section that has to be reached through one would be untestable for no reason.
import { Bay, Empty, FieldBox, Figure, HolderEdge, Reveal } from '@shared/ui';
import { Confirm, Key } from '@shared/controls';
import type { ClaudeHeadState } from '@entities/claude-head';
import { validateTopology } from '@entities/topology';
import type { TopologyState, TopologyWriteResult } from '@entities/topology';
import { TOPOLOGY_PROVENANCE } from '@features/head-edit';
import { TomlEditor, TomlMerge } from '@widgets/toml-editor';
import { changedPaths, coerce, EMPTIES, flattenTopology, setAtPath, toToml, valueAtPath } from './model';
import { S } from './strings';

function PendingRoute({ state, empty }: { state: unknown; empty: { text: string; source: string } }) {
  return typeof state === 'object' && state !== null && 'pending' in state ? (
    <Empty text={empty.text} source={empty.source} />
  ) : null;
}

/**
 * The document as forms, one field box per scalar, grouped by the table the key lives in.
 *
 * The validator's findings are shown BESIDE the draft rather than blocking the write: the daemon's
 * own writer re-validates and its refusal is the authority, so a console that refused first would
 * only be adding a second opinion to the same question.
 */
export function TopologySection({ state, loaded, draft, onDraft, onWrite, busy, result }: {
  state: TopologyState;
  loaded: Record<string, unknown> | null;
  draft: Record<string, unknown> | null;
  onDraft: (next: Record<string, unknown>) => void;
  onWrite: () => void;
  busy: boolean;
  result: TopologyWriteResult | null;
}) {
  if (typeof state === 'object' && state !== null && 'pending' in state) {
    return <PendingRoute state={state} empty={EMPTIES.topologyPending} />;
  }
  if (draft === null) return null;

  const leaves = flattenTopology(draft);
  const groups = new Map<string, typeof leaves>();
  for (const leaf of leaves) {
    const head = leaf.path.split('.')[0];
    groups.set(head, [...(groups.get(head) ?? []), leaf]);
  }

  const changed = loaded === null ? [] : changedPaths(loaded, draft);
  const findings = validateTopology(draft);

  return (
    <div className="myx-settings-topology">
      {/* FEATURES 4.7: "Backs the file up first." This is the sentence, not a label, so it lives
          here rather than in the string table (CONTRACTS.md section 4). */}
      <p className="myx-settings-note">
        the daemon backs the file up first, writes it through the structured writer add-model uses,
        and this console reads it back rather than keeping a copy of its own.
      </p>
      <p className="myx-settings-path">{state.path}</p>
      {state.stale ? <HolderEdge state="amber" label={S.restart} /> : null}

      {[...groups.entries()].map(([head, group]) => (
        <Bay key={head} label={head} count={group.length}>
          {group.map((leaf) => (
            <FieldBox
              key={leaf.path}
              label={leaf.path}
              value={String(leaf.value)}
              provenance={TOPOLOGY_PROVENANCE}
              hot={false}
              onChange={(raw) => onDraft(setDraftLeaf(draft, leaf.path, raw))}
            />
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
        <p className="myx-settings-note">
          {result.ok ? 'written' : 'refused'}
          {result.backup_path === undefined ? '' : ` ${result.backup_path}`}
        </p>
      )}
    </div>
  );
}

function setDraftLeaf(draft: Record<string, unknown>, path: string, raw: string): Record<string, unknown> {
  const before = valueAtPath(draft, path);
  const reference = typeof before === 'string' || typeof before === 'number' || typeof before === 'boolean' ? before : '';
  return setAtPath(draft, path, coerce(raw, reference));
}

/**
 * The Claude head's mode. The two modes are two different products, and the section prints the
 * difference instead of hiding it behind a toggle: Separate touches nothing of the operator's own
 * setup, and Wrap rewrites two files in `~/.claude` and shadows the `claude` command.
 */
export function ClaudeModeSection({ state, onWrap, onUnwrap, busy }: {
  state: ClaudeHeadState | null;
  onWrap: () => void;
  onUnwrap: () => void;
  busy: boolean;
}) {
  if (state === null || 'pending' in state) {
    return <PendingRoute state={state} empty={EMPTIES.claudePending} />;
  }
  const card = state;
  const wrapping = card.mode === 'wrap';

  return (
    <div className="myx-settings-claude">
      <div className="myx-settings-row">
        <HolderEdge state={wrapping ? 'amber' : 'green'} label={wrapping ? S.wrap : S.separate} />
        <span className="myx-settings-path">{card.head ?? ''}</span>
        <span className="myx-settings-path">{card.config_dir ?? ''}</span>
      </div>
      {/* The side effects, in words, before either action: wrapping is not a preference toggle,
          it edits two files the operator did not ask this console to own and shadows a command
          they run by name. */}
      <p className="myx-settings-note">
        {wrapping
          ? 'wrapping rewrites the two files listed here in the vanilla config dir, shadowing the claude command with a shim that execs the real binary by absolute path; unwrapping restores both from the backups.'
          : 'separate leaves the vanilla setup untouched: nothing outside this head own config dir is written, and the claude command on PATH stays yours.'}
      </p>
      <div className="myx-settings-row">
        <span className="myx-settings-note">{S.onPath}</span>
        <span className="myx-settings-path">{card.claude_on_path ?? 'nothing named claude'}</span>
      </div>
      {card.shim_path === undefined ? null : (
        <div className="myx-settings-row">
          <span className="myx-settings-note">{S.shim}</span>
          <span className="myx-settings-path">{card.shim_path}</span>
        </div>
      )}
      {(card.rewritten_files ?? []).map((file) => (
        <div className="myx-settings-row" key={file}>
          <span className="myx-settings-note">{S.rewritten}</span>
          <span className="myx-settings-path">{file}</span>
        </div>
      ))}
      {(card.backup_paths ?? []).map((file) => (
        <div className="myx-settings-row" key={file}>
          <span className="myx-settings-note">{S.backups}</span>
          <span className="myx-settings-path">{file}</span>
        </div>
      ))}
      {card.note === undefined ? null : <p className="myx-settings-note">{card.note}</p>}

      <div className="myx-settings-actions">
        {wrapping ? (
          <Confirm label={S.unwrapLabel} confirmLabel={S.confirmUnwrap} busy={busy} onConfirm={onUnwrap} />
        ) : (
          <Confirm label={S.wrap} confirmLabel={S.confirmWrap} busy={busy || card.wrap_supported === false} onConfirm={onWrap} />
        )}
      </div>
      {card.wrap_supported === false ? (
        <p className="myx-settings-note">
          wrap is not offered on this machine: the materializer refuses the vanilla config dir by
          design, and this page will not route around that guard.
        </p>
      ) : null}
    </div>
  );
}
