// A session's conversation: one strip per turn, with the turn's body behind a
// Reveal. Read as a conversation between participants, never as a log tail
// (FEATURES.md 4.4).
//
// The body is behind a reveal for two reasons that agree: the world's copy rule
// allows no paragraph on a page except an honest empty, and a transcript is
// thousands of lines the operator did not ask for until they open one. Nothing
// is prefetched: this reads a page when it is asked to and reads the next only
// when the reader asks again.
import { useEffect } from 'react';
import { loadMoreTranscript, loadTranscript, useTranscript } from '@entities/transcript';
import type { TranscriptSlice } from '@entities/transcript';
import { Empty, ErrorNote, Reveal, Strip, StripField } from '@shared/ui';
import { S } from './strings';
import './conversation.css';

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM of a turn. The console's clocks are 24 hour everywhere (the rule). */
function atClock(epochMs: number): string {
  const at = new Date(epochMs);
  return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

/**
 * `slice` is the fixture and test seam (CONTRACTS.md section 4): a capture or a
 * test hands the loaded transcript straight in, so nothing has to reach the
 * store. When it is provided this reads nothing.
 */
export function Conversation({ sessionId, slice }: { sessionId: string; slice?: TranscriptSlice }) {
  const state = useTranscript((s) => s);

  useEffect(() => {
    if (slice === undefined) void loadTranscript(sessionId);
  }, [sessionId, slice]);

  if (slice === undefined && state.error !== null) return <ErrorNote message={state.error} />;
  const data = slice ?? state.data;
  if (data === null) return null;
  if ('pending' in data) {
    return <Empty text="transcript route not built yet" source="row V4-130" />;
  }

  return (
    <div className="myx-cv">
      <div className="myx-cv-head">
        <StripField w={40} label={S.source} value={data.path} />
        <StripField w={7} label={S.pages} value={data.cursor.pages} />
      </div>

      {data.messages.map((message) => (
        <div className="myx-cv-turn" key={message.index}>
          <Strip edge="grey" edgeLabel={message.role} ariaLabel={`${message.role} turn ${message.index}`}>
            <StripField w={7} label={S.turn} value={message.index} />
            {message.ts === undefined ? null : <StripField w={7} label={S.at} value={atClock(message.ts)} />}
            {message.tool === undefined ? null : <StripField w={14} label={S.tool} value={message.tool} />}
            {message.result === true ? <StripField w={9} label={S.tool} value={S.result} /> : null}
            <StripField w={10} label={S.size} value={message.text.length} />
          </Strip>
          <Reveal label={S.body}>
            <pre className="myx-cv-text">{message.text}</pre>
          </Reveal>
        </div>
      ))}

      {data.cursor.complete ? null : (
        <button type="button" className="myx-cv-more" onClick={() => void loadMoreTranscript()}>
          {S.loadMore}
        </button>
      )}
    </div>
  );
}
