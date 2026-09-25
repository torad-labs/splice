// The knob rack: every runtime knob as a row that says what it is, what it is set to, where that
// value came from, and whether saving a new one does anything now (FEATURES 4.7, CONTRACTS.md
// section 4).
//
// The operator opens Settings to answer "what is this knob and where does its value come from"
// (FEATURES section 1). The row used to answer only the second half: a camelCase key over a raw
// number. It now leads with the knob's name in words and one line on what it does (copy.ts),
// prints the unit after the value and a readable form beside a millisecond or byte count, and
// draws the rest (docs/design/DESIGN.md section 9): where the value sits against its default, the
// layer it came from lit in the stack of layers, and a glyph for live or on restart. A value off
// its default carries a badge and a way back. The key stays on the row, small, because
// splice.toml, the environment and the CLI all spell it that way.
//
// The row is NOT a Strip. A Strip is a focusable role="button", and an input inside one is a
// nested interactive element; the world reaches this page through the bay, the holder edge and the
// type instead. The holder edge appears only on a row that is genuinely in a state worth flagging:
// saved, and not yet in force.
import { useState } from 'react';
import { ArrowClockwiseIcon } from '@phosphor-icons/react/dist/csr/ArrowClockwise';
import { ArrowCounterClockwiseIcon } from '@phosphor-icons/react/dist/csr/ArrowCounterClockwise';
import { LightningIcon } from '@phosphor-icons/react/dist/csr/Lightning';
import type { ConfigValue } from '@shared/api';
import { PROVENANCE_LAYERS, parseConfigInput } from '@entities/config';
import type { KnobDisposition, Provenance } from '@entities/config';
import { Choice, Flag, Key } from '@shared/controls';
import { Badge, LayerChip, Tip } from '@shared/ui';
import { KNOB_HELP } from './copy';
import { KNOB_META, unitText } from './knobs';
import type { KnobGroup, KnobMeta, KnobUnit } from './knobs';
import { GROUP_LABELS, KNOB_LABELS, S, SOURCE_LABELS, U } from './strings';
import './knob-form.css';

export { KNOB_HELP } from './copy';
export { KNOB_META, readableBytes, readableMs, unitText } from './knobs';
export type { KnobGroup, KnobMeta, KnobUnit } from './knobs';

const GROUP_ORDER = Object.keys(GROUP_LABELS) as KnobGroup[];

/** The value's layers, weakest first, named as the chip's tip prints them. */
const LAYER_NAMES: readonly string[] = PROVENANCE_LAYERS.map((layer) => SOURCE_LABELS[layer]);

/** A picker's options: the daemon's values, plus the current one when it is outside them (a value
 *  written into splice.toml by hand), so the row never shows a choice it does not hold. An empty
 *  value is `Model default` only where the knob declares it (effort, which each model fills in);
 *  on any other knob it is a value nobody set, and the daemon's own default applies. */
export function choiceOptions(choices: readonly string[], current: string): { value: string; label: string }[] {
  const values = choices.includes(current) ? choices : [...choices, current];
  const blank = choices.includes('') ? S.modelDefault : S.unset;
  return values.map((value) => ({ value, label: value === '' ? blank : value }));
}
/** Inside a group, knobs keep the order knobs.ts lists them in: the one an operator reaches for
 *  first leads, and a knob with no entry goes last. */
const KNOB_ORDER = new Map(Object.keys(KNOB_META).map((key, at) => [key, at]));
const orderOf = (key: string): number => KNOB_ORDER.get(key) ?? KNOB_ORDER.size;

export interface Wording {
  changed: string;
  reset: string;
  /** What the scale measures the value against. */
  reference: string;
}

const DEFAULT_WORDING: Wording = { changed: S.changed, reset: S.reset, reference: U.default };

/** A head's view measures each knob against the global value, and going back drops the override. */
export const HEAD_WORDING: Wording = { changed: S.ownValue, reset: S.useGlobal, reference: U.global };

type KnobWords = KnobMeta & { label: string; help: string };

/** The words and data for a key, or a bare fallback for a knob this console has none for yet. The
 *  completeness test keeps the fallback unreachable for every knob Knob.kt declares. */
