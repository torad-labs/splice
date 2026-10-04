import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { FleetCard as Facts, FleetLine } from '../../lib/fleet';
import { localZonedInstantText } from '../../lib/heads';
import { Q } from '../../lib/words-quota';
import { State, Window } from '../../ui';

/** One measured window: a command-colour bar, or a red hatch for a held refusal. */
function Gauge({ line }: { line: Extract<FleetLine, { kind: 'gauge' }> }) {
  return (
    <div className="gauge">
      <div className="gl">
        <span>{line.name}</span>
        {line.full ? <b>{line.pct}%</b> : null}
        {line.note === '' ? null : <small>{line.note}</small>}
      </div>
      <small>{Q.observed(line.observedAt == null ? null : localZonedInstantText(line.observedAt))}</small>
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
      ref={(node) => { drag.setNodeRef(node); drag.setActivatorNodeRef(node); }}
      style={{ transform: CSS.Transform.toString(drag.transform), transition: drag.transition }}
      {...(sortable ? drag.attributes : {})}
      role="listitem"
      {...drag.listeners}
    >
      <div className="bar">
        <h3>
          <Link className="card-link" to={`/models/${encodeURIComponent(facts.key)}`}>
            {facts.title}
          </Link>
        </h3>
        <State tone={facts.tone}>{facts.state}</State>
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
      {facts.providerAnswer == null ? null : <p className="quiet-line">{facts.providerAnswer}</p>}
      {facts.line === null && facts.none !== null ? <div className="quiet-meta"><span>{facts.none}</span></div> : null}
      {fix === null ? null : <div className="acts">{fix}</div>}
    </Window>
  );
}
