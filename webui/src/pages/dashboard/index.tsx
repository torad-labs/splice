// ARRIVE: proxy up? version? load? — then usage, then live traffic.
import { useEffect } from 'react';
import { startStatusPolling, useStatus } from '@entities/proxy-status';
import { startUsagePolling } from '@entities/usage';
import { LiveRequestTable } from '@widgets/live-request-table';
import { UsageMeter } from '@widgets/usage-meter';
import { fmtDurationS } from '@shared/lib';
import { ErrorNote, Metric, Panel } from '@shared/ui';

export function DashboardPage() {
  useEffect(() => {
    const stops = [startStatusPolling(2000), startUsagePolling(5000)];
    return () => stops.forEach((stop) => stop());
  }, []);

  const { data, error } = useStatus((s) => s);

  return (
    <div className="myx-stack">
      <Panel title="proxy">
        {error ? <ErrorNote message={error} /> : null}
        {data ? (
          <div className="myx-metric-row">
            <Metric label="head" value={data.proxy} />
            <Metric label="version" value={`v${data.version}`} tone="info" />
            <Metric label="uptime" value={fmtDurationS(data.uptime_s)} />
            {data.mode ? <Metric label="arm" value={data.mode} tone="amber" /> : null}
            {data.pinned_model ? <Metric label="pinned model" value={data.pinned_model} /> : null}
            {data.show_reasoning ? <Metric label="mirror" value={data.show_reasoning} tone={data.show_reasoning === 'text' ? 'pos' : 'amber'} /> : null}
          </div>
        ) : null}
      </Panel>
      <UsageMeter />
      <LiveRequestTable />
    </div>
  );
}
