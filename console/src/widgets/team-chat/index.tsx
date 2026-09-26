// The team's chat: every hand-off between its sessions today, oldest first, as GET
// /api/teams/{id}/chat serves it (FEATURES 4.13, served since V4-131).
//
// THE TEXT IS BEHIND A REVEAL. A hand-off's text is read from the sender's transcript on demand
// and never stored by the daemon, so the list prints who wrote to whom and when, and shows the
// words only when asked.
import { ArrowRightIcon } from '@phosphor-icons/react/dist/csr/ArrowRight';
import { Empty, Reveal, Section } from '@shared/ui';
import type { PendingRoute } from '@shared/api';
import type { TeamMessage } from '@entities/team';
import { H, S, U } from './strings';
import './team-chat.css';

export interface TeamChatPayload {
  messages: TeamMessage[];
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

function body(state: TeamChatState) {
  if (state === null) return <Empty text={S.reading} />;
  if ('pending' in state) return <Empty text={S.unavailable} source={H.unavailable} />;
  if ('error' in state) return <Empty text={S.unreadable} source={state.error} />;
  if (state.messages.length === 0) return <Empty text={S.noMessages} source={H.noMessages} />;
  return (
    <ol className="myx-chat">
      {chatOrder(state.messages).map((message) => (
        <li key={`${message.time}-${message.from}-${message.to}`} className="myx-chat-row" aria-label={`${message.from} ${U.to} ${message.to}`}>
          <span className="myx-chat-time">{message.time}</span>
          <span className="myx-chat-route">
            <span className="myx-chat-party">{message.from}</span>
            <ArrowRightIcon className="myx-chat-arrow" aria-hidden="true" />
            <span className="myx-chat-party">{message.to}</span>
          </span>
          <span className="myx-chat-text">
            <Reveal label={S.reveal}><p className="myx-chat-words">{message.text}</p></Reveal>
          </span>
        </li>
      ))}
    </ol>
  );
}

export function TeamChat({ state }: { state: TeamChatState }) {
  const count = state !== null && 'messages' in state ? state.messages.length : undefined;
  return (
    <Section title={S.chat} {...(count === undefined ? {} : { count })} info={{ text: H.chat, label: S.chatWhy }}>
      {body(state)}
    </Section>
  );
}
