import { Send } from '../../ui';
import { P } from './copy';

/** Drawn, and honest: splice has no route that writes to a Claude Code session, so it is disabled and says so. */
export function Composer() {
  return (
    <div className="composer">
      <textarea className="in" disabled rows={2} placeholder={P.composerHint} aria-label={P.composerHint} />
      <div className="row">
        <span className="pend">{P.composerPending}</span>
        <button type="button" className="btn sm" disabled aria-disabled="true">
          {P.send}
          <Send />
        </button>
      </div>
    </div>
  );
}
