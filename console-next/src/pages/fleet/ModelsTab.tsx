import { failureText } from '../../api/client';
import { useCompareModels, useModels } from '../../api/models';
import { isPendingRoute } from '../../api/auth';
import { fmtTokens } from '../../lib/format';
import { rateText, slotTiers, windowSourceText } from '../../lib/models';
import { M } from '../../lib/words-models';
import type { CatalogModel, UpstreamProvider } from '../../types/models';
import { Button } from '../../ui';
import { AddModels } from './AddModels';
import { HT } from './copy';

const windowText = (model: CatalogModel): string =>
  model.context_window === null ? HT.noWindow : HT.window(fmtTokens(model.context_window), windowSourceText(model.context_window_source));

function Compared({ provider }: { provider: UpstreamProvider }) {
  if (provider.roster === 'unpublished') return <p className="hint">{HT.unpublished}</p>;
  if (provider.roster === 'unreadable') return <p className="hint alert">{HT.unreadable} {provider.reason ?? ''}</p>;
  return (
    <>
      <p className="hint">{provider.agrees ? HT.agrees : HT.differs}</p>
      <ul className="models">
        {provider.rows.map((row) => (
          <li key={row.id}>
            <div>
              <b className="id">{row.id}</b>
              <span>{M.verdict[row.verdict]}</span>
            </div>
            {row.declared_window !== null && row.upstream_window !== null && row.declared_window !== row.upstream_window ? (
              <small>{HT.windowsDiffer(fmtTokens(row.declared_window), fmtTokens(row.upstream_window))}</small>
            ) : null}
            {row.note === '' ? null : <small>{row.note}</small>}
          </li>
        ))}
      </ul>
    </>
  );
}

/** One plan's models: the four tiers Claude Code asks for and what answers each, every model with its window and price, and the
 *  provider's own published list beside the roster when asked. */
export function ModelsTab({ head }: { head: string }) {
  const models = useModels();
  const compare = useCompareModels();
  if (models.isError) return <p className="hint alert" role="alert">{failureText(models.error)}</p>;
  if (models.data === undefined) return null;
  if (isPendingRoute(models.data)) return <p className="hint">{HT.modelsUnserved}</p>;
  const catalog = models.data.heads.find((entry) => entry.head === head);
  if (catalog === undefined || catalog.models.length === 0) return <p className="hint">{HT.noCatalog}</p>;
  const provider = compare.data?.providers.find((entry) => entry.key === catalog.provider);
  return (
    <>
      <section className="tab-section" aria-label={HT.tiers}>
        <h2 className="sub-head">{HT.tiers}</h2>
        <p className="hint">{HT.tiersWhy}</p>
        <ul className="models">
          {slotTiers(catalog).map((tier) => (
            <li key={tier.slot}>
              <div>
                <b>{HT.slot[tier.slot]}</b>
                <span>{tier.model === null ? HT.notDeclared : tier.model.label}</span>
              </div>
              {tier.model === null ? null : <small>{windowText(tier.model)}</small>}
            </li>
          ))}
        </ul>
      </section>
      <section className="tab-section" aria-label={HT.everyModel}>
        <h2 className="sub-head">{HT.everyModel}</h2>
        <div className="acts-row"><AddModels head={head} /></div>
        <ul className="models">
          {catalog.models.map((model) => (
            <li key={`${model.slot ?? ''}:${model.id}`}>
              <div>
                <b>{model.label}</b>
                <code className="id">{model.id}</code>
                {model.pinned ? <span className="chip">{HT.pinned}</span> : null}
                {model.resolved ? null : <span className="chip warn">{HT.unresolved}</span>}
              </div>
              <small>{windowText(model)} · {rateText(model.rates)}</small>
              {model.reason === undefined ? null : <small>{model.reason}</small>}
            </li>
          ))}
        </ul>
      </section>
      <section className="tab-section" aria-label={HT.compared}>
        <h2 className="sub-head">{HT.compared}</h2>
        <div className="acts-row">
          <Button small disabled={compare.isPending} onClick={() => compare.mutate(catalog.provider)}>{compare.isPending ? HT.comparing : HT.compare}</Button>
        </div>
        {compare.isError ? <p className="hint alert" role="alert">{failureText(compare.error)}</p> : null}
        {provider === undefined ? null : <Compared provider={provider} />}
      </section>
    </>
  );
}
