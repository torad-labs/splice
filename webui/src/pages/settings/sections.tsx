// The two sections that are not the knob rack: the topology document and the Claude head's mode.
//
// Both are exported standalone rather than inlined into the page so the tests can render them
// directly with a fixture payload — a page that reads the router cannot be static-rendered, and a
// section that has to be reached through one would be untestable for no reason.
import { Bay, Empty, FieldBox, Figure, HolderEdge, Reveal } from '@shared/ui';
import { Confirm, Key } from '@shared/controls';
import type { ClaudeHeadActionResult, ClaudeHeadPayload } from '@entities/claude-head';
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
export function ClaudeModeSection({ state, result, onWrap, onUnwrap, busy }: {
  state: ClaudeHeadPayload | null;
  result: ClaudeHeadActionResult | null;
  onWrap: () => void;
  onUnwrap: () => void;
  busy: boolean;
}) {
  if (state === null) return <Empty text={EMPTIES.claudeUnread.text} source={EMPTIES.claudeUnread.source} />;
  const card = state;
  const wrapping = card.mode === 'wrapped';
  const logins = card.claude_logins;

  return (
    <div className="myx-settings-claude">
      <div className="myx-settings-row">
        <HolderEdge state={wrapping ? 'amber' : 'green'} label={wrapping ? S.wrapped : S.separate} />
      </div>
      {/* The side effects, in words, before either action: wrapping is not a preference toggle,
          it edits two files the operator did not ask this console to own and shadows a command
          they run by name. */}
      <p className="myx-settings-note">
        {wrapping
          ? 'wrapping rewrote settings.json and .claude.json in the vanilla config dir and shadowed the claude command with a shim that execs the real binary by absolute path; unwrapping restores both from the backups.'
          : 'separate leaves the vanilla setup untouched: nothing outside this head own config dir is written, and the claude command on PATH stays yours.'}
      </p>
      <div className="myx-settings-row">
        <span className="myx-settings-note">{S.onPath}</span>
        {/* The TARGET, not the link, because that is the string an unwrap restores. `null` is the
            daemon saying it could not resolve one — a different fact from an empty string, and the
            only reading under which this line was ever right before V4-175. */}
        <span className="myx-settings-path">{card.resolves_to ?? 'nothing named claude'}</span>
      </div>
      <div className="myx-settings-row">
        <span className="myx-settings-note">{S.shim}</span>
        <span className="myx-settings-path">{card.shim_path}</span>
      </div>
      {/* Wrapped only, and printed even when the daemon answers null: an unwrap restores FROM this
          string, so its absence on a wrapped machine is the operator's problem to see, not a row
          to hide. */}
      {wrapping ? (
        <div className="myx-settings-row">
          <span className="myx-settings-note">{S.realBinary}</span>
          <span className="myx-settings-path">{card.real_binary_path ?? 'unknown'}</span>
        </div>
      ) : null}

      {/* A Claude head is client-auth: the daemon holds no account, so there are no account rows
          here and none are missing. These are the logins the operator stored, and the constraint
          is the daemon's own sentence. */}
      <div className="myx-settings-row">
        <span className="myx-settings-note">{S.logins}</span>
        <span className="myx-settings-path">{logins.count === 0 ? 'none' : logins.labels.join(' ')}</span>
      </div>
      <div className="myx-settings-row">
        <span className="myx-settings-note">{S.selected}</span>
        <span className="myx-settings-path">{logins.selected ?? 'none'}</span>
      </div>
      <p className="myx-settings-note">{logins.constraint}</p>

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
            <span className="myx-settings-note">{S.backups}</span>
            <span className="myx-settings-path">{path}</span>
          </div>
        ))}
    </div>
  );
}
