import type { ReactNode } from 'react';
import { W } from '../lib/words';

export function PageHead({ title, lede, tools }: { title: string; lede?: string; tools?: ReactNode }) {
  return (
    <header className="page-head">
      <div>
        <h1>{title}</h1>
        {lede === undefined ? null : <p className="lede">{lede}</p>}
      </div>
      {tools === undefined ? null : <div className="tools">{tools}</div>}
    </header>
  );
}

export function GroupHead({ title, count, why, action }: { title: string; count?: number; why?: string; action?: ReactNode }) {
  return (
    <div className="group-head">
      <h2>{title}</h2>
      {count === undefined ? null : <span className="n">{count}</span>}
      {why === undefined ? null : <span className="why">{why}</span>}
      {action === undefined ? null : <span className="group-action">{action}</span>}
    </div>
  );
}

/** A page with nothing to show says why, in one sentence, and what fills it. */
export function Empty({ title, why }: { title: string; why?: string }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {why === undefined ? null : <p className="empty-why">{why}</p>}
    </div>
  );
}

/** What a read that failed says: the daemon's own sentence. */
export function Fault({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="fault" role="alert">
      <p>{message}</p>
      {onRetry === undefined ? null : (
        <button type="button" className="btn sm" onClick={onRetry}>
          {W.retry}
        </button>
      )}
    </div>
  );
}
