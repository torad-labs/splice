import { useEffect, useRef } from 'react';
import { Search } from './icons';

/** A search box that ⌘K (or Ctrl+K) focuses from anywhere on the page. */
export function SearchField({ value, onChange, label, hint }: { value: string; onChange: (value: string) => void; label: string; hint?: string }) {
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const focus = (event: KeyboardEvent): void => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        input.current?.focus();
      }
    };
    window.addEventListener('keydown', focus);
    return () => window.removeEventListener('keydown', focus);
  }, []);
  return (
    <label className="search">
      <Search />
      <input ref={input} type="search" value={value} placeholder={hint} aria-label={label} onChange={(event) => onChange(event.target.value)} />
      <span className="kbd" aria-hidden="true">⌘K</span>
    </label>
  );
}