function copyOf(key: string): KnobWords {
  const label = (KNOB_LABELS as Record<string, string>)[key] ?? key;
  return { label, help: KNOB_HELP[key] ?? '', ...(KNOB_META[key] ?? { group: 'daemon' }) };
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
function ValueNote({ unit, value }: { unit: KnobUnit | undefined; value: ConfigValue }) {
  const { suffix, readable } = unitText(unit, typeof value === 'number' ? value : null);
  if (suffix === null && readable === null) return null;
  return (
    <span className="myx-knob-unit">
      {suffix}
      {readable === null ? null : <span className="myx-knob-readable">{readable}</span>}
    </span>
  );
}

const clamp = (share: number): number => Math.max(0, Math.min(1, share));
const DOUBLINGS = 3;

/** Where a number sits against its reference (the daemon's default, or the global value in a head's
 *  view): a tick at the reference, a dot at the value, and the run between them. Most knobs have a
 *  floor and no ceiling (ConfigCoercion), so the scale is logarithmic, three doublings either side
 *  of the reference; a percent has both ends and reads 0 to 100. A port is an address, not an
 *  amount, and a zero has no place on a log scale (it means "no limit" on the knobs that take it),
 *  so neither draws one. The row draws it only off its reference (Scale). */
export function scaleOf(unit: KnobUnit | undefined, value: ConfigValue, reference: ConfigValue): { at: number; mark: number } | null {
  if (typeof value !== 'number' || typeof reference !== 'number' || unit === undefined || unit === 'port') return null;
  if (unit === 'percent') return { at: clamp(value / 100), mark: clamp(reference / 100) };
  if (value <= 0 || reference <= 0) return null;
  return { at: clamp(0.5 + Math.log2(value / reference) / (DOUBLINGS * 2)), mark: 0.5 };
}

/** The scale, drawn only off the reference and with the reference's mark labelled. At the reference
 *  there is nothing to place: a rack at defaults drew dozens of identical centred sliders that do
 *  not drag, and said "at default" only to a screen reader (Hitstop, 2026-09-25). */
function Scale({ unit, value, reference, label, word }: { unit: KnobUnit | undefined; value: ConfigValue; reference: ConfigValue; label: string; word: string }) {
  const scale = value === reference ? null : scaleOf(unit, value, reference);
  if (scale === null) return null;
  const pct = (share: number): string => `${share * 100}%`;
  const from = Math.min(scale.at, scale.mark);
  return (
    <span className="myx-knob-scale" role="img" aria-label={`${label}: ${String(value)}, ${word} ${String(reference)}`}>
      <span className="myx-knob-scale-run" style={{ left: pct(from), width: pct(Math.abs(scale.at - scale.mark)) }} />
      <span className="myx-knob-scale-mark" style={{ left: pct(scale.mark) }} />
      <span className="myx-knob-scale-dot" style={{ left: pct(scale.at) }} />
      <span className="myx-knob-scale-ref" style={{ left: pct(scale.mark) }} aria-hidden="true">{`${word} ${String(reference)}`}</span>
    </span>
  );
}

/** Where the value came from, as the layer stack with the winner lit, and whether a save applies
 *  now or on restart, as a glyph whose words ride in its tip. */
function Source({ provenance, hot }: { provenance: Provenance; hot: boolean }) {
  const said = hot ? S.live : S.restart;
  return (
    <span className="myx-knob-source">
      <LayerChip names={LAYER_NAMES} active={PROVENANCE_LAYERS.indexOf(provenance)} label={S.source} />
      <Tip text={said}>
        {hot
          ? <LightningIcon className="myx-knob-apply myx-knob-live" role="img" aria-label={said} />
          : <ArrowClockwiseIcon className="myx-knob-apply" role="img" aria-label={said} />}
      </Tip>
    </span>
  );
}

/** A read-only value as the rest of the rack prints it: a switch's value as On or Off, not the
 *  `false` the payload carries. */
function readoutText(value: ConfigValue): string {
  if (value === null) return S.unset;
  if (typeof value === 'boolean') return value ? S.on : S.off;
  return String(value);
}

/** The name, the key and the one line of help, the same on every row. */
function Name({ knobKey, copy }: { knobKey: string; copy: KnobWords }) {
  return (
    <>
      <span className="myx-knob-name">
        <span className="myx-knob-label">{copy.label}</span>
        <code className="myx-knob-key">{knobKey}</code>
      </span>
      {copy.help === '' ? null : <p className="myx-knob-help">{copy.help}</p>}
    </>
  );
}

/** A knob printed, not edited: the fleet's per-head detail, the MCP host limits, and a knob the
 *  daemon forces or scopes to one head. */
export function KnobReadout({ knob }: { knob: KnobDisposition }) {
  const copy = copyOf(knob.key);
  return (
    <div className="myx-knob myx-knob-readout" data-knob={knob.key}>
      <Name knobKey={knob.key} copy={copy} />
      <span className="myx-knob-value">
        <span className="myx-knob-figure">{readoutText(knob.value)}</span>
        <ValueNote unit={copy.unit} value={knob.value} />
        {copy.headOnly === true ? <Badge tone="neutral">{S.perHead}</Badge> : null}
        {copy.locked === true ? <Badge tone="neutral">{S.locked}</Badge> : null}
      </span>
      <span className="myx-knob-foot">
        <Source provenance={knob.provenance} hot={knob.hot} />
      </span>
    </div>
  );
}

export function KnobForm({ disposition, pending, busy, onSave, scopeNote, wording, perHead = false }: {
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
  /** Whether saving writes one head's [heads.KEY.overrides], the only layer a head-only knob takes. */
  perHead?: boolean;
}) {
  const words = wording ?? DEFAULT_WORDING;
  const [draft, setDraft] = useState<string | null>(null);
  const copy = copyOf(disposition.key);
  const shownValue = draft ?? shown(disposition.value);
  const parsed = parseConfigInput(shownValue, disposition.value);
  const dirty = draft !== null && !sameValue(parsed, disposition.value);
  const changed = !sameValue(disposition.value, disposition.defaultValue);
  const isFlag = typeof disposition.value === 'boolean' || typeof disposition.defaultValue === 'boolean';
  if (copy.locked === true || (copy.headOnly === true && !perHead)) return <KnobReadout knob={disposition} />;

  const save = (value: ConfigValue) => {
    onSave(disposition.key, value);
    setDraft(null);
  };

  return (
    <div className="myx-knob" data-knob={disposition.key} data-changed={changed || undefined}>
      <Name knobKey={disposition.key} copy={copy} />

      <span className="myx-knob-value">
        <span className="myx-knob-control">
          {isFlag ? (
            <Flag
              on={parsed === true}
              onLabel={S.on}
              offLabel={S.off}
              ariaLabel={copy.label}
              disabled={busy === true}
              onChange={(next) => setDraft(String(next))}
            />
          ) : copy.choices !== undefined ? (
            <Choice
              id={`knob-${disposition.key}`}
              className="myx-knob-choice"
              label={copy.label}
              value={shownValue}
              options={choiceOptions(copy.choices, shownValue)}
              onChange={setDraft}
              w={16}
              disabled={busy === true}
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
          <ValueNote unit={copy.unit} value={parsed} />
        </span>
        <Scale unit={copy.unit} value={parsed} reference={disposition.defaultValue} label={copy.label} word={words.reference} />
      </span>

      <span className="myx-knob-foot">
        <Source provenance={disposition.provenance} hot={disposition.hot} />
        {changed ? (
          <span className="myx-knob-changed">
            <Badge tone="accent">{words.changed}</Badge>
            <Tip text={words.reset}>
              <button type="button" className="myx-knob-reset" aria-label={words.reset} disabled={busy === true} onClick={() => save(disposition.defaultValue)}>
                <ArrowCounterClockwiseIcon aria-hidden="true" />
              </button>
            </Tip>
          </span>
        ) : null}
        {pending ? <Badge tone="warn">{S.pending}</Badge> : null}
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
  return [copy.label, key, copy.help].some((text) => text.toLowerCase().includes(q));
}

/**
 * The whole rack, one section per group in a fixed order. `dispositions` arrives already filtered
 * by the page's active view and finder; the widget groups and never hides anything on its own, so
 * what the operator sees is what the page asked for.
 */
export function KnobRack({ dispositions, pending, busyKey, onSave, scopeNote, wording, perHead = false }: {
  dispositions: readonly KnobDisposition[];
  /** Keys saved and not yet in force, for the row's holder edge. */
  pending: readonly string[];
  busyKey: string | null;
  onSave: (key: string, value: ConfigValue) => void;
  /** Per knob: what saving it reaches, when that needs saying. */
  scopeNote?: (knob: KnobDisposition) => string | null;
  wording?: Wording;
  /** Saving writes one head's overrides rather than the global PATCH (see KnobForm). */
  perHead?: boolean;
}) {
  const groups = GROUP_ORDER.map((group) => ({
    group,
    knobs: dispositions
      .filter((knob) => copyOf(knob.key).group === group)
      .sort((left, right) => orderOf(left.key) - orderOf(right.key)),
  })).filter(({ knobs }) => knobs.length > 0);

  // The groups as a list to jump by, beside the knobs (DESIGN.md section 7). They scroll the page
  // rather than link, because the address bar holds the console's route and not an anchor.
  const jump = (group: KnobGroup) => document.getElementById(`knob-group-${group}`)?.scrollIntoView({ block: 'start' });

  return (
    <div className="myx-knob-rack">
      <nav className="myx-knob-index" aria-label={S.groups}>
        {groups.map(({ group, knobs }) => (
          <button key={group} type="button" className="myx-knob-index-item" onClick={() => jump(group)}>
            <span>{GROUP_LABELS[group]}</span>
            <span className="myx-knob-index-count">{knobs.length}</span>
          </button>
        ))}
      </nav>
      <div className="myx-knob-groups">
      {groups.map(({ group, knobs }) => (
        <section key={group} id={`knob-group-${group}`} className="myx-knob-group" aria-label={GROUP_LABELS[group]}>
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
                perHead={perHead}
              />
            ))}
          </div>
        </section>
      ))}
      </div>
    </div>
  );
}
