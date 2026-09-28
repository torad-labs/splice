import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { fetchAddProfiles } from '@entities/add';
import type { AddProfile } from '@entities/add';
import { Blank, Fault, Key } from '@shared/controls';
import { planChoices } from './model';
import type { PlanChoice } from './model';
import { H, S } from './strings';
import './connect-plan.css';

export function PlanPicker({ choices, onSelect, onOther }: {
  choices: readonly PlanChoice[];
  onSelect: (profile: string) => void;
  onOther: () => void;
}) {
  return (
    <section className="myx-connect" aria-label={S.connect}>
      <p>{H.intro}</p>
      <div className="myx-connect-options">
        {choices.map((choice) => (
          <Key key={choice.id} className="myx-connect-card" ariaLabel={choice.label}
            disabled={choice.profile === null} onClick={() => onSelect(choice.id)}>
            <span className="myx-connect-name">{choice.label}</span>
            <span className="myx-connect-description">{choice.profile === null ? H.unavailable : choice.description}</span>
          </Key>
        ))}
      </div>
      <Key className="myx-connect-card" ariaLabel={S.other} onClick={onOther}>
        <span className="myx-connect-name">{S.other}</span>
        <span className="myx-connect-description">{H.other}</span>
      </Key>
    </section>
  );
}

export function ConnectPlan({ renderAdd }: { renderAdd: (profile: string | null, onDone: () => void) => ReactNode }) {
  const [profiles, setProfiles] = useState<AddProfile[] | null>(null);
  const [selected, setSelected] = useState<{ profile: string | null } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchAddProfiles().then(setProfiles, (cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause)));
  }, []);

  if (error !== null) return <Fault message={error} />;
  if (profiles === null) return <Blank strips={3} />;
  if (selected !== null) return (
    <div className="myx-connect">
      <Key onClick={() => setSelected(null)}>{S.allPlans}</Key>
      {renderAdd(selected.profile, () => setSelected(null))}
    </div>
  );
  return <PlanPicker choices={planChoices(profiles)} onSelect={(profile) => setSelected({ profile })} onOther={() => setSelected({ profile: null })} />;
}
