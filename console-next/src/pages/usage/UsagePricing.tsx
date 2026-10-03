import { Link } from 'react-router';
import { useModels } from '../../api/models';
import { useTopology } from '../../api/config';
import { isPendingRoute } from '../../api/auth';
import { failureText } from '../../api/client';
import type { HeadCatalog } from '../../types/models';
import type { HeadStatus } from '../../types/core';
import type { TurnsState } from '../../types/perf';
import { Fault } from '../../ui';
import { B } from './copy';

export function CommandPricing({ command, label, catalog, unpriced, path }: {
  command: string; label: string; catalog: HeadCatalog | undefined; unpriced: number; path: string | undefined;
}) {
  const missing = catalog?.models.filter(model => model.rates == null) ?? [];
  if (catalog !== undefined && missing.length === 0 && unpriced === 0) return null;
  const example = missing[0]?.id ?? catalog?.pinned_model;
  return <details className="usage-pricing-command">
    <summary>{B.pricesFor(label)}</summary>
    <p>{catalog === undefined ? B.priceCatalogMissing : missing.length === 0 ? B.pricesDeclared : missing.length === catalog.models.length ? B.noPrices : B.missingPrices(missing.map(model => model.label || model.id).join(', '))}</p>
    {unpriced === 0 ? null : <p>{B.recordedMissing(unpriced)}</p>}
    <p>{B.priceHow}</p>
    {example === undefined || example === '' ? null : <pre aria-label={B.priceExample}>{`[heads.${JSON.stringify(command)}.rates]\n${JSON.stringify(example)} = { input = INPUT_USD, cache_read = CACHE_READ_USD, output = OUTPUT_USD }`}</pre>}
    <Link to="/settings/advanced">{B.priceConfig}</Link>
    {path === undefined ? null : <p><code>{path}</code></p>}
  </details>;
}

export function UsagePricing({ heads, recorded }: { heads: readonly HeadStatus[]; recorded: TurnsState | null }) {
  const models = useModels();
  const topology = useTopology();
  const catalog = models.data === undefined || isPendingRoute(models.data) ? undefined : models.data;
  const path = topology.data === undefined || isPendingRoute(topology.data) ? undefined : topology.data.path;
  const commands = [...new Set([...heads.map(head => head.key), ...(catalog?.heads.map(head => head.head) ?? []), ...(recorded?.landed.map(row => row.head) ?? [])])];
  return <section className="section usage-pricing" aria-labelledby="usage-pricing">
    <h2 id="usage-pricing">{B.pricing}</h2><p className="why">{B.pricingWhy}</p>
    {models.isError ? <Fault message={failureText(models.error)} onRetry={() => void models.refetch()} />
      : models.isPending ? <p className="hint">{B.pricesReading}</p>
      : isPendingRoute(models.data) ? <p className="hint">{B.pricesUnavailable}</p> : commands.map(command => <CommandPricing key={command} command={command} label={heads.find(head => head.key === command)?.label ?? command} catalog={catalog?.heads.find(head => head.head === command)} unpriced={recorded?.landed.filter(row => row.head === command && row.local_step !== 1 && row.cost_usd == null).length ?? 0} path={path} />)}
  </section>;
}
