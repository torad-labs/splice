// The mirror instrument — the page the reference dashboard does not have.
// Controls the distillation loop (mirror mode, effort/summary fallbacks) and
// shows live emission signals from the proxy log.
import { useEffect } from 'react';
import { fetchConfig } from '@entities/config';
import { startLogsPolling } from '@entities/logs';
import { startStatusPolling, useStatus } from '@entities/proxy-status';
import { SetEffort } from '@features/set-effort';
import { ToggleMirror } from '@features/toggle-mirror';
import { LogTail } from '@widgets/log-tail';
import { Panel, StatusPill, Well } from '@shared/ui';

const EMISSION_SIGNALS = /promote-to-text|reasoning|stream fatal|compact request/i;

export function ReasoningPage() {
  useEffect(() => {
    const stops = [startStatusPolling(3000), startLogsPolling(5000)];
    void fetchConfig();
    return () => stops.forEach((stop) => stop());
  }, []);
  const status = useStatus((s) => s.data);

  return (
    <div className="myx-stack">
      <Panel title="mirror state">
        <div className="myx-pill-row">
          <StatusPill tone={status?.show_reasoning === 'text' ? 'pos' : 'amber'}>
            mirror {status?.show_reasoning ?? 'unknown'}
          </StatusPill>
          <StatusPill tone="mute">effort fallback {status?.effort_fallback ?? 'unset'}</StatusPill>
          <StatusPill tone="mute">summary fallback {status?.summary_fallback ?? 'unset'}</StatusPill>
        </div>
        <Well>
          the loop: reasoning items never replay upstream (locked); each turn re-derives from live
          evidence, and with mirror mode text the summary persists in the transcript as a visible
          [reasoning summary] block.
        </Well>
        <div className="myx-control-grid">
          <ToggleMirror />
          <SetEffort />
        </div>
        <p className="myx-footnote">
          precedence: explicit body effort, then the Claude /effort picker (thinking budget), then
          the fallback set here, then high. compact turns always run effort low.
        </p>
      </Panel>
      <LogTail filter={EMISSION_SIGNALS} />
    </div>
  );
}
