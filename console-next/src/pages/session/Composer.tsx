import { NOTE_LIMIT, noteReady } from '../../lib/note';
import { Button, Send } from '../../ui';
import { P } from './copy';

export type NotePhase = 'idle' | 'sending' | 'sent' | 'failed';

export type ComposerProps = {
  draft: string;
  phase: NotePhase;
  /** The daemon's sentence for a refused or failed note; shown while [phase] is `failed`. */
  failure: string | null;
  onDraft: (draft: string) => void;
  onSend: () => void;
};

/** A note to the session, below its conversation. Presentational: the page owns the draft and the send. What it says about the
 *  note is what the daemon can vouch for: sent, never read. */
export function Composer({ draft, phase, failure, onDraft, onSend }: ComposerProps) {
  const sending = phase === 'sending';
  const tooLong = draft.length > NOTE_LIMIT;
  return (
    <form className="composer" onSubmit={(event) => { event.preventDefault(); if (noteReady(draft) && !sending) onSend(); }}>
      <textarea className="in" rows={2} value={draft} placeholder={P.noteHint} aria-label={P.noteLabel} onChange={(event) => onDraft(event.target.value)} />
      <p className="what">{P.noteWhat}</p>
      <div className="row">
        <span className={`status${phase === 'failed' || tooLong ? ' bad' : ''}`} role="status">
          {tooLong ? P.noteTooLong(NOTE_LIMIT) : phase === 'sent' ? P.noteSent : phase === 'failed' ? failure : null}
        </span>
        <Button type="submit" small kind="go" disabled={!noteReady(draft) || sending}>
          {sending ? P.noteSending : P.noteSend}
          <Send />
        </Button>
      </div>
    </form>
  );
}

/** What the page shows where a session the daemon will not send a note to would have the box: the reason and the way out, in the
 *  daemon's own terms (lib/note.ts noteRefusal). */
export function NoteRefused({ text }: { text: string }) {
  return <p className="hint">{text}</p>;
}

/** What the page shows where a session that is not running would have the box. */
export function NoteClosed() {
  return <p className="hint">{P.noteNotRunning}</p>;
}
