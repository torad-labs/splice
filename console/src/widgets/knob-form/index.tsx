// The knob rack: every runtime knob as a row that says what it is, what it is set to, where that
// value came from, and whether saving a new one does anything now (FEATURES 4.7, CONTRACTS.md
// section 4).
//
// The operator opens Settings to answer "what is this knob and where does its value come from"
// (FEATURES section 1). The row used to answer only the second half: a camelCase key over a raw
// number. It now leads with the knob's name in words and one sentence on what it does (copy.ts),
// prints the unit after the value and a readable form beside a millisecond or byte count, and
// marks a value that differs from the daemon's default, with a way back to it. The key stays on
// the row, small, because splice.toml, the environment and the CLI all spell it that way.
//
// The row is NOT a Strip. A Strip is a focusable role="button", and an input inside one is a
// nested interactive element; the world reaches this page through the bay, the holder edge and the
// type instead. The holder edge appears only on a row that is genuinely in a state worth flagging:
// saved, and not yet in force.
import { useState } from 'react';
import type { ConfigValue } from '@shared/api';
import { parseConfigInput } from '@entities/config';
import type { KnobDisposition, Provenance } from '@entities/config';
import { Flag, Key } from '@shared/controls';
import { HolderEdge } from '@shared/ui';
import { KNOB_COPY, unitText } from './copy';
import type { KnobCopy, KnobGroup } from './copy';
import { GROUP_LABELS, KNOB_LABELS, S, SOURCE_LABELS } from './strings';
import './knob-form.css';

export { KNOB_COPY, readableBytes, readableMs, unitText } from './copy';
export type { KnobCopy, KnobGroup, KnobUnit } from './copy';

const GROUP_ORDER = Object.keys(GROUP_LABELS) as KnobGroup[];
/** Inside a group, knobs keep the order copy.ts lists them in: the one an operator reaches for
 *  first leads, and a knob with no copy goes last. */
const KNOB_ORDER = new Map(Object.keys(KNOB_COPY).map((key, at) => [key, at]));
const orderOf = (key: string): number => KNOB_ORDER.get(key) ?? KNOB_ORDER.size;

export interface Wording {
  changed: string;
  reset: string;
}

const DEFAULT_WORDING: Wording = { changed: S.changed, reset: S.reset };

/** A head's view measures each knob against the global value, and going back drops the override. */
export const HEAD_WORDING: Wording = { changed: S.ownValue, reset: S.useGlobal };

/** The copy for a key, or a bare fallback for a knob this console has no words for yet. The
 *  completeness test keeps the fallback unreachable for every knob Knob.kt declares. */
function copyOf(key: string): KnobCopy & { label: string } {
  const label = (KNOB_LABELS as Record<string, string>)[key] ?? key;
  return { label, ...(KNOB_COPY[key] ?? { group: 'daemon', summary: '' }) };
}

/** The knob's name as the operator reads it. */
export function knobLabel(key: string): string {
  return copyOf(key).label;
}

/** The effective value as the editable string the field holds. `null` is "unset", not "0". */
function shown(value: ConfigValue): string {
  return value === null ? '' : String(value);
}

function sameValue(left: ConfigValue, right: ConfigValue): boolean {
  return left === right || (left === null && right === '') || (left === '' && right === null);
}

/** What the value reads as beside the field: the unit, and a readable form of a raw count. */
function ValueNote({ knobKey, value }: { knobKey: string; value: ConfigValue }) {
  const { suffix, readable } = unitText(copyOf(knobKey).unit, typeof value === 'number' ? value : null);
  if (suffix === null && readable === null) return null;
  return (
    <span className="myx-knob-unit">
      {suffix}
      {readable === null ? null : <span className="myx-knob-readable">{readable}</span>}
    </span>
  );
}

function Source({ provenance, hot }: { provenance: Provenance; hot: boolean }) {
  return (
    <span className="myx-knob-source">
      <span className="myx-knob-prov">{SOURCE_LABELS[provenance]}</span>
      <span className="myx-knob-hot">{hot ? S.live : S.restart}</span>
    </span>
  );
}

/** A knob printed, not edited: the fleet's per-head detail and the MCP host limits. */
export function KnobReadout({ knob }: { knob: KnobDisposition }) {
  const copy = copyOf(knob.key);
  return (
    <div className="myx-knob myx-knob-readout" data-knob={knob.key}>
      <span className="myx-knob-name">
        <span className="myx-knob-label">{copy.label}</span>
        <code className="myx-knob-key">{knob.key}</code>
      </span>
      {copy.locked === true ? <p className="myx-knob-summary">{copy.summary}</p> : null}
      <span className="myx-knob-value">
        <span className="myx-knob-figure">{knob.value === null ? S.unset : String(knob.value)}</span>
        <ValueNote knobKey={knob.key} value={knob.value} />
      </span>
      <Source provenance={knob.provenance} hot={knob.hot} />
    </div>
  );
}

