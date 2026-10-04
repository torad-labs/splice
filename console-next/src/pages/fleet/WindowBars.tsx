import { localZonedInstantText } from '../../lib/heads';
import type { PlanWindow } from '../../lib/usage';
import { Q } from '../../lib/words-quota';
import { D } from './copy';

const NAME = { '5h': '5 hours', '7d': 'Week' } as const;

/** Every plan window a head tracks, one bar each, in the glass ink. A window whose reset has passed since it was read is not
 *  a figure any more, and says so. A reset reads in the viewer's own zone, the one clock style resets share. */
export function WindowBars({ windows, now }: { windows: readonly PlanWindow[]; now: number }) {
  return (
    <div className="glass window-bars">
      {windows.map((window) => (
        <div key={window.window} className="gauge">
          <div className="gl">
            <span>{NAME[window.window]}</span>
            {window.stale ? <small>{D.resetPassed}</small> : (
              <>
                <b>{Math.round(window.pct)}%</b>
                {window.resetsAt === null ? null : <small>{window.resetsAt * 1000 > now ? 'resets' : 'reset'} {localZonedInstantText(window.resetsAt)}</small>}
              </>
            )}
          </div>
          <small>{Q.observed(window.observedAt === null ? null : localZonedInstantText(window.observedAt))}</small>
          <div className={`track${window.pct >= 100 && !window.stale ? ' full' : ''}`} role="img" aria-label={`${NAME[window.window]} ${Math.round(window.pct)}% used`}>
            <i style={{ width: `${window.stale ? 0 : Math.min(100, window.pct)}%` }} />
          </div>
        </div>
      ))}
    </div>
  );
}
