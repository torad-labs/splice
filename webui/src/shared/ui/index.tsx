// Hand-authored instrument primitives (.myx-*). No component library (locked).
// Every data-driven surface designs its full state cycle: loading skeletons
// shaped like the final layout, composed empty states, inline errors.
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { cx } from '../lib';
import './ui.css';

/* PANEL AND EMPTYSTATE WERE EXPORTED AND PLACED NOWHERE, AND ARE DELETED (M1-101).
   Panel had zero JSX sites in the whole console and no hand-rolled equivalent to replace: no file
   outside this one writes a `myx-panel*` class, so nothing was waiting for it. EmptyState had zero
   sites for a different and more interesting reason -- the console's honest empties are rendered by
   ITS OWN SIBLING, `Empty`, fifty-five times over, and `Empty` carries the `source` field the
   absence vocabulary needs (M1-20) while EmptyState was a bare `<p>` with a label. It did not lose
   to copy-paste; it lost to a better primitive that already existed.
   THREE INDEPENDENT CHECKS, because this campaign has a row that nearly deleted two finished
   features on one clean grep: (1) zero `<Panel` or `<EmptyState` JSX sites anywhere under
   webui/src or webui/tests, inside the library and outside it; (2) nothing outside this file NAMES
   either identifier; (3) every class token they carried -- myx-panel, -signal, -head, -title,
   -actions, -body and myx-empty -- appears in exactly two files, this one and ui.css, so removing
   both leaves no dangling class. The sheet rules went with them. */

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

// The Strip Bay world (v0.4.0). One file per primitive; the barrel above stays
// for the old instruments, which keep working unchanged until M2 removes their
// last consumer. New code imports from here and never from a primitive's file.
export { HolderEdge } from './holder-edge';
export { Strip } from './strip';
export { StripField } from './strip-field';
export { Bay } from './bay';
export { ScopeInset } from './scope-inset';
export { FieldBox } from './field-box';
export { Reveal } from './reveal';
export { Empty } from './empty';
export { Figure } from './figure';
export type { Provenance } from './field-box';
export type { Edge, Basis } from './types';
