import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useSessions, useStatus, useTeams } from '../../api/queries';
import { useSessionEdges, useSessionHistory, useTranscript, useTurnOf } from '../../api/sessions';
import { clockTime } from '../../lib/format';
import { foldTranscript, interleave, withoutEchoed } from '../../lib/conversation';
import { colourFromRegistry } from '../../lib/model';
import type { ModelColour } from '../../lib/model';
import { peerRow, railOf } from '../../lib/rail';
import type { Seat } from '../../lib/rail';
import { canResumeSession, namedPeer, peerLabel, sessionKey, sessionLabel, spanText, stateOf, stateTone, stateWord, repoName, timingOf } from '../../lib/sessions';
import type { SessionRow } from '../../types/sessions';
import { UNKNOWN_HEAD } from '../../types/sessions';
import { Back, Button, Empty, Fault, Markdown, Segmented, State, Window } from '../../ui';
import { ResumeCopy, StopTurn, sessionPath } from '../shared/SessionActions';
import { P } from './copy';
import { Handoff, PeerSaid } from './Handoff';
import { Rail } from './Rail';
import { SessionNote } from './SessionNote';
import { ToolBlock } from './ToolBlock';
import './session.css';

type Filter = 'messages' | 'tools' | 'handoffs';
const FILTERS = [['messages', P.messages], ['tools', P.toolsOnly], ['handoffs', P.handoffsOnly]] as const;

const hue = (colour: ModelColour): React.CSSProperties => ({ '--c': colour === 'none' ? 'var(--tan)' : `var(--${colour})` }) as React.CSSProperties;

