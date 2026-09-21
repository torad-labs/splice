// The palette: one list over the thirteen addresses, the two rooms, and the
// saved views of the page you are standing on. Opened by ctrl+k or `/`, closed
// by escape or by choosing.
//
// It takes the views, the theme and the addresses as props rather than reaching
// for them: the app layer composes the slices, and a feature cannot import a
// feature (the boundaries wall).
import { useEffect, useState } from 'react';
import { Command } from 'cmdk';
import { useNavigate } from 'react-router';
import { S } from './strings';
import './palette.css';

export interface PaletteView {
  id: string;
  name: string;
}

export interface PaletteProps {
  addresses: readonly string[];
  views: readonly PaletteView[];
  onSelectView: (id: string) => void;
  theme: 'dark' | 'light';
  onTheme: (theme: 'dark' | 'light') => void;
}

/** `/` is a jumper only when the operator is not typing into something. */
function isTyping(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  const tag = target.tagName.toLowerCase();
  return tag === 'input' || tag === 'textarea' || tag === 'select' || target.isContentEditable;
}

export function Palette({ addresses, views, onSelectView, theme, onTheme }: PaletteProps) {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setOpen((shown) => !shown);
        return;
      }
      if (event.key === '/' && !isTyping(event.target)) {
        event.preventDefault();
        setOpen(true);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, []);

  return (
    <Command.Dialog
      open={open}
      onOpenChange={setOpen}
      label={S.palette}
      overlayClassName="myx-palette-scrim"
      contentClassName="myx-palette"
    >
      <Command.Input className="myx-palette-input" placeholder={S.placeholder} />
      <Command.List className="myx-palette-list">
        <Command.Empty className="myx-palette-empty">{S.noMatch}</Command.Empty>

        <Command.Group className="myx-palette-group" heading={S.pages}>
          {addresses.map((address) => (
            <Command.Item
              key={address}
              className="myx-palette-item"
              value={address}
              onSelect={() => {
                setOpen(false);
                void navigate(`/${address}`);
              }}
            >
              {address}
            </Command.Item>
          ))}
        </Command.Group>

        {views.length > 0 ? (
          <Command.Group className="myx-palette-group" heading={S.views}>
            {views.map((view) => (
              <Command.Item
                key={view.id}
                className="myx-palette-item"
                value={`view ${view.name}`}
                onSelect={() => {
                  setOpen(false);
                  onSelectView(view.id);
                }}
              >
                {view.name}
              </Command.Item>
            ))}
          </Command.Group>
        ) : null}

        <Command.Group className="myx-palette-group" heading={S.theme}>
          <Command.Item
            className="myx-palette-item"
            value={S.themeDark}
            onSelect={() => {
              setOpen(false);
              onTheme('dark');
            }}
          >
            {S.themeDark}
            {theme === 'dark' ? <span className="myx-palette-mark">{S.current}</span> : null}
          </Command.Item>
          <Command.Item
            className="myx-palette-item"
            value={S.themeLight}
            onSelect={() => {
              setOpen(false);
              onTheme('light');
            }}
          >
            {S.themeLight}
            {theme === 'light' ? <span className="myx-palette-mark">{S.current}</span> : null}
          </Command.Item>
        </Command.Group>
      </Command.List>
    </Command.Dialog>
  );
}
