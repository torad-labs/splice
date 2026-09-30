import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { FleetCard as Facts, FleetLine } from '../../lib/fleet';
import { Grip, State, Window } from '../../ui';
import { F } from './copy';

/** The plan window a card draws: one line of terminal text and one bar. The bar is the glass ink, never the head's colour. */
function Gauge({ line }: { line: Extract<FleetLine, { kind: 'gauge' }> }) {
  return (
    <div className="gauge">
      <div className="gl">
        <span>{line.name}</span>
        <b>{line.pct}%</b>
        {line.note === '' ? null : <small>{line.note}</small>}
      </div>
      <div className={`track${line.full ? ' full' : ''}`} role="img" aria-label={`${line.name} ${line.pct}% used`}>
        <i style={{ width: `${Math.min(100, line.pct)}%` }} />
      </div>
    </div>
  );
}

/** One plan: its name, one state, the one window that matters, one quiet line of facts, and at most one act. */
export function FleetCardView({ facts, fix, sortable = true }: { facts: Facts; fix: ReactNode; sortable?: boolean }) {
  const drag = useSortable({ id: facts.key, disabled: !sortable });
  return (
    <Window
      as="li"
      colour={facts.colour}
      attention={facts.attention}
      className={`card${drag.isDragging ? ' dragging' : ''}`}
      ref={drag.setNodeRef}
      style={{ transform: CSS.Transform.toString(drag.transform), transition: drag.transition }}
      {...drag.listeners}
    >
      <div className="bar">
        <h3>
          <Link className="card-link" to={`/fleet/${encodeURIComponent(facts.key)}`}>
            {facts.title}
          </Link>
        </h3>
        <State tone={facts.tone}>{facts.state}</State>
        {sortable ? (
          <button type="button" className="grip" ref={drag.setActivatorNodeRef} aria-label={F.dragHandle} aria-describedby={drag.attributes['aria-describedby']}>
            <Grip />
          </button>
        ) : null}
      </div>
      {facts.line === null ? null : facts.line.kind === 'gauge' ? (
        <div className="glass one">
          <Gauge line={facts.line} />
        </div>
      ) : (
        <p className="quiet-line">{facts.line.text}</p>
      )}
      <div className="quiet-meta">
        {facts.meta.map((part) => (
          <span key={part}>{part}</span>
        ))}
      </div>
      {facts.line === null && facts.none !== null ? <div className="quiet-meta"><span>{facts.none}</span></div> : null}
      {fix === null ? null : <div className="acts">{fix}</div>}
    </Window>
  );
}
