import type { AccountWindow } from '../../types/accounts';
import { localInstantText } from '../../lib/heads';
import { fmtDurationS } from '../../lib/format';
import { A } from './copy';

export function limitName(window: AccountWindow | null, short: boolean): string {
  if (window === null) return short ? A.fiveHours : A.weekly;
  if (window.length_known === false) return short ? A.shortWindow : A.longWindow;
  if (window.seconds === 18_000) return A.fiveHours;
  if (window.seconds === 604_800) return A.weekly;
  if (window.seconds % 86_400 === 0) return A.days(window.seconds / 86_400);
  return window.seconds % 3600 === 0 ? A.hours(window.seconds / 3600) : fmtDurationS(window.seconds);
}

function Limit({ window, short, now }: { window: AccountWindow | null; short: boolean; now: number }) {
  const name = limitName(window, short);
  const label = window?.model === undefined ? name : A.modelWindow(name, window.model);
  const pct = window?.used_percent ?? null;
  const reset = window?.reset_epoch_seconds ?? null;
  const previous = window?.current === false || (reset !== null && reset * 1000 <= now);
  return (
    <div className="account-limit">
      <div className="account-limit-label"><span>{label}</span><b>{pct === null ? A.unreported : A.used(pct)}</b></div>
      {pct === null ? null : <div className="account-limit-track" role="img" aria-label={`${label}: ${A.used(pct)}${previous ? `, ${A.oldReading}` : ''}`}>
        <i style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} data-stale={previous ? '' : undefined} />
      </div>}
      <p className="hint">{reset === null ? `${A.resets}: ${A.unreported}` : `${reset * 1000 > now ? A.resets : A.reset} ${localInstantText(reset, 'America/Chicago')} ${A.ct}`}{previous ? ` · ${A.oldReading}` : ''}</p>
    </div>
  );
}

export function AccountLimits({ windows, now }: { windows: readonly AccountWindow[]; now: number }) {
  const short = windows.filter(window => window.seconds <= 21_600);
  const long = windows.filter(window => window.seconds > 21_600);
  return (
    <div className="account-limits">
      {(short.length ? short : [null]).map((window, index) => <Limit key={`short-${window?.model ?? index}`} window={window} short now={now} />)}
      {(long.length ? long : [null]).map((window, index) => <Limit key={`long-${window?.model ?? index}`} window={window} short={false} now={now} />)}
    </div>
  );
}
