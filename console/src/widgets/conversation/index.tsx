// A session's conversation: one line per turn (who spoke, when, which tool, how long), with the
// turn's body behind a Reveal. Read as a conversation between participants, never as a log tail
// (FEATURES.md 4.4).
//
// The body is behind a reveal for two reasons that agree: the voice ruling allows no paragraph on a
// page, and a transcript is thousands of lines the operator did not ask for until they open one.
// Nothing is prefetched: this reads a page when it is asked to and reads the next only when the
// reader asks again.
import { useEffect } from 'react';
import { loadMoreTranscript, loadTranscript, useTranscript } from '@entities/transcript';
import type { TranscriptRole, TranscriptSlice } from '@entities/transcript';
import { Fault, Key } from '@shared/controls';
import { Badge, Empty, KeyValue, Reveal } from '@shared/ui';
import { H, S, U } from './strings';
import './conversation.css';

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM of a turn. The console's clocks are 24 hour everywhere (the rule). */
function atClock(epochMs: number): string {
  const at = new Date(epochMs);
  return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

const ROLE: Record<TranscriptRole, string> = { user: S.user, assistant: S.assistant, system: S.system, tool: S.tool };

/**
 * `slice` is the fixture and test seam (CONTRACTS.md section 4): a capture or a test hands the
 * loaded transcript straight in, so nothing has to reach the store. When it is provided this reads
 * nothing.
 */
export function Conversation({ sessionId, slice }: { sessionId: string; slice?: TranscriptSlice }) {
  const state = useTranscript((s) => s);

  useEffect(() => {
    if (slice === undefined) void loadTranscript(sessionId);
  }, [sessionId, slice]);

  if (slice === undefined && state.error !== null) return <Fault message={state.error} />;
  const data = slice ?? state.data;
  if (data === null) return null;
  // No file on disk is the daemon's answer, with where it looked: not a fault and not a missing route.
  if ('missing' in data) {
    return <Empty text={S.noTranscript} source={data.missing.length === 0 ? H.notWritten : `${H.lookedIn} ${data.missing.join(', ')}`} />;
  }
  if ('pending' in data) return <Empty text={S.unavailable} source={H.pending} />;

  return (
    <div className="myx-cv">
      <KeyValue rows={[[S.source, <span className="myx-cv-path">{data.path}</span>], [S.pages, String(data.cursor.pages)]]} />

      <ol className="myx-cv-turns" aria-label={S.turns}>
        {data.messages.map((message) => (
          <li className="myx-cv-turn" key={message.index} aria-label={`${ROLE[message.role]} ${U.turn} ${message.index}`}>
            <div className="myx-cv-line">
              <Badge tone="neutral" quiet>{ROLE[message.role]}</Badge>
              <span className="myx-cv-index">#{message.index}</span>
              {message.ts === undefined ? null : <span className="myx-cv-at">{atClock(message.ts)}</span>}
              {message.tool === undefined ? null : <span className="myx-cv-tool">{message.tool}</span>}
              {message.result === true ? <Badge tone="neutral" quiet>{S.result}</Badge> : null}
              <span className="myx-cv-size">{message.text.length.toLocaleString('en-US')} {U.chars}</span>
            </div>
            <Reveal label={S.body}>
              <pre className="myx-cv-text">{message.text}</pre>
            </Reveal>
          </li>
        ))}
      </ol>

      {data.cursor.complete ? null : <Key onClick={() => void loadMoreTranscript()}>{S.loadMore}</Key>}
    </div>
  );
}
