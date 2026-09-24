// The model strips, as components that take their data as props.
//
// A strip per model, in slot order first and then the unslotted rows, because the two answer
// different questions: a slotted model is what Claude Code will actually be handed for that tier,
// and an unslotted one is a model the head can serve but no tier selects. FEATURES.md 4.8 asks for
// "which tiers Claude Code will and will not get on this head" — so an undeclared tier is a STRUCK
// strip that prints `not declared`, never a missing row.
import type { CatalogModel, HeadCatalog } from '@entities/model';
import { slotTiers } from '@entities/model';
import { fmtTokens } from '@shared/lib';
import { Bay, Empty, Strip, StripField } from '@shared/ui';
import { EMPTIES } from './model';
import type { HeadWindows } from './model';
import { S } from './strings';

/** A rate the model does not declare prints the absence glyph, not the phrase `no rates` — the
 *  strip's holder edge says `no rates` once, which is where a state belongs (m1 design review
 *  B8 and B10). */
function rateValue(model: CatalogModel, pick: (rates: NonNullable<CatalogModel['rates']>) => number): string {
  // undefined AND null: the daemon omits the key rather than sending null (M1-41, types.ts).
  return model.rates === undefined || model.rates === null ? S.absent : String(pick(model.rates));
}

/** The catalog rack's columns: the bay head prints these once and the strips below carry values
 *  only (CONTRACTS.md section 2, m1 design review B9).
 *
 *  THAT SENTENCE WAS TRUE OF THE INTENT AND FALSE OF THE CODE UNTIL M2-33. This table existed and
 *  was exported for exactly the purpose it names, and then every strip was given `label={column
 *  .label}` and the bay was given no names row at all -- so each of the thirteen strips printed
 *  the six column names again. Measured at 1536 dark: a strip stood 63.8px and 42px of it was the
 *  values, so a third of every row went on repeating what the rack says once. The wiring is the
 *  fix; the design was already written down here. */
export const MODEL_COLUMNS: readonly { key: string; label: string; w: number; prose?: boolean }[] = [
  { key: 'model', label: S.model, w: 24, prose: true },
  { key: 'slot', label: S.slot, w: 8, prose: true },
  { key: 'contextWindow', label: S.contextWindow, w: 13 },
  { key: 'windowSource', label: S.windowSource, w: 24, prose: true },
  { key: 'rateInput', label: S.rateInput, w: 12 },
  { key: 'rateOutput', label: S.rateOutput, w: 12 },
];

