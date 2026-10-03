import { fmtInt, fmtTokens, fmtUsd } from '../../lib/format';
import type { projectUsage } from '../../lib/project-teams';
import { Fault } from '../../ui';
import { T } from './copy';

export function ProjectUsage({ value, reading, partial, error, retry }: {
  value: ReturnType<typeof projectUsage> | null; reading: boolean; partial: boolean; error: string | null; retry: () => void;
}) {
  if (error !== null) return <Fault message={error} onRetry={retry} />;
  if (reading) return <p className="hint" role="status">{T.usageReading}</p>;
  if (value === null) return <p className="hint">{T.usageUnavailable}</p>;
  if (value.requests === 0 && !partial) return <p className="hint">{T.usageNone}</p>;
  const measured = (value: number | null, format: (n: number) => string, missing: boolean): string => value === null ? T.usageUnknown : missing ? T.atLeast(format(value)) : format(value);
  return <section className="project-team-usage" aria-label={T.usage}>
    <dl>
      <div><dt>{T.usage}</dt><dd>{measured(value.requests, fmtInt, partial)}</dd></div>
      <div><dt>{T.usageTokens}</dt><dd>{measured(value.input, fmtTokens, partial || value.missingInput > 0)} / {measured(value.output, fmtTokens, partial || value.missingOutput > 0)}</dd></div>
      <div><dt>{T.usageCost}</dt><dd>{measured(value.cost, fmtUsd, partial || value.unpriced > 0)}</dd></div>
    </dl>
    {partial ? <p className="hint">{T.usagePartial}</p> : null}
    {value.unpriced === 0 ? null : <p className="hint">{T.usageUnpriced(value.unpriced)}</p>}
  </section>;
}
