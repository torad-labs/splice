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

function rateValue(model: CatalogModel, pick: (rates: NonNullable<CatalogModel['rates']>) => number): string {
  return model.rates === null ? S.noRates : String(pick(model.rates));
}

function ModelStrip({ model, slot, selected, onOpen }: {
  model: CatalogModel;
  slot: string;
  selected: boolean;
  onOpen: () => void;
}) {
  return (
    <Strip
      edge={model.rates === null ? 'grey' : 'green'}
      edgeLabel={model.pinned ? S.pinnedYes : slot}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${S.openModel} ${model.id}`}
    >
      {/* Six fields and no more: a strip whose fields outrun its bay squeezes the holder edge, and
          a wrapped edge label is the one defect that makes a rack stop reading as a rack. The cache
          read and write rates and the pinned flag live in the opened strip's detail. */}
      <StripField w={24} label={S.model} value={model.id} mono={false} />
      <StripField w={8} label={S.slot} value={slot} mono={false} />
      <StripField w={13} label={S.contextWindow} value={fmtTokens(model.context_window)} />
      <StripField w={24} label={S.windowSource} value={model.context_window_source} mono={false} />
      <StripField w={12} label={S.rateInput} value={rateValue(model, (rates) => rates.input)} />
      <StripField w={12} label={S.rateOutput} value={rateValue(model, (rates) => rates.output)} />
    </Strip>
  );
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
            <StripField w={24} label={S.model} value={S.undeclared} mono={false} />
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
          slot="none"
          selected={selected === model.id}
          onOpen={() => onSelect(model.id)}
        />
      ))}
    </Bay>
  );
}

/** The rate card and windows of the opened model, which is what the strip could not fit. */
export function ModelDetail({ model, head }: { model: CatalogModel; head: HeadCatalog }) {
  return (
    <div className="myx-models-detail">
      <h3 className="myx-models-sub">{model.id}</h3>
      <p className="myx-models-note">{model.description}</p>
      <Strip edge={model.rates === null ? 'grey' : 'green'} edgeLabel={model.rates === null ? S.noRates : S.rates} ariaLabel={S.rates}>
        <StripField w={13} label={S.rateInput} value={rateValue(model, (rates) => rates.input)} />
        <StripField w={13} label={S.rateRead} value={rateValue(model, (rates) => rates.cache_read)} />
        <StripField w={13} label={S.rateWrite} value={model.rates?.cache_write === undefined ? S.noRates : String(model.rates.cache_write)} />
        <StripField w={13} label={S.rateOutput} value={rateValue(model, (rates) => rates.output)} />
      </Strip>
      <Strip edge="grey" edgeLabel={head.key} ariaLabel={S.tiers}>
        <StripField w={15} label={S.headWindow} value={head.context_window === null ? 'none' : fmtTokens(head.context_window)} />
        <StripField w={15} label={S.defaultWindow} value={fmtTokens(head.default_context_window)} />
        <StripField w={15} label={S.extraWindows} value={head.extra_windows.length} />
        <StripField w={15} label={S.windowRules} value={head.window_rules.length} />
      </Strip>
    </div>
  );
}

export function EmptyBay({ text, source }: { text: string; source: string }) {
  return <Empty text={text} source={source} />;
}