function ModelStrip({ model, slot, selected, onOpen }: {
  model: CatalogModel;
  slot: string;
  selected: boolean;
  onOpen: () => void;
}) {
  /** A model no tier selects. It is the strip's own state, so the holder edge prints it and the
   *  `slot` field carries the same word as data (m1 design review B10: the edge label used to BE
   *  the slot datum, so every strip printed its tier twice). */
  const unslotted = slot === S.noSlot;
  return (
    <Strip
      edge={unslotted ? 'grey' : 'green'}
      /* The edge carries the one state no column does, pinned; a tier's model and a model with no
         tier said `auto` and `none` there, beside a tier column that already said which. */
      edgeLabel={model.pinned ? S.pinnedYes : ''}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${S.openModel} ${model.id}`}
    >
      {/* Six fields and no more: a strip whose fields outrun its bay squeezes the holder edge, and
          a wrapped edge label is the one defect that makes a rack stop reading as a rack. The cache
          read and write rates and the pinned flag live in the opened strip's detail. */}
      {MODEL_COLUMNS.map((column) => (
        <StripField
          key={column.key}
          w={column.w}
          value={modelCell(model, slot, column.key)}
          {...(column.prose ? { mono: false } : {})}
        />
      ))}
    </Strip>
  );
}

/** One cell of the catalog rack, by column. The rack's names are on the bay head, so a cell is a
 *  value and nothing else. */
function modelCell(model: CatalogModel, slot: string, key: string): string {
  if (key === 'model') return model.id;
  if (key === 'slot') return slot === S.noSlot ? S.absent : slot;
  if (key === 'contextWindow') return model.context_window === null ? S.absent : fmtTokens(model.context_window);
  if (key === 'windowSource') return model.context_window_source;
  if (key === 'rateInput') return rateValue(model, (rates) => rates.input);
  return rateValue(model, (rates) => rates.output);
}

/** The rack's column names, once, at the ch of the columns they name (B9). The growth is the half
 *  that is easy to miss: `strip-field.tsx` sets flexGrow to each cell's OWN ch (M1-73), so a name
 *  row fixed at `w ch` drifts off the column under it -- further the more slack the rack has. The
 *  name takes the same basis and the same growth, so the two cannot disagree. */
function ColumnNames() {
  return (
    <>
      {MODEL_COLUMNS.map((column) => (
        <span key={column.key} className="myx-models-col" style={{ width: `${column.w}ch`, flexGrow: column.w }}>
          {column.label}
        </span>
      ))}
    </>
  );
}

/** The tiers no model fills, said once as a sentence: which Claude Code tiers this head will not
 *  serve, and where one is set. Null when every tier has a model. */
export function vacantText(tiers: readonly string[]): string | null {
  if (tiers.length === 0) return null;
  return `no model fills the ${tiers.join(', ')} tier${tiers.length === 1 ? '' : 's'} here; set slot = "${tiers[0]}" on one of this head's models in splice.toml to fill one`;
}

/** One head's rack: the tiers no model fills, named on one line, then the models that fill a tier,
 *  then every model that fills none.
 *
 *  The vacant tiers were four struck strips of dashes at the top of every rack (console review,
 *  2026-09-24); FEATURES 4.8 asks the page to say which tiers Claude Code will not get, and a line
 *  naming them says it without four rows of nothing. */
export function HeadCatalogBay({ head, selected, onSelect }: {
  head: HeadCatalog;
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const unslotted = head.models.filter((model) => model.slot === null);
  const tiers = slotTiers(head);
  const vacant = vacantText(tiers.filter((tier) => tier.model === null).map((tier) => tier.slot));
  return (
    <Bay
      label={head.head}
      count={head.models.length}
      empty={{ text: EMPTIES.noModels.text, source: EMPTIES.noModels.source }}
      fields={<ColumnNames />}
    >
      {vacant === null ? null : <p key="vacant" className="myx-models-vacant">{vacant}</p>}
      {tiers.map((tier) => (
        tier.model === null ? null : (
          <ModelStrip
            key={tier.model.id}
            model={tier.model}
            slot={tier.slot}
            selected={selected === tier.model.id}
            onOpen={() => onSelect(tier.model?.id ?? '')}
          />
        )
      ))}
      {unslotted.map((model) => (
        <ModelStrip
          key={model.id}
          model={model}
          slot={S.noSlot}
          selected={selected === model.id}
          onOpen={() => onSelect(model.id)}
        />
      ))}
    </Bay>
  );
}

/** The rate card and windows of the opened model, which is what the strip could not fit. */
export function ModelDetail({ model, windows }: { model: CatalogModel; windows: HeadWindows | null }) {
  // The edge is the state, so it has to read the same absence the fields do: a missing key used to
  // paint GREEN and label the strip `rates` for a model that declares none (M1-41).
  const noRates = model.rates === undefined || model.rates === null;
  // A FRAGMENT, NOT A SECOND .myx-models-detail (M1-121). This returned <div
  // className="myx-models-detail"> while index.tsx already wraps it in <aside
  // className="myx-models-detail">, so the class was on two NESTED boxes and every rule for it
  // applied twice. It surfaced when this row put overflow-x on that class: the scrollport landed
  // on the inner div by accident, the outer aside measured scrollWidth === clientWidth, and the
  // instrument read "nothing to scroll" on a column that could in fact scroll. One box now, so the
  // aside is the column and the scrollport, and there is one place to read its rules.
  return (
    <>
      <h3 className="myx-models-sub">{model.id}</h3>
      <p className="myx-models-note">{model.description}</p>
      <Strip edge={noRates ? 'grey' : 'green'} edgeLabel={noRates ? S.noRates : S.rates} ariaLabel={S.rates}>
        <StripField w={13} label={S.rateInput} value={rateValue(model, (rates) => rates.input)} />
        <StripField w={13} label={S.rateRead} value={rateValue(model, (rates) => rates.cache_read)} />
        <StripField w={13} label={S.rateWrite} value={model.rates?.cache_write === undefined ? S.absent : String(model.rates.cache_write)} />
        <StripField w={13} label={S.rateOutput} value={rateValue(model, (rates) => rates.output)} />
      </Strip>
      <Strip edge="grey" edgeLabel={S.window} ariaLabel={S.tiers}>
        <StripField w={15} label={S.headWindow} value={windows === null || windows.headWindow === null ? S.absent : fmtTokens(windows.headWindow)} />
        <StripField w={15} label={S.defaultWindow} value={windows === null || windows.defaultWindow === null ? S.absent : fmtTokens(windows.defaultWindow)} />
        <StripField w={15} label={S.extraWindows} value={windows === null ? S.absent : windows.extraWindows} />
        <StripField w={15} label={S.windowRules} value={windows === null ? S.absent : windows.windowRules} />
      </Strip>
    </>
  );
}

export function EmptyBay({ text, source }: { text: string; source: string }) {
  return <Empty text={text} source={source} />;
}
