// The team's chat: every hand-off between its sessions today, oldest first, as GET
// /api/teams/{id}/chat serves it (FEATURES 4.13, served since V4-131).
//
// THE TEXT IS BEHIND A REVEAL. A hand-off's text is read from the sender's transcript on demand
// and never stored by the daemon, so the list prints who wrote to whom and when, and shows the
// words only when asked.
import { ArrowRightIcon } from '@phosphor-icons/react/dist/csr/ArrowRight';
import { Key } from '@shared/controls';
import { Empty, Reveal, Section } from '@shared/ui';
import type { PendingRoute } from '@shared/api';
import type { TeamMessage } from '@entities/team';
import { H, S, U } from './strings';
import './team-chat.css';

export interface TeamChatPayload {
  messages: TeamMessage[];
  state?: 'on' | 'off' | 'deleted' | 'not_kept' | 'partially_kept';
  reason?: string;
  oldest_kept_epoch_millis?: number;
}

/** What the panel was handed: the chat, the honest empty naming its row, the daemon's refusal, or
 *  nothing yet. */
export type TeamChatState = TeamChatPayload | PendingRoute | { error: string } | null;

const stampOf = (time: string): number => {
  const [h = '0', m = '0', s = '0'] = time.split(':');
  return Number(h) * 3600 + Number(m) * 60 + Number(s);
};

/** Oldest first, whatever order the route answered in: a chat reads down. Stable, so two messages
 *  in one minute keep the order the daemon gave them. */
export function chatOrder(messages: readonly TeamMessage[]): TeamMessage[] {
  return messages
    .map((message, at) => ({ message, at }))
    .sort((a, b) => stampOf(a.message.time) - stampOf(b.message.time) || a.at - b.at)
    .map(({ message }) => message);
}

function body(state: TeamChatState, today: boolean) {
  if (state === null) return <Empty text={S.reading} />;
  if ('pending' in state) return <Empty text={S.unavailable} source={H.unavailable} />;
  if ('error' in state) return <Empty text={S.unreadable} source={state.error} />;
  if (state.state === 'deleted') return <Empty text={S.deleted} source={H.deleted} />;
  if (state.state === 'not_kept') return <Empty text={S.notKept} source={state.reason} />;
  if (state.state === 'off' && state.messages.length === 0) return <Empty text={S.off} source={state.reason} />;
  if (state.state === 'partially_kept' && state.messages.length === 0) return <Empty text={S.partlyKept} source={state.reason} />;
  if (state.messages.length === 0) return <Empty text={today ? S.noMessages : S.noMessagesOnDay} source={H.noMessages} />;
  return (
    <>
    <ol className="myx-chat">
      {chatOrder(state.messages).map((message, index) => (
        <li key={`${message.at}-${message.from}-${message.to}-${index}`} className="myx-chat-row" aria-label={`${message.fromRole ?? message.from} ${U.to} ${message.toRole ?? message.to}`}>
          <span className="myx-chat-time">{message.time}</span>
          <span className="myx-chat-route">
            <span className="myx-chat-party">{message.fromRole ?? message.from}</span>
            <ArrowRightIcon className="myx-chat-arrow" aria-hidden="true" />
            <span className="myx-chat-party">{message.toRole ?? message.to}</span>
          </span>
          <span className="myx-chat-text">
            <Reveal label={S.reveal}><p className="myx-chat-words">{message.text}</p></Reveal>
          </span>
        </li>
      ))}
    </ol>
    {state.state === 'partially_kept' ? <Empty text={S.partlyKept} source={state.reason} /> : null}
    </>
  );
}

/** Move by calendar days, never by 24 hours: daylight-saving days are not fixed spans. */
function nextDay(day: number, by: number): number {
  const at = new Date(day);
  return new Date(at.getFullYear(), at.getMonth(), at.getDate() + by).getTime();
}

export function TeamChat({ state, day, today, onDayChange }: {
  state: TeamChatState;
  /** Local midnight of the selected and current days; absent on a static board. */
  day?: number;
  today?: number;
  onDayChange?: (day: number) => void;
}) {
  const current = day === undefined || today === undefined || day === today;
  const count = state !== null && 'messages' in state ? state.messages.length : undefined;
  const oldest = state !== null && 'messages' in state ? state.oldest_kept_epoch_millis : undefined;
  return (
    <Section title={S.chat} {...(count === undefined ? {} : { count })}
      info={{ text: current ? H.chat : H.older, label: S.chatWhy }}
      actions={day === undefined || today === undefined || onDayChange === undefined ? undefined : (
        <span className="myx-chat-days">
          <Key disabled={oldest !== undefined && day <= oldest} onClick={() => onDayChange(nextDay(day, -1))}>{S.previousDay}</Key>
          <span className="myx-chat-day">{current ? S.today : new Date(day).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}</span>
          <Key disabled={current} onClick={() => onDayChange(nextDay(day, 1))}>{S.nextDay}</Key>
          {current ? null : <Key onClick={() => onDayChange(today)}>{S.today}</Key>}
        </span>
      )}>
      {body(state, current)}
    </Section>
  );
}
