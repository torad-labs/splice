import type { ReactNode } from 'react';
import type { ModelColour } from '../lib/model';

export type StateTone = 'work' | 'wait' | 'stuck' | 'idle' | 'quota';

/** The state a thing is in, as a word and a dot: never colour alone. */
export function State({ tone, children }: { tone: StateTone; children: ReactNode }) {
  return (
    <span className={`state ${tone}`}>
      <i />
      {children}
    </span>
  );
}

/** A model's dot beside its name. */
export function ModelMark({ colour, children }: { colour: ModelColour; children: ReactNode }) {
  return <span className={`model m-${colour}`}>{children}</span>;
}
