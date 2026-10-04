import { Link } from 'react-router';
import { failureText } from '../../api/client';
import { useCapture, useConversation, useKeptTurn, useWire } from '../../api/turns';
import { foldTranscript } from '../../lib/conversation';
import { fmtMs } from '../../lib/format';
import { readable } from '../../lib/message';
import { askAndAnswer, isClean, isStopped, outcomeOf, spokenFailure, wireFor } from '../../lib/turns-page';
import { P } from '../../lib/words-turns';
import type { TraceRecord, TraceSide, TurnRow } from '../../types/perf';
import { Markdown } from '../../ui';
import { ToolBlock } from '../session/ToolBlock';
import '../session/session.css';
import { CaptureControl } from './CaptureControl';

const TABS = [['conversation', P.tabConversation], ['request', P.tabRequest], ['sent', P.tabSent]] as const;
type Tab = (typeof TABS)[number][0];
const tabOf = (raw: string | null): Tab => TABS.find(([id]) => id === raw)?.[0] ?? 'conversation';

function Conversation({ row, plan }: { row: TurnRow; plan: string }) {
  const read = useConversation(row.head, row.session_id ?? null, row.response_message_id ?? null, row.session_id !== undefined && row.response_message_id !== undefined);
  if (row.session_id === undefined) return <p className="kept-note">{P.conversationNoId}</p>;
  if (row.response_message_id === undefined) return <p className="kept-note">{P.conversationNoReply}</p>;
  if (read.isError) return <p className="kept-note" role="alert">{failureText(read.error)}</p>;
  if (read.data === undefined) return <p className="kept-note" role="status">{P.readingConversation}</p>;
  if (read.data.state !== 'found') return <p className="kept-note">{read.data.reason}</p>;
  const { ask, reply, earlier } = askAndAnswer(read.data.messages);
  const asked = ask === null ? null : readable(ask.text);
  return (
    <div className="qa">
      {asked === null ? null : <div><div className="who">{P.asked}</div><div className="you">{asked}</div></div>}
      {foldTranscript(reply).map((item) => {
        if (item.kind === 'tool') return <div key={item.index} className="msg"><ToolBlock item={item} /></div>;
        if (item.kind === 'note') return <p key={item.index} className="note">{item.line}</p>;
        if (item.kind === 'peer') return <div key={item.index}><div className="who">{item.from}</div><Markdown>{item.text}</Markdown></div>;
        return (
          <div key={item.index}>
            <div className="who">{item.who === 'system' ? P.system : P.answered(plan)}</div>
            <Markdown>{item.text}</Markdown>
          </div>
        );
      })}
      {earlier > 0 ? <p className="note">{P.conversationEarlier(earlier)}</p> : null}
    </div>
  );
}

function Side({ label, side }: { label: string; side: TraceSide | undefined }) {
  const text = side?.body ?? side?.text;
  if (side === undefined || text === undefined) return null;
  const reason = typeof text !== 'string' && typeof text?.reason === 'string' ? text.reason : undefined;
  return (
    <details>
      <summary>{label}{side.status === undefined ? '' : ` · ${side.status}`}</summary>
      {typeof text === 'string' ? <pre>{text}</pre> : <p className="kept-note" role="status">{P.bodyUnavailable}{reason === undefined ? '' : ` ${reason}`}</p>}
      {side.truncated === true ? <p className="sub">{P.truncated}</p> : null}
    </details>
  );
}

function Attempt({ record }: { record: TraceRecord }) {
  return (
    <li>
      <h3>{P.attempt(record.attempt ?? 1)}</h3>
      <p className="sub">{[record.transport, record.durationMs === undefined ? null : fmtMs(record.durationMs), record.failure].filter((part) => part !== undefined && part !== null).join(' · ')}</p>
      <Side label={P.sentRequest} side={record.request} />
      <Side label={P.received} side={record.response} />
    </li>
  );
}

