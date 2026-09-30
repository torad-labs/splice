import type { HTMLAttributes, ReactNode } from 'react';
import type { ModelColour } from '../lib/model';

interface Props extends HTMLAttributes<HTMLElement> {
  /** The model whose colour is the object's back edge. */
  colour?: ModelColour;
  /** This object needs the operator: its outline is the charged colour. */
  attention?: boolean;
  as?: 'article' | 'section' | 'div' | 'li';
}

/** A paper object: dark outline, a hard cast shadow in its model's colour. Sessions, plans and
 *  needs-you items are each one. */
export function Window({ colour = 'none', attention = false, as: Tag = 'article', className, ...rest }: Props) {
  const classes = ['win', colour === 'none' ? '' : colour, attention ? 'attn' : '', className ?? ''].filter(Boolean).join(' ');
  return <Tag className={classes} {...rest} />;
}

export function WindowBar({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return (
    <div className="bar">
      <h3>{title}</h3>
      {children}
    </div>
  );
}
