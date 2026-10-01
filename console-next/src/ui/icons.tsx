// The small icon set the film's console draws with. Strokes, no fills, at the sizes the controls use.
import type { ReactElement, SVGProps } from 'react';

type P = SVGProps<SVGSVGElement>;
const line = (size: number, width: number, path: ReactElement, p: P) => (
  <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} fill="none" stroke="currentColor" strokeWidth={width} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...p}>
    {path}
  </svg>
);

export const Search = (p: P) => line(16, 2, <><circle cx="7" cy="7" r="5" /><path d="M11 11l3.5 3.5" /></>, p);
export const Back = (p: P) => line(16, 2.2, <path d="M10 3L5 8l5 5" />, p);
export const Chevron = (p: P) => line(14, 2.2, <path d="M4 5l3 3 3-3" />, p);
export const Arrow = (p: P) => line(16, 2.2, <path d="M3 8h10M9 4l4 4-4 4" />, p);
export const Check = (p: P) => line(14, 2.4, <path d="M2.5 7.5l3 3 6-7" />, p);
export const Send = (p: P) => line(16, 2.2, <path d="M8 13V3M3.5 7.5L8 3l4.5 4.5" />, p);
export const Plus = (p: P) => line(16, 2.4, <path d="M8 3v10M3 8h10" />, p);
export const Folder = (p: P) => line(16, 1.8, <path d="M1.5 4.5a1 1 0 011-1h3.2l1.6 1.8h6.2a1 1 0 011 1V12a1 1 0 01-1 1h-11a1 1 0 01-1-1z" />, p);
export const Clock = (p: P) => line(14, 1.8, <><circle cx="7" cy="7" r="5.5" /><path d="M7 4v3.2l2 1.3" /></>, p);
export const Stop = (p: P) => (
  <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor" aria-hidden="true" {...p}>
    <rect x="1" y="1" width="10" height="10" rx="2" />
  </svg>
);
export const Close = (p: P) => line(14, 2.2, <path d="M3 3l8 8M11 3l-8 8" />, p);
export const Copy = (p: P) => line(14, 1.8, <><rect x="4.5" y="4.5" width="7" height="8" rx="1.5" /><path d="M9.5 4.5V3a1 1 0 00-1-1h-5a1 1 0 00-1 1v6.5a1 1 0 001 1H4.5" /></>, p);
export const KeyIcon = (p: P) => line(16, 1.8, <><circle cx="5.5" cy="10.5" r="3" /><path d="M8 8.5L13.5 3M11.5 5l1.8 1.8" /></>, p);
