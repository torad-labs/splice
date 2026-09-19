// Hidden until an explicit action. For long or over-the-shoulder content — a system prompt, a chat
// body, a file's contents: the point is that the bytes are not in the DOM at all until the reader
// asks, so this unmounts rather than hiding with CSS. There is no collapse control — what was
// revealed is not un-revealed, it is scrolled past.
//
// THE GUARANTEE IS DOM-SCOPED, AND ONLY DOM-SCOPED. Every call site wraps a field the page has
// ALREADY fetched, so the bytes crossed the wire before the button existed; what this buys is that
// they are not in the document, a screenshot, or the accessibility tree until someone asks. That is
// sound for what this console shows — the payload is the operator's own config and every route is
// mgmt-key gated — and it is NOT a control for a third-party credential. A field that must not be
// FETCHED needs a read of its own; do not wire one behind this and read the header as cover.
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
