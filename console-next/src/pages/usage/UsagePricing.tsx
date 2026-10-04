import { Link } from 'react-router';
import { useModels } from '../../api/models';
import { useTopology } from '../../api/config';
import { useUsage } from '../../api/queries';
import { isPendingRoute } from '../../api/auth';
import { failureText } from '../../api/client';
import type { HeadCatalog } from '../../types/models';
import type { HeadStatus } from '../../types/core';
import type { TurnsState } from '../../types/perf';
import { Fault } from '../../ui';
import { B } from './copy';

export function CommandPricing({ command, label, catalog, unpriced, path, usedModels = [], subscription, missingInput = 0, missingOutput = 0 }: {
  command: string; label: string; catalog: HeadCatalog | undefined; unpriced: number; path: string | undefined;
  usedModels?: readonly string[]; subscription?: string | undefined; missingInput?: number; missingOutput?: number;
}) {
  const missing = usedModels.filter(id => catalog?.models.find(model => model.id === id)?.rates == null);
  if (catalog !== undefined && missing.length === 0 && unpriced === 0 && missingInput === 0 && missingOutput === 0 && subscription === undefined) return null;
  return <details className="usage-pricing-command">
    <summary>{B.pricesFor(label)}</summary>
    {subscription === undefined ? null : <p>{B.subscription(subscription)}</p>}
    <p>{catalog === undefined ? B.priceCatalogMissing : missing.length > 0 ? B.missingPrices(missing.join(', ')) : usedModels.length === 0 ? B.noUsedModels : B.pricesDeclared}</p>
    {unpriced === 0 ? null : <p>{B.recordedMissing(unpriced)}</p>}
    {missingInput === 0 ? null : <p>{B.inputMissing(missingInput)}</p>}
    {missingOutput === 0 ? null : <p>{B.outputMissing(missingOutput)}</p>}
    {missingInput === 0 && missingOutput === 0 ? null : <p>{B.priceCountersWhy}</p>}
    {missing.length === 0 ? null : <>
      <p>{B.priceHow}</p>
      <pre aria-label={B.priceExample}>{`[heads.${JSON.stringify(command)}.rates]\n${missing.map(model => `${JSON.stringify(model)} = { input = INPUT_USD, cache_read = CACHE_READ_USD, output = OUTPUT_USD }`).join('\n')}`}</pre>
    </>}
    <Link to="/settings/advanced">{B.priceConfig}</Link>
    {path === undefined ? null : <p><code>{path}</code></p>}
  </details>;
}

export function UsagePricing({ heads, recorded }: { heads: readonly HeadStatus[]; recorded: TurnsState | null }) {
  const models = useModels();
  const topology = useTopology();
  const usage = useUsage();
  const catalog = models.data === undefined || isPendingRoute(models.data) ? undefined : models.data;
  const path = topology.data === undefined || isPendingRoute(topology.data) ? undefined : topology.data.path;
  const commands = [...new Set([...heads.map(head => head.key), ...Object.keys(recorded?.usageBy ?? {})])];
  return <section className="section usage-pricing" aria-labelledby="usage-pricing">
    <h2 id="usage-pricing">{B.pricing}</h2><p className="why">{B.pricingWhy}</p>
    {models.isError ? <Fault message={failureText(models.error)} onRetry={() => void models.refetch()} />
      : models.isPending ? <p className="hint">{B.pricesReading}</p>
      : isPendingRoute(models.data) ? <p className="hint">{B.pricesUnavailable}</p> : commands.map(command => <CommandPricing key={command} command={command} label={heads.find(head => head.key === command)?.label ?? command} catalog={catalog?.heads.find(head => head.head === command)} unpriced={recorded?.usageBy?.[command]?.totals.unpriced_requests ?? 0} missingInput={recorded?.usageBy?.[command]?.totals.missing_input_requests ?? 0} missingOutput={recorded?.usageBy?.[command]?.totals.missing_output_requests ?? 0} usedModels={recorded?.usageBy?.[command]?.models.flatMap(model => model.key === null ? [] : [model.key]) ?? []} subscription={usage.data?.heads.find(head => head.key === command)?.usage?.quota?.plan} path={path} />)}
  </section>;
}
