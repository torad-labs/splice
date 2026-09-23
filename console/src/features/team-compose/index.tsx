// The team composer: the form that writes a team — its name, goal, feature list and repo, and the
// role slots with their lead flag, their standing instructions and the session bound to each.
//
// A SAVE IS ONE OR TWO WRITES, and the form prints the daemon's answer to them. A new team is
// PUT /api/teams under an Idempotency-Key; an existing one is PUT /api/teams/{id}, which keeps any
// binding its body leaves null, so a seat the operator opened is then unbound through
// PUT /api/teams/{id}/sessions. The save stays disabled while the draft breaks a rule the form can
// check, and a refusal the daemon gives anyway is printed in its own words.
//
// THE FIELDS ARE FORM FIELDS, NOT STRIPS. A strip is a focusable button (shared/ui/strip.tsx) and
// an input inside one is a nested interactive element, which is the same reason the settings page
// composes field boxes rather than racks.
import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { bindSessions, createTeam, replaceTeam } from '@entities/team';
import type { TeamRow } from '@entities/team';
import { Flag, Input, Key } from '@shared/controls';
import {
  addSlot, bindSession, blankDraft, draftOf, editSlot, featuresOf, keyFor, removeSlot, setArchived, setLead,
  unbindSession, unbindsOf, validateDraft, writeOf,
} from './model';
import type { TeamDraft } from './model';
import { S } from './strings';
import './team-compose.css';

export {
  addSlot, bindSession, blankDraft, blankSlot, draftOf, editSlot, featuresOf, keyFor, removeSlot, setArchived, setLead,
  unbindSession, unbindsOf, validateDraft, writeOf,
} from './model';
export type { DraftSlot, TeamDraft } from './model';

/** A fresh id: for a slot, and for a create's Idempotency-Key. */
const mint = (): string => crypto.randomUUID();

/**
 * Saves the draft: creates it under `key` when `team` is null, else replaces `team` and unbinds
 * every seat the draft opened. Answers the team as the daemon holds it after the last write.
 */
export async function saveDraft(draft: TeamDraft, team: TeamRow | null, key: string): Promise<TeamRow> {
  const body = writeOf(draft);
  if (team === null) return createTeam(body, key);
  const replaced = await replaceTeam(team.id, body);
  const unbinds = unbindsOf(team, draft);
  return Object.keys(unbinds).length === 0 ? replaced : bindSessions(team.id, unbinds);
}

/** A multi-line field in the world's input box. The control set has no text area (a single line is
 *  every other field in the console), so this borrows Input's own label and box classes rather
 *  than inventing a second look for the one field that needs a newline. */
function Lines({ label, value, rows, onChange }: { label: string; value: string; rows: number; onChange: (next: string) => void }): ReactNode {
  return (
    <label className="myx-input myx-compose-lines">
      <span className="myx-input-label">{label}</span>
      <textarea className="myx-input-box" rows={rows} value={value} spellCheck={false} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}

/** The composer, for a new team (`team` absent) or for `team` as the daemon holds it. `onSaved`
 *  hears the team the daemon answered and the line printed for it, so the page can open the team
 *  and hand the line to the composer it opens it in: a create re-keys this form onto the new team,
 *  and the answer would otherwise go with the form it was printed in. */
export function TeamCompose({ team = null, initial, answer: printed = null, onSaved }: {
  team?: TeamRow | null;
  /** A draft to start from instead of `team`'s: the dev fixture's. */
  initial?: TeamDraft;
  /** The daemon's answer to the save that opened this form. */
  answer?: string | null;
  onSaved?: (saved: TeamRow, answer: string) => void;
}) {
  const [draft, setDraft] = useState<TeamDraft>(() => initial ?? (team === null ? blankDraft(mint()) : draftOf(team)));
  const [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<string | null>(printed);
  const attempt = useRef<{ body: string; key: string } | null>(null);
  const problems = validateDraft(draft);

  const save = () => {
    attempt.current = keyFor(attempt.current, JSON.stringify(writeOf(draft)), mint);
    setBusy(true);
    setAnswer(null);
    saveDraft(draft, team, attempt.current.key).then(
      (saved) => {
        const line = `saved ${saved.name} as ${saved.id}`;
        setAnswer(line);
        onSaved?.(saved, line);
      },
      (err: unknown) => setAnswer(err instanceof Error ? err.message : String(err)),
    ).finally(() => setBusy(false));
  };

  const title = team === null ? S.compose : `${S.edit} ${team.name}`;
  return (
    <form className="myx-compose" aria-label={title} onSubmit={(event) => event.preventDefault()}>
      <h3 className="myx-compose-title">{title}</h3>

      <div className="myx-compose-head">
        <Input label={S.name} value={draft.name} w={24} onChange={(name) => setDraft({ ...draft, name })} />
        <Input label={S.repo} value={draft.repo} w={40} onChange={(repo) => setDraft({ ...draft, repo })} />
        <Input label={S.goal} value={draft.goal} w={40} onChange={(goal) => setDraft({ ...draft, goal })} />
        <Lines label={S.features} rows={3} value={draft.features.join('\n')} onChange={(text) => setDraft({ ...draft, features: featuresOf(text) })} />
      </div>

      <fieldset className="myx-compose-slots">
        <legend>{S.slots}</legend>
        {draft.slots.map((slot, index) => (
          <div className="myx-compose-slot" key={slot.id}>
            <Input label={S.role} value={slot.role} w={14} onChange={(role) => setDraft(editSlot(draft, index, { role }))} />
            <Input label={S.head} value={slot.head} w={18} onChange={(head) => setDraft(editSlot(draft, index, { head }))} />
            <Input
              label={S.session}
              value={slot.session ?? ''}
              w={22}
              placeholder={S.open}
              onChange={(session) => setDraft(bindSession(draft, index, session))}
            />
            {/* The lead flag is exclusive: setting it here clears it on every other slot. */}
            <Flag
              on={slot.lead}
              onLabel={S.lead}
              offLabel={S.notLead}
              onChange={(on) => setDraft(on ? setLead(draft, index) : { ...draft, slots: draft.slots.map((s, at) => (at === index ? { ...s, lead: false } : s)) })}
            />
            <Lines label={S.instructions} rows={2} value={slot.instructions} onChange={(instructions) => setDraft(editSlot(draft, index, { instructions }))} />
            <div className="myx-compose-slot-actions">
              <Key onClick={() => setDraft(unbindSession(draft, index))} disabled={slot.session === null}>{S.unbind}</Key>
              <Key onClick={() => setDraft(removeSlot(draft, index))}>{S.removeSlot}</Key>
            </div>
          </div>
        ))}
        <div className="myx-compose-slot-actions">
          <Key onClick={() => setDraft(addSlot(draft, mint()))}>{S.addSlot}</Key>
        </div>
      </fieldset>

      {/* Archive is a flag and nothing more: the team, its slots and their bindings all stay. */}
      <div className="myx-compose-foot">
        <Flag on={draft.archived} onLabel={S.archived} offLabel={S.live} onChange={(on) => setDraft(setArchived(draft, on))} />
      </div>

      {problems.length > 0 ? (
        <div className="myx-compose-problems">
          <span className="myx-compose-problems-label">{S.problems}</span>
          <ul>{problems.map((problem) => <li key={problem}>{problem}</li>)}</ul>
        </div>
      ) : null}

      <div className="myx-compose-foot">
        <Key onClick={save} disabled={busy || problems.length > 0}>{team === null ? S.create : S.save}</Key>
        {answer === null ? null : <p className="myx-compose-answer" role="status">{answer}</p>}
      </div>
    </form>
  );
}
