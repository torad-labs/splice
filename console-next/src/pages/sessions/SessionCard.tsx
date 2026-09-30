import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Link } from 'react-router';
import type { ModelColour } from '../../lib/model';
import type { Handoff, SessionState } from '../../lib/sessions';
import { activityText, needsPerson, sessionKey, sessionLabel, stateTone, stateWord, repoName } from '../../lib/sessions';
import type { SessionRow } from '../../types/sessions';
import { Grip, State, Window } from '../../ui';
import { S } from '../shared/copy';
import { OpenLink, ResumeCopy, StopTurn, sessionPath } from '../shared/SessionActions';
import { P } from './copy';
import './sessions.css';

export interface CardFacts {
  row: SessionRow;
  state: SessionState;
  colour: ModelColour;
  /** The head's command (`claude-grok`), or null for a session splice did not start. */
  head: string | null;
  hand: Handoff;
  /** How long it has been in its state (ms), when the registry gave a time. */
  since: number | null;
  /** A busy session with no live turn is running a tool: how long it has been quiet, else null. */
  quiet: number | null;
}

const handText = (hand: Handoff): string | null =>
  hand === null ? null : hand.kind === 'lead' ? `${P.leadOf} ${hand.peers}` : `${P.from} ${hand.peer}`;

/** One session: its title, its state, one line of what it is doing, one quiet line of facts, and at most one act. */
export function SessionCard({ facts, sortable = true }: { facts: CardFacts; sortable?: boolean }) {
  const { row, state, colour, head, hand, since, quiet } = facts;
  const key = sessionKey(row);
  const drag = useSortable({ id: key, disabled: !sortable });
  const needs = needsPerson(state);
  const meta = [repoName(row), head, handText(hand)].filter((part): part is string => part !== null);
  return (
    <Window
      as="li"
      colour={colour}
      attention={needs}
      className={`card${drag.isDragging ? ' dragging' : ''}`}
      ref={drag.setNodeRef}
      style={{ transform: CSS.Transform.toString(drag.transform), transition: drag.transition }}
      {...drag.listeners}
    >
      <div className="bar">
        <h3>
          <Link className="card-link" to={sessionPath(row)}>
            {sessionLabel(row) || S.unnamed}
          </Link>
        </h3>
        <State tone={stateTone(state)}>{stateWord(state)}</State>
        {sortable ? (
          <button type="button" className="grip" ref={drag.setActivatorNodeRef} aria-label={P.dragHandle} aria-describedby={drag.attributes['aria-describedby']}>
            <Grip />
          </button>
        ) : null}
      </div>
      <div className="glass one">
        <p className={state === 'working' ? 'cur' : undefined}>{activityText(state, since, quiet)}</p>
      </div>
      <div className="quiet-meta">
        {meta.map((part) => (
          <span key={part}>{part}</span>
        ))}
      </div>
      {needs ? (
        <div className="acts">
          {state === 'stuck' ? <StopTurn row={row} /> : <OpenLink row={row} />}
          <ResumeCopy row={row} />
        </div>
      ) : null}
    </Window>
  );
}
