import { useState } from 'react';
import { failureText } from '../../api/client';
import { useSessionEdges } from '../../api/sessions';
import { handoffText } from '../../lib/project-teams';
import { peerLabel, sessionLabel } from '../../lib/sessions';
import type { SessionEdge, SessionRow } from '../../types/sessions';
import { Fault } from '../../ui';
import { T } from './copy';

const clock = (at: number): string => new Date(at).toLocaleString('en-US', {
  month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short',
});

export function MessageText({ edge }: { edge: SessionEdge }) {
  const read = useSessionEdges(edge.from);
  if (read.isError) return <Fault message={failureText(read.error)} onRetry={() => void read.refetch()} />;
  if (read.isPending) return <p className="hint">{T.readingMessage}</p>;
  const message = handoffText(edge, read.data.edges);
  return <p className="team-message">{message?.text ?? message?.missing_reason ?? read.data.reason ?? T.missingMessage}</p>;
}

/** Transcript text is requested only when the operator opens the observed handoff. */
export function HandoffMessage({ edge, rows }: { edge: SessionEdge; rows: readonly SessionRow[] }) {
  const [open, setOpen] = useState(false);
  const sender = rows.find(row => row.session_id === edge.from);
  if (sender === undefined) return null;
  return (
    <li>
      <details onToggle={event => setOpen(event.currentTarget.open)}>
        <summary><span>{sessionLabel(sender)} {T.to} {peerLabel(rows, edge)}</span><time dateTime={new Date(edge.at).toISOString()}>{clock(edge.at)}</time></summary>
        {open ? <MessageText edge={edge} /> : null}
      </details>
    </li>
  );
}
