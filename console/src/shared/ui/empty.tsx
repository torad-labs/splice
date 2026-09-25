// An empty state: one factual line ("No sessions") and the action that fills it when there is one.
// `source` says what would fill it, behind an info mark rather than as a sentence on the page.
import type { ReactNode } from 'react';
import { InfoTip } from './charts';
import { S } from './strings';

export function Empty({ text, source, action }: { text: string; source?: string | undefined; action?: ReactNode }) {
  return (
    <div className="myx-empt" role="status">
      <span className="myx-empt-line">
        <span className="myx-empt-text">{text}</span>
        {source === undefined ? null : <InfoTip text={source} label={S.why} />}
      </span>
      {action === undefined ? null : <span className="myx-empt-action">{action}</span>}
    </div>
  );
}