/** The sentence the daemon recorded for a failed turn, whole, under this turn's own read: a late answer for another turn lands in that turn's cache. */
function Failure({ row }: { row: TurnRow }) {
  const kept = useKeptTurn(row.head, row.turn ?? null, row.turn !== undefined);
  if (isClean(row.outcome) || row.outcome === '?') return null;
  if (row.turn !== undefined && kept.data === undefined && !kept.isError) return <p className="failure-sentence" role="status">{P.readingFailure}</p>;
  const sentence = kept.data === undefined || 'gone' in kept.data ? null : (kept.data.read.turn.failure_sentence ?? null);
  const absent = isStopped(row.outcome) ? P.stoppedNoReason : P.failureNoReason(outcomeOf(row.outcome).word);
  const spoken = sentence === null ? null : spokenFailure(sentence);
  const reason = row.outcome === 'error:rate-limited' ? spoken?.replace(/^\s*[a-z]/, initial => initial.toUpperCase()) : spoken;
  const problem = kept.isError ? failureText(kept.error) : kept.data !== undefined && 'gone' in kept.data ? kept.data.gone : null;
  return <p className="failure-sentence">{reason?.trim() ? reason : absent}{problem === null ? '' : ` ${problem}`}</p>;
}

function Request({ row, plan }: { row: TurnRow; plan: string }) {
  const capture = useCapture(row.head);
  const kept = useKeptTurn(row.head, row.turn ?? null, row.turn !== undefined);
  if (row.turn === undefined) return <p className="kept-note">{capture.data?.enabled === false ? P.captureOff(plan) : P.keptGone}</p>;
  if (kept.isError) return <p className="kept-note" role="alert">{failureText(kept.error)}</p>;
  if (kept.data === undefined) return <p className="kept-note" role="status">{P.readingRequest}</p>;
  if ('gone' in kept.data) return <p className="kept-note">{kept.data.gone}</p>;
  const attempts = kept.data.read.records.filter((record) => record.kind === 'attempt');
  return (
    <ul className="attempts">
      {attempts.map((record) => <Attempt key={record.attempt ?? record.ts} record={record} />)}
      {kept.data.read.records.filter((record) => record.kind === 'turn').map((record) => (
        <li key={`turn-${record.ts}`}>
          <Side label={P.received} side={record.answer} />
        </li>
      ))}
    </ul>
  );
}

function Sent({ row }: { row: TurnRow }) {
  const wire = useWire(row.head);
  if (wire.isError) return <p className="kept-note" role="alert">{failureText(wire.error)}</p>;
  if (wire.data === undefined) return <p className="kept-note" role="status">{P.readingSent}</p>;
  if ('off' in wire.data) return <p className="kept-note">{wire.data.off}</p>;
  const records = wireFor(wire.data.tap.records, row);
  if (records.length === 0) return <p className="kept-note">{P.wireNone}</p>;
  return (
    <ul className="attempts">
      {records.map((record) => (
        <li key={record.ts}>
          <details open>
            <summary>{P.wireBody}</summary>
            <pre>{record.body}</pre>
          </details>
        </li>
      ))}
    </ul>
  );
}

/** What splice kept of a turn, one place per kind: the conversation, the request and answer, the bodies sent to the plan. */
export function KeptTabs({ row, plan, tab }: { row: TurnRow; plan: string; tab: string | null }) {
  const current = tabOf(tab);
  return (
    <section className="kept" aria-label={P.tabsLabel}>
      <Failure row={row} />
      <CaptureControl head={row.head} plan={plan} />
      <nav className="tabs" aria-label={P.tabsLabel}>
        {TABS.map(([id, label]) => (
          <Link key={id} to={`?tab=${id}`} replace aria-current={id === current ? 'page' : undefined}>{label}</Link>
        ))}
      </nav>
      {current === 'conversation' ? <Conversation row={row} plan={plan} /> : current === 'request' ? <Request row={row} plan={plan} /> : <Sent row={row} />}
    </section>
  );
}
