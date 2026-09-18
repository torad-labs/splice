// Hidden until an explicit action. For secrets and system prompts: the point is
// that the bytes are not in the DOM at all until the reader asks, so this
// unmounts rather than hiding with CSS. There is no collapse control — a
// revealed secret is not un-revealed, it is scrolled past.
import { useState } from 'react';
import type { ReactNode } from 'react';

export function Reveal({ label, children }: { label: string; children: ReactNode }) {
  const [shown, setShown] = useState(false);
  return (
    <div className="myx-reveal">
      <button
        type="button"
        className="myx-reveal-btn"
        aria-expanded={shown}
        onClick={() => setShown(true)}
      >
        {label}
      </button>
      {shown ? <div className="myx-reveal-body">{children}</div> : null}
    </div>
  );
}