export function SessionPage() {
  const { id = '' } = useParams();
  const sessions = useSessions();
  const heads = useHeads();
  const status = useStatus();
  const teams = useTeams();
  const edges = useSessionEdges(id);
  const transcript = useTranscript(id);
  const [filter, setFilter] = useState<Filter>('messages');
  const now = Date.now();

  const rows = sessions.data?.sessions ?? [];
  const liveRow = rows.find((candidate) => sessionKey(candidate) === id);
  // A session that has ended is not in the registry: the history knows it, and matches it by its id.
  const history = useSessionHistory(id, sessions.isSuccess && liveRow === undefined && id !== '');
  const pastRow = (history.data?.pages ?? []).flatMap((page) => ('sessions' in page ? page.sessions : [])).find((candidate) => sessionKey(candidate) === id);
  const row: SessionRow | undefined = liveRow ?? pastRow;
  const registryColour = colourFromRegistry(status.data);
  const colourOfKey = (headKey: string | null): ModelColour =>
    headKey === null || headKey === UNKNOWN_HEAD ? 'none' : registryColour(headKey);
  const colour = colourOfKey(row?.head ?? null);
  const headLabel = row === undefined || row.head === UNKNOWN_HEAD ? null : (heads.data?.heads.find((head) => head.key === row.head)?.label ?? row.head);

  const handed = edges.data?.edges ?? [];
  const timeline = useMemo(() => {
    const view = transcript.view;
    const items = view?.kind === 'messages' ? foldTranscript(view.messages) : [];
    return interleave(items, withoutEchoed(handed, items, (edge) => namedPeer(rows, edge)));
  }, [transcript.view, handed, rows]);

  const turnOf = useTurnOf(row === undefined || row.head === UNKNOWN_HEAD ? [] : [row.head]);
  const state = row === undefined ? null : stateOf(row);
  const timing = row === undefined || state === null ? null : timingOf(row, state, turnOf(row), now);
  const rail = row === undefined ? null : railOf(row, rows, handed, teams.data?.teams ?? []);
  const seatColour = (seat: Seat): ModelColour => colourOfKey(seat.head);
  const seatPath = (seat: Seat): string | null => {
    const found = rows.find((candidate) => sessionKey(candidate) === seat.key);
    return found === undefined ? null : sessionPath(found);
  };

  const title = row === undefined ? id.slice(0, 8) : sessionLabel(row) || P.untitled;
  const facts = row === undefined || state === null ? [] : [
    repoName(row),
    headLabel,
    timing?.since == null ? null : `${P.elapsed} ${spanText(timing.since)}`,
  ].filter((part): part is string => part !== null);

  const shown = timeline.filter((entry) => {
    if (filter === 'messages') return true;
    if (filter === 'tools') return entry.kind === 'item' && entry.item.kind === 'tool';
    return entry.kind === 'handoff';
  });

  // The page opens at the newest message. It stays at the bottom while the conversation fills in, until the operator scrolls up;
  // an earlier page loaded at the top keeps what they were reading where it was.
  const nearBottom = useRef(true);
  const anchor = useRef<number | null>(null);
  useEffect(() => {
    const onScroll = () => { nearBottom.current = document.documentElement.scrollHeight - (window.scrollY + window.innerHeight) < 240; };
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);
  useLayoutEffect(() => {
    if (anchor.current !== null) {
      window.scrollBy(0, document.documentElement.scrollHeight - anchor.current);
      anchor.current = null;
      // The reader is at the earlier page now; the growth that page caused is not news to follow.
      nearBottom.current = false;
    } else if (nearBottom.current) window.scrollTo(0, document.documentElement.scrollHeight);
  }, [shown.length]);
  // What lands after the messages (the team rail, the note box, a hint under the conversation) grows the page with no new
  // message; on a cold daemon it lands 20 ms after them. A reader still at the bottom stays there.
  useEffect(() => {
    const follow = new ResizeObserver(() => {
      if (nearBottom.current) window.scrollTo(0, document.documentElement.scrollHeight);
    });
    follow.observe(document.body);
    return () => follow.disconnect();
  }, []);

  let previous: string | null = null;
  return (
    <>
      <Link className="crumb" to="/sessions">
        <Back />
        {P.sessions}
      </Link>
      <header className="top">
        <div>
          <h1>{title}</h1>
          {state === null ? null : (
            <div className="facts quiet-meta">
              <State tone={stateTone(state)}>{stateWord(state)}</State>
              {facts.map((fact) => (
                <span key={fact}>{fact}</span>
              ))}
            </div>
          )}
        </div>
        {row === undefined || state === null || (!canResumeSession(row) && !(state === 'working' && turnOf(row) != null)) ? null : (
          <div className="acts">
            {state === 'working' ? <StopTurn row={row} fallback={null} /> : null}
            <ResumeCopy row={row} />
          </div>
        )}
      </header>
      <div className="cols session-columns">
        <Window as="section" colour={colour} className="sheet" aria-label={P.conversation}>
          <div className="bar">
            <h3>{P.conversation}</h3>
            <Segmented label={P.filterLabel} value={filter} options={FILTERS} onChange={setFilter} />
          </div>
          <div className="convo">
            {transcript.hasNextPage ? (
              <div className="more-row">
                <Button
                  disabled={transcript.isFetchingNextPage}
                  onClick={() => { anchor.current = document.documentElement.scrollHeight; void transcript.fetchNextPage(); }}
                >
                  {P.loadEarlier}
                </Button>
              </div>
            ) : null}
            {transcript.isPending ? <p className="hint">{P.loading}</p> : null}
            {transcript.isError ? <Fault message={failureText(transcript.error)} onRetry={() => void transcript.refetch()} /> : null}
            {transcript.view?.kind === 'off' ? <p className="hint">{P.transcriptOff} {transcript.view.reason}</p> : null}
            {transcript.view?.kind === 'missing' ? (
              <>
                <p className="hint">
                  {row === undefined && sessions.isSuccess && !history.isFetching ? P.notFound : P.transcriptMissing}
                </p>
                {transcript.view.searched.length === 0 ? null : (
                  <details>
                    <summary>{P.showPath}</summary>
                    <p className="hint">{P.lookedIn}</p>
                    <pre>{transcript.view.searched.join('\n')}</pre>
                  </details>
                )}
              </>
            ) : null}
            {transcript.view?.kind === 'messages' && shown.length === 0 && !transcript.hasNextPage ? <Empty title={P.noMessages} /> : null}
            {shown.map((entry) => {
              if (entry.kind === 'handoff') {
                previous = null;
                const peer = peerRow(rows, entry.edge);
                return <Handoff key={`h${entry.edge.at}-${entry.edge.direction}`} edge={entry.edge} peer={peerLabel(rows, entry.edge)} colour={colourOfKey(peer?.head ?? null)} />;
              }
              const { item } = entry;
              if (item.kind === 'tool') {
                previous = null;
                return <div key={item.index} className="msg"><ToolBlock item={item} /></div>;
              }
              if (item.kind === 'peer') {
                previous = null;
                const sender = rows.find((candidate) => sessionLabel(candidate) === item.from);
                return <PeerSaid key={item.index} from={item.from} at={item.ts} text={item.text} colour={colourOfKey(sender?.head ?? null)} />;
              }
              if (item.kind === 'note') {
                previous = null;
                return (
                  <details key={item.index} className="note">
                    <summary>{item.line}</summary>
                    {item.detail !== null ? <pre>{item.detail}</pre> : item.text.includes('\n') ? <pre>{item.text}</pre> : null}
                  </details>
                );
              }
              const same = previous === item.who;
              previous = item.who;
              return (
                <div key={item.index} className={`msg${item.who === 'user' ? ' you' : ''}`}>
                  {same ? null : (
                    <div className="who">
                      {item.who === 'user' ? <span>{P.you}</span> : item.who === 'system' ? <span>{P.system}</span> : <span className="m" style={hue(colour)}>{headLabel ?? P.assistant}</span>}
                      {item.ts === null ? null : <span className="t">{clockTime(item.ts)}</span>}
                    </div>
                  )}
                  <Markdown>{item.text}</Markdown>
                </div>
              );
            })}
            {row === undefined ? null : <SessionNote key={id} row={row} versions={sessions.data?.note_versions} />}
          </div>
        </Window>
        {rail === null ? <div /> : <Rail rail={rail} colourOf={seatColour} pathOf={seatPath} />}
      </div>
    </>
  );
}
