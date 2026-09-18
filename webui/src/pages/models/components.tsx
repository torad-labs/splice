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
import { S } from './strings';

/** A rate the model does not declare prints the absence glyph, not the phrase `no rates` — the
 *  strip's holder edge says `no rates` once, which is where a state belongs (m1 design review
 *  B8 and B10). */
function rateValue(model: CatalogModel, pick: (rates: NonNullable<CatalogModel['rates']>) => number): string {
  // undefined AND null: the daemon omits the key rather than sending null (M1-41, types.ts).
  return model.rates === undefined || model.rates === null ? S.absent : String(pick(model.rates));
}

/** The catalog rack's columns: the bay head prints these once and the strips below carry values
 *  only (CONTRACTS.md section 2, m1 design review B9). The struck row for a tier no model fills
 *  carries the first two, which is what a missing model means. */
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
      edgeLabel={model.pinned ? S.pinnedYes : unslotted ? S.noSlot : S.slotted}
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
          label={column.label}
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
  if (key === 'slot') return slot;
  if (key === 'contextWindow') return fmtTokens(model.context_window);
  if (key === 'windowSource') return model.context_window_source;
  if (key === 'rateInput') return rateValue(model, (rates) => rates.input);
  return rateValue(model, (rates) => rates.output);
}

/** One head's rack: its four tiers, then every model that fills no tier. */
export function HeadCatalogBay({ head, selected, onSelect }: {
  head: HeadCatalog;
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const unslotted = head.models.filter((model) => model.slot === null);
  return (
    <Bay
      label={head.key}
      count={head.models.length}
      empty={{ text: EMPTIES.noModels.text, source: EMPTIES.noModels.source }}
    >
      {slotTiers(head).map((tier) => (
        tier.model === null ? (
          <Strip
            key={tier.slot}
            edge="grey"
            edgeLabel={S.undeclared}
            struck
            ariaLabel={`${S.slot} ${tier.slot}`}
          >
            <StripField w={24} label={S.model} value={S.absent} mono={false} />
            <StripField w={8} label={S.slot} value={tier.slot} mono={false} />
          </Strip>
        ) : (
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
export function ModelDetail({ model, head }: { model: CatalogModel; head: HeadCatalog }) {
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
        <StripField w={15} label={S.headWindow} value={head.context_window === null ? S.absent : fmtTokens(head.context_window)} />
        <StripField w={15} label={S.defaultWindow} value={fmtTokens(head.default_context_window)} />
        <StripField w={15} label={S.extraWindows} value={head.extra_windows.length} />
        <StripField w={15} label={S.windowRules} value={head.window_rules.length} />
      </Strip>
    </>
  );
}

export function EmptyBay({ text, source }: { text: string; source: string }) {
  return <Empty text={text} source={source} />;
}
