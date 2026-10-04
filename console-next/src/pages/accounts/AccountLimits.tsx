import type { AccountWindow } from '../../types/accounts';
import { Q } from '../../lib/words-quota';
import { A } from './copy';

export function limitName(window: AccountWindow | null, short: boolean, kind?: string): string {
  if (window === null) return short ? A.fiveHours : A.weekly;
  // Muse names this window weekly; its seconds are time remaining, not a provider-reported duration.
  if (!short && kind === 'muse-oauth') return A.weekly;
  if (window.length_known === false) return short ? A.shortWindow : A.longWindow;
  if (window.seconds === 18_000) return A.fiveHours;
  if (window.seconds === 604_800) return A.weekly;
  if (window.seconds % 86_400 === 0) return A.days(window.seconds / 86_400);
  return window.seconds % 3600 === 0 ? A.hours(window.seconds / 3600) : short ? A.shortWindow : A.longWindow;
}

function Limit({ window, short, now, kind }: { window: AccountWindow | null; short: boolean; now: number; kind: string | undefined }) {
  const name = limitName(window, short, kind);
  const label = window?.model === undefined ? name : A.modelWindow(name, window.model);
  const reset = window?.reset_epoch_seconds ?? null;
  const elapsed = reset !== null && reset * 1000 <= now;
  const old = window?.current === false;
  const pct = elapsed || old ? null : window?.used_percent ?? null;
  return (
    <div className="account-limit">
      <div className="account-limit-label"><span>{label}</span><b>{pct === null ? A.unreported : A.used(pct)}</b></div>
      {pct === null ? null : <div className="account-limit-track" role="img" aria-label={`${label}: ${A.used(pct)}`}>
        <i style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} />
      </div>}
      <p className="hint">{reset === null ? `${A.resets}: ${A.unreported}` : `${elapsed ? A.reset : A.resets} ${A.instant(reset)}`}{old && !elapsed ? ` · ${A.readingOld}` : ''}</p>
      {window === null ? null : <p className="hint">{Q.observed(window.observed_at_epoch_seconds == null ? null : A.instant(window.observed_at_epoch_seconds))}</p>}
    </div>
  );
}

export function AccountLimits({ windows, now, kind }: { windows: readonly AccountWindow[]; now: number; kind?: string }) {
  const short = windows.filter(window => window.seconds <= 21_600);
  const long = windows.filter(window => window.seconds > 21_600);
  return (
    <div className="account-limits">
      {(short.length ? short : [null]).map((window, index) => <Limit key={`short-${window?.model ?? index}`} window={window} short now={now} kind={kind} />)}
      {(long.length ? long : [null]).map((window, index) => <Limit key={`long-${window?.model ?? index}`} window={window} short={false} now={now} kind={kind} />)}
    </div>
  );
}
