// The team composer: the form that writes a team — its name, goal, feature list and repo, and the
// role slots with their lead flag, their standing instructions and the session bound to each.
//
// IT CANNOT SAVE YET AND SAYS SO. PUT /api/teams is V4-131's work, so the form validates in full,
// prints what still stops the draft, and then prints the honest empty naming the row instead of a
// button that would drop the operator's work on the floor.
//
// THE FIELDS ARE FORM FIELDS, NOT STRIPS. A strip is a focusable button (shared/ui/strip.tsx) and
// an input inside one is a nested interactive element, which is the same reason the settings page
// composes field boxes rather than racks.
import { useState } from 'react';
import type { ReactNode } from 'react';
import { Flag, Input, Key } from '@shared/controls';
import { Empty } from '@shared/ui';
import {
  addSlot, bindSession, blankDraft, editSlot, featuresOf, removeSlot, setArchived, setLead,
  unbindSession, validateDraft,
} from './model';
import type { TeamDraft } from './model';
import { S } from './strings';
import './team-compose.css';

export { addSlot, bindSession, blankDraft, draftOf, editSlot, featuresOf, removeSlot, setArchived, setLead, unbindSession, validateDraft } from './model';
export type { DraftSlot, TeamDraft } from './model';

/** The row that will serve the write, printed wherever the form would otherwise promise one. */
export const PENDING_COMPOSE = 'V4-131';

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

export function TeamCompose({ initial }: { initial?: TeamDraft }) {
  const [draft, setDraft] = useState<TeamDraft>(initial ?? blankDraft());
  const problems = validateDraft(draft);

  return (
    <form className="myx-compose" aria-label={S.compose} onSubmit={(event) => event.preventDefault()}>
      <h3 className="myx-compose-title">{S.compose}</h3>

      <div className="myx-compose-head">
        <Input label={S.name} value={draft.name} w={24} onChange={(name) => setDraft({ ...draft, name })} />
        <Input label={S.repo} value={draft.repo} w={40} onChange={(repo) => setDraft({ ...draft, repo })} />
        <Input label={S.goal} value={draft.goal} w={40} onChange={(goal) => setDraft({ ...draft, goal })} />
        <Lines label={S.features} rows={3} value={draft.features.join('\n')} onChange={(text) => setDraft({ ...draft, features: featuresOf(text) })} />
      </div>

      <fieldset className="myx-compose-slots">
        <legend>{S.slots}</legend>
        {draft.slots.map((slot, index) => (
          // The index IS the identity here: two blank slots are not distinguishable by content,
          // and the operator is editing positions in a list they can see.
          <div className="myx-compose-slot" key={`slot-${index}`}>
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
          <Key onClick={() => setDraft(addSlot(draft))}>{S.addSlot}</Key>
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

      {/* The save. Not a disabled button with a tooltip: the route does not exist, and the
          console's answer to that is the same honest empty every other pending surface prints. */}
      <Empty text="the console cannot save a team yet" source={`${PENDING_COMPOSE} serves PUT /api/teams`} />
    </form>
  );
}