export function KnobForm({ disposition, pending, busy, onSave, scopeNote, wording }: {
  disposition: KnobDisposition;
  /** Saved in this console session and not read by the running daemon yet. */
  pending: boolean;
  busy?: boolean;
  onSave: (key: string, value: ConfigValue) => void;
  /** One sentence on what saving here reaches, when that is not simply "this knob". */
  scopeNote?: string | null;
  /** The words for "differs from the reference" and "go back to it", when the reference is not
   *  the daemon's default (a head's view measures against the global value). */
  wording?: Wording;
}) {
  const words = wording ?? DEFAULT_WORDING;
  const [draft, setDraft] = useState<string | null>(null);
  const copy = copyOf(disposition.key);
  const shownValue = draft ?? shown(disposition.value);
  const parsed = parseConfigInput(shownValue, disposition.value);
  const dirty = draft !== null && !sameValue(parsed, disposition.value);
  const changed = !sameValue(disposition.value, disposition.defaultValue);
  const isFlag = typeof disposition.value === 'boolean' || typeof disposition.defaultValue === 'boolean';
  if (copy.locked === true) return <KnobReadout knob={disposition} />;

  const save = (value: ConfigValue) => {
    onSave(disposition.key, value);
    setDraft(null);
  };

  return (
    <div className="myx-knob" data-knob={disposition.key} data-changed={changed || undefined}>
      <span className="myx-knob-name">
        <span className="myx-knob-label">{copy.label}</span>
        <code className="myx-knob-key">{disposition.key}</code>
      </span>
      {copy.summary === '' ? null : <p className="myx-knob-summary">{copy.summary}</p>}

      <span className="myx-knob-value">
        {isFlag ? (
          <Flag
            on={parsed === true}
            onLabel={S.on}
            offLabel={S.off}
            ariaLabel={copy.label}
            disabled={busy === true}
            onChange={(next) => setDraft(String(next))}
          />
        ) : (
          <input
            className="myx-knob-input"
            aria-label={copy.label}
            value={shownValue}
            placeholder={S.unset}
            inputMode={typeof disposition.defaultValue === 'number' ? 'numeric' : undefined}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => setDraft(event.target.value)}
          />
        )}
        <ValueNote knobKey={disposition.key} value={parsed} />
      </span>

      <span className="myx-knob-foot">
        <Source provenance={disposition.provenance} hot={disposition.hot} />
        {changed ? (
          <span className="myx-knob-changed">
            {words.changed}
            <button type="button" className="myx-knob-reset" disabled={busy === true} onClick={() => save(disposition.defaultValue)}>
              {words.reset}
            </button>
          </span>
        ) : null}
        {pending ? <HolderEdge state="amber" label={S.pending} /> : null}
        {dirty ? <Key busy={busy ?? false} onClick={() => save(parsed)}>{S.save}</Key> : null}
      </span>
      {scopeNote === undefined || scopeNote === null ? null : <p className="myx-knob-scope">{scopeNote}</p>}
    </div>
  );
}

/** Whether a knob matches what the operator typed into the finder: its name, key or sentence. */
export function knobMatches(key: string, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === '') return true;
  const copy = copyOf(key);
  return [copy.label, key, copy.summary].some((text) => text.toLowerCase().includes(q));
}

/**
 * The whole rack, one section per group in a fixed order. `dispositions` arrives already filtered
 * by the page's active view and finder; the widget groups and never hides anything on its own, so
 * what the operator sees is what the page asked for.
 */
export function KnobRack({ dispositions, pending, busyKey, onSave, scopeNote, wording }: {
  dispositions: readonly KnobDisposition[];
  /** Keys saved and not yet in force, for the row's holder edge. */
  pending: readonly string[];
  busyKey: string | null;
  onSave: (key: string, value: ConfigValue) => void;
  /** Per knob: what saving it reaches, when that needs saying. */
  scopeNote?: (knob: KnobDisposition) => string | null;
  wording?: Wording;
}) {
  const groups = GROUP_ORDER.map((group) => ({
    group,
    knobs: dispositions
      .filter((knob) => copyOf(knob.key).group === group)
      .sort((left, right) => orderOf(left.key) - orderOf(right.key)),
  })).filter(({ knobs }) => knobs.length > 0);

  return (
    <div className="myx-knob-groups">
      {groups.map(({ group, knobs }) => (
        <section key={group} className="myx-knob-group" aria-label={GROUP_LABELS[group]}>
          <h3 className="myx-knob-group-title">{GROUP_LABELS[group]}</h3>
          <div className="myx-knobs">
            {knobs.map((disposition) => (
              <KnobForm
                key={disposition.key}
                disposition={disposition}
                pending={pending.includes(disposition.key)}
                busy={busyKey === disposition.key}
                onSave={onSave}
                scopeNote={scopeNote?.(disposition) ?? null}
                {...(wording === undefined ? {} : { wording })}
              />
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
