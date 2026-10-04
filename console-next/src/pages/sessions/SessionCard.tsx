import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Link } from 'react-router';
import type { ModelColour } from '../../lib/model';
import type { Handoff, SessionState } from '../../lib/sessions';
import { answerWhere, waitingAsks } from '../../lib/session-says';
import { canResumeSession, cardLine, needsPerson, sessionKey, sessionLabel, stateTone, stateWord, repoName } from '../../lib/sessions';
import { SW } from '../../lib/words-sessions';
import type { SessionAsk, SessionRow } from '../../types/sessions';
import { ModelMark, State, Window } from '../../ui';
import { S } from '../shared/copy';
import { OpenLink, ResumeCopy, sessionPath } from '../shared/SessionActions';
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
  hand === null ? null : hand.kind === 'lead' ? P.messaged(hand.peers) : `${hand.kind === 'from' ? P.lastFrom : P.lastTo} ${hand.peer}`;

/** A waiting session's questions on its card: each whole, with its options' labels. They are answered where the session runs. */
function Asks({ asks }: { asks: SessionAsk[] }) {
  return (
    <div className="glass one asks">
      {asks.map((ask) => (
        <div key={ask.question} className="ask">
          <p>{ask.question}</p>
          {ask.multi ? <p className="choose">{SW.chooseAny}</p> : null}
          <ul className="ask-options">
            {ask.options.map((option) => (
              <li key={option} className="tag">{option}</li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

/** One session: its title, its state, one line of what it is doing (a waiting one's questions, when it asked through AskUserQuestion),
 *  where a waiting one is answered, one quiet line of facts, and at most one act. */
export function SessionCard({ facts, sortable = true }: { facts: CardFacts; sortable?: boolean }) {
  const { row, state, colour, head, hand, since, quiet } = facts;
  const key = sessionKey(row);
  const drag = useSortable({ id: key, disabled: !sortable });
  const needs = needsPerson(state);
  const { line, note, agent } = cardLine(row, state, since, quiet);
  const asks = state === 'waiting' ? waitingAsks(row) : [];
  const where = needs ? answerWhere(row) : null;
  const meta = [note, repoName(row), handText(hand)].filter((part): part is string => part !== null);
  return (
    <Window
      as="li"
      colour={colour}
      attention={needs}
      className={`card${drag.isDragging ? ' dragging' : ''}`}
      ref={(node) => { drag.setNodeRef(node); drag.setActivatorNodeRef(node); }}
      style={{ transform: CSS.Transform.toString(drag.transform), transition: drag.transition }}
      {...(sortable ? drag.attributes : {})}
      role="listitem"
      {...drag.listeners}
    >
      <div className="bar">
        <h3>
          <Link className="card-link" to={sessionPath(row)}>
            {sessionLabel(row) || S.unnamed}
          </Link>
        </h3>
        <State tone={stateTone(state)}>{stateWord(state)}</State>
      </div>
      {asks.length > 0 ? (
        <Asks asks={asks} />
      ) : agent ? (
        <div className="glass one">
          <p className={state === 'working' ? 'cur' : undefined}>{line}</p>
        </div>
      ) : (
        <p className="quiet-line">{line}</p>
      )}
      {where === null ? null : <p className="quiet-line where">{where}</p>}
      <div className="quiet-meta">
        {/* the head's own dot, in the hue the card's shadow wears: that is the key to it */}
        {head === null ? null : <ModelMark colour={colour}>{head}</ModelMark>}
        {meta.map((part) => (
          <span key={part}>{part}</span>
        ))}
      </div>
      {needs || (state === 'gone' && canResumeSession(row)) ? (
        <div className="acts">
          {needs ? <OpenLink row={row} /> : null}
          <ResumeCopy row={row} />
        </div>
      ) : null}
    </Window>
  );
}
