// The team's group chat: every hand-off between its sessions, oldest first, as GET
// /api/teams/{id}/chat serves it (FEATURES 4.13, served since V4-131).
//
// THE TEXT IS BEHIND A REVEAL. A hand-off's text is read from the sender's transcript on demand
// and never stored by the daemon, so the panel prints who wrote to whom and when, and shows the
// words only when asked. The rows are therefore NOT strips: a strip is a focusable button, and a
// reveal inside one would be a button inside a button.
import { HolderEdge, Empty, Reveal } from '@shared/ui';
import type { PendingRoute } from '@shared/api';
import type { TeamMessage } from '@entities/team';
import { S } from './strings';
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

export function TeamChat({ state }: { state: TeamChatState }) {
  let body;
  if (state === null) body = <Empty text="reading the chat" source="GET /api/teams/{id}/chat" />;
  else if ('pending' in state) body = <Empty text="no chat route" source={`${state.pending} pending`} />;
  else if ('error' in state) body = <Empty text="chat unreadable" source={state.error} />;
  else if (state.messages.length === 0) body = <Empty text="no messages yet" source="GET /api/teams/{id}/chat" />;
  else {
    body = (
      <ol className="myx-chat-rows">
        {chatOrder(state.messages).map((message) => (
          <li key={`${message.time}-${message.from}-${message.to}`} className="myx-chat-row">
            <HolderEdge state={message.fromHead === 'claude' ? 'green' : 'grey'} label="" />
            <dl className="myx-chat-cells">
              <div className="myx-chat-cell"><dt>{S.time}</dt><dd className="myx-chat-fig">{message.time}</dd></div>
              <div className="myx-chat-cell"><dt>{S.from}</dt><dd>{message.from}</dd></div>
              <div className="myx-chat-cell"><dt>{S.to}</dt><dd>{message.to}</dd></div>
              <div className="myx-chat-cell myx-chat-text">
                <dt>{S.message}</dt>
                <dd><Reveal label={S.reveal}>{message.text}</Reveal></dd>
              </div>
            </dl>
          </li>
        ))}
      </ol>
    );
  }
  return (
    <section className="myx-chat" aria-label={S.chat}>
      <h3 className="myx-chat-title">{S.chat}</h3>
      {body}
    </section>
  );
}
