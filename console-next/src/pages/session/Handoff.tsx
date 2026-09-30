import type { ModelColour } from '../../lib/model';
import { clockTime } from '../../lib/format';
import { readable } from '../../lib/message';
import type { HandedEdge } from '../../types/sessions';
import { Markdown } from '../../ui';
import { P } from './copy';

/** What another session handed over (or this one handed on): visibly not a person's message, in the peer's colour. */
export function Handoff({ edge, peer, colour }: { edge: HandedEdge; peer: string; colour: ModelColour }) {
  const said = edge.text === null ? null : readable(edge.text);
  return (
    <div className="msg peer" style={{ '--c': colour === 'none' ? 'var(--tan)' : `var(--${colour})` } as React.CSSProperties}>
      <div className="stamp">
        {edge.direction === 'in' ? P.handoffFrom : P.handoffTo} {peer} · {clockTime(edge.at)}
      </div>
      {said === null ? <p className="hint">{edge.text === null ? (edge.missing_reason ?? P.handoffMissing) : P.handoffMissing}</p> : <Markdown>{said}</Markdown>}
    </div>
  );
}
