import type { ButtonHTMLAttributes } from 'react';

export type ButtonKind = 'plain' | 'go' | 'quiet' | 'danger';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  kind?: ButtonKind;
  small?: boolean;
}

/** One charged act per item wears `go` (vermilion); the second action is `quiet`. */
export function Button({ kind = 'plain', small = false, className, type = 'button', ...rest }: Props) {
  const classes = ['btn', kind === 'plain' ? '' : kind, small ? 'sm' : '', className ?? ''].filter(Boolean).join(' ');
  return <button type={type} className={classes} {...rest} />;
}
