import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router';
import { failureText } from '../../api/client';
import { useHeads, useSessions, useTeams } from '../../api/queries';
import { useSessionEdges, useTranscript, useTurnOf } from '../../api/sessions';
import { clockTime } from '../../lib/format';
import { foldTranscript, interleave } from '../../lib/conversation';
import { colourOfHead } from '../../lib/model';
import type { ModelColour } from '../../lib/model';
import { peerRow, railOf } from '../../lib/rail';
import type { Seat } from '../../lib/rail';
import { peerLabel, sessionKey, sessionLabel, spanText, stateOf, stateTone, stateWord, repoName, timingOf } from '../../lib/sessions';
import type { SessionRow } from '../../types/sessions';
import { UNKNOWN_HEAD } from '../../types/sessions';
import { Back, Button, Empty, Fault, Markdown, Segmented, State, Window } from '../../ui';
import { ResumeCopy, StopTurn, sessionPath } from '../shared/SessionActions';
import { Composer } from './Composer';
import { P } from './copy';
import { Handoff } from './Handoff';
import { Rail } from './Rail';
import { ToolBlock } from './ToolBlock';
import './session.css';

type Filter = 'messages' | 'tools' | 'handoffs';
const FILTERS = [['messages', P.messages], ['tools', P.toolsOnly], ['handoffs', P.handoffsOnly]] as const;

const hue = (colour: ModelColour): React.CSSProperties => ({ '--c': colour === 'none' ? 'var(--tan)' : `var(--${colour})` }) as React.CSSProperties;

export function SessionPage() {
  const { id = '' } = useParams();
  const sessions = useSessions();
  const heads = useHeads();
  const teams = useTeams();
  const edges = useSessionEdges(id);
  const transcript = useTranscript(id);
  const [filter, setFilter] = useState<Filter>('messages');
  const now = Date.now();

  const rows = sessions.data?.sessions ?? [];
  const row: SessionRow | undefined = rows.find((candidate) => sessionKey(candidate) === id);
  const colourOfKey = (headKey: string | null): ModelColour => {
    if (headKey === null || headKey === UNKNOWN_HEAD) return 'none';
    const head = heads.data?.heads.find((candidate) => candidate.key === headKey);
    return head === undefined ? 'none' : colourOfHead(head.authKind);
  };
  const colour = colourOfKey(row?.head ?? null);
  const headLabel = row === undefined || row.head === UNKNOWN_HEAD ? null : (heads.data?.heads.find((head) => head.key === row.head)?.label ?? row.head);

  const handed = edges.data?.edges ?? [];
  const timeline = useMemo(() => {
    const view = transcript.view;
    const items = view?.kind === 'messages' ? foldTranscript(view.messages) : [];
    return interleave(items, handed);
  }, [transcript.view, handed]);

  const turnOf = useTurnOf(row === undefined || row.head === UNKNOWN_HEAD ? [] : [row.head]);
  const state = row === undefined ? null : stateOf(row, turnOf(row));
  const timing = row === undefined || state === null ? null : timingOf(row, state, turnOf(row), now);
  const rail = row === undefined ? null : railOf(row, rows, handed, teams.data?.teams ?? [], turnOf);
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
        {row === undefined || state === null ? null : (
          <div className="acts">
            {state === 'working' || state === 'stuck' ? <StopTurn row={row} fallback={null} /> : null}
            <ResumeCopy row={row} />
          </div>
        )}
      </header>
      <div className="cols">
        <Window as="section" colour={colour} className="sheet" aria-label={P.conversation}>
          <div className="bar">
            <h3>{P.conversation}</h3>
            <Segmented label={P.filterLabel} value={filter} options={FILTERS} onChange={setFilter} />
          </div>
          <div className="convo">
            {transcript.isPending ? <p className="hint">{P.loading}</p> : null}
            {transcript.isError ? <Fault message={failureText(transcript.error)} onRetry={() => void transcript.refetch()} /> : null}
            {transcript.view?.kind === 'off' ? <p className="hint">{P.transcriptOff} {transcript.view.reason}</p> : null}
            {transcript.view?.kind === 'missing' ? (
              <p className="hint">
                {row === undefined && sessions.isSuccess ? P.notFound : P.transcriptMissing} {transcript.view.searched.join(', ')}
              </p>
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
              if (item.kind === 'note') {
                previous = null;
                return (
                  <details key={item.index} className="note">
                    <summary>{item.label}{item.text === '' ? '' : `: ${item.text.split('\n', 1)[0] ?? ''}`}</summary>
                    {item.text.includes('\n') ? <pre>{item.text}</pre> : null}
                  </details>
                );
              }
              const same = previous === item.who;
              previous = item.who;
              return (
                <div key={item.index} className={`msg${item.who === 'user' ? ' you' : ''}`}>
                  {same ? null : (
                    <div className="who">
                      {item.who === 'user' ? <span>{P.you}</span> : <span className="m" style={hue(colour)}>{headLabel ?? P.assistant}</span>}
                      {item.ts === null ? null : <span className="t">{clockTime(item.ts)}</span>}
                    </div>
                  )}
                  <Markdown>{item.text}</Markdown>
                </div>
              );
            })}
            {transcript.hasNextPage ? (
              <div className="more-row">
                <Button disabled={transcript.isFetchingNextPage} onClick={() => void transcript.fetchNextPage()}>{P.loadMore}</Button>
              </div>
            ) : null}
          </div>
          <Composer />
        </Window>
        {rail === null ? <div /> : <Rail rail={rail} colourOf={seatColour} pathOf={seatPath} />}
      </div>
    </>
  );
}
