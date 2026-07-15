// Hand-authored instrument primitives (.myx-*). No component library (locked).
// Every data-driven surface designs its full state cycle: loading skeletons
// shaped like the final layout, composed empty states, inline errors.
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { cx } from '../lib';
import './ui.css';

export function Panel({ title, actions, children, tone }: {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
  tone?: 'signal';
}) {
  return (
    <section className={cx('myx-panel', tone === 'signal' && 'myx-panel-signal')}>
      <header className="myx-panel-head">
        <h2 className="myx-panel-title">{title}</h2>
        {actions ? <div className="myx-panel-actions">{actions}</div> : null}
      </header>
      <div className="myx-panel-body">{children}</div>
    </section>
  );
}

export type PillTone = 'pos' | 'neg' | 'info' | 'amber' | 'mute';

export function StatusPill({ tone, children }: { tone: PillTone; children: ReactNode }) {
  return <span className={cx('myx-pill', `myx-pill-${tone}`)}>{children}</span>;
}

export function Metric({ label, value, unit, tone }: {
  label: string;
  value: string;
  unit?: string;
  tone?: PillTone;
}) {
  return (
    <div className="myx-metric">
      <div className={cx('myx-metric-value', tone && `myx-ink-${tone}`)}>
        {value}
        {unit ? <span className="myx-metric-unit">{unit}</span> : null}
      </div>
      <div className="myx-metric-label">{label}</div>
    </div>
  );
}

export function Btn({ children, onClick, busy, disabled, kind = 'control', type = 'button' }: {
  children: ReactNode;
  onClick?: () => void;
  busy?: boolean;
  disabled?: boolean;
  kind?: 'control' | 'primary' | 'danger';
  type?: 'button' | 'submit';
}) {
  return (
    <button
      type={type}
      className={cx('myx-btn', `myx-btn-${kind}`, busy && 'myx-btn-busy')}
      onClick={onClick}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
    >
      {busy ? 'working' : children}
    </button>
  );
}

export function Field({ label, htmlFor, children }: { label: string; htmlFor?: string; children: ReactNode }) {
  return (
    <label className="myx-field" htmlFor={htmlFor}>
      <span className="myx-field-label">{label}</span>
      {children}
    </label>
  );
}

export function Well({ children }: { children: ReactNode }) {
  return <div className="myx-well">{children}</div>;
}

export function EmptyState({ label }: { label: string }) {
  return <p className="myx-empty" role="status">{label}</p>;
}

export function ErrorNote({ message }: { message: string }) {
  return <p className="myx-error" role="alert">error: {message}</p>;
}

export function SkeletonRows({ rows, cols }: { rows: number; cols: number }) {
  return (
    <div className="myx-skeleton" aria-hidden="true">
      {Array.from({ length: rows }, (_, r) => (
        <div className="myx-skeleton-row" key={r}>
          {Array.from({ length: cols }, (_, c) => <span className="myx-skeleton-cell" key={c} />)}
        </div>
      ))}
    </div>
  );
}

export function Stale({ lastUpdated }: { lastUpdated: number | null }) {
  if (!lastUpdated) return null;
  const age = Date.now() - lastUpdated;
  if (age < 15_000) return null;
  return <span className="myx-stale">stale: {Math.floor(age / 1000)}s</span>;
}

/** A hairline measurement track; the fill is the ONLY warn-tinted element
 * (measurement ink, never a colored panel). Tokens-only CSS. */
export function MeterBar({ pct, tone }: { pct: number; tone: 'pos' | 'amber' | 'neg' | 'mute' }) {
  const clamped = Math.max(0, Math.min(100, pct));
  return (
    <div className="myx-meter" role="img" aria-label={`${Math.round(clamped)} percent`}>
      <div className={cx('myx-meter-fill', `myx-meter-fill-${tone}`)} style={{ width: `${clamped}%` }} />
    </div>
  );
}

/** Two-step destructive control: first click arms (danger styling + cancel,
 * auto-disarms after 4s), second click fires. For actions that interrupt a
 * live session (stop/restart) where a single misclick is costly. */
export function ConfirmBtn({ children, onConfirm, busy }: {
  children: ReactNode;
  onConfirm: () => void;
  busy?: boolean;
}) {
  const [armed, setArmed] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current);
  }, []);

  const disarm = () => {
    setArmed(false);
    if (timerRef.current) clearTimeout(timerRef.current);
  };

  const arm = () => {
    setArmed(true);
    timerRef.current = setTimeout(() => setArmed(false), 4000);
  };

  if (!armed) {
    return (
      <button
        type="button"
        className="myx-btn myx-btn-danger"
        onClick={arm}
        disabled={busy}
        aria-busy={busy || undefined}
      >
        {busy ? 'working' : children}
      </button>
    );
  }

  return (
    <span className="myx-confirm">
      <button
        type="button"
        className="myx-btn myx-btn-danger myx-btn-armed"
        onClick={() => { disarm(); onConfirm(); }}
        disabled={busy}
        aria-busy={busy || undefined}
      >
        {busy ? 'working' : 'confirm'}
      </button>
      <button type="button" className="myx-btn" onClick={disarm} disabled={busy}>cancel</button>
    </span>
  );
}
