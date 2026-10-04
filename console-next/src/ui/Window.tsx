import type { ElementType, HTMLAttributes, ReactNode, Ref } from 'react';
import type { ModelColour } from '../lib/model';

interface Props extends HTMLAttributes<HTMLElement> {
  ref?: Ref<HTMLElement>;
  /** The model whose colour is the object's back edge. */
  colour?: ModelColour;
  /** This object needs the operator: it drops its hue (tan shadow) and keeps the one vermilion button. */
  attention?: boolean;
  as?: 'article' | 'section' | 'div' | 'li';
}

/** A paper object: dark outline, a hard cast shadow in its model's colour. Sessions, plans and
 *  needs-you items are each one. */
export function Window({ colour = 'none', attention = false, as = 'article', className, ...rest }: Props) {
  // One element kind chosen by the caller; the props are the shared HTML ones, `ref` included (React 19 passes it as a prop).
  const Tag = as as ElementType<Omit<Props, 'as' | 'colour' | 'attention'>>;
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
