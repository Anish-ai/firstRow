import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';

type Politeness = 'polite' | 'assertive';
type Announce = (message: string, politeness?: Politeness) => void;

const AnnouncerContext = createContext<Announce>(() => {});

/**
 * Screen-reader announcements via ARIA live regions. Text placed in a live region is read aloud
 * without moving focus: "polite" waits for a pause, "assertive" interrupts (used for failures).
 * The regions are visually hidden but stay in the accessibility tree.
 */
export function AnnouncerProvider({ children }: { children: ReactNode }) {
  const [messages, setMessages] = useState<Record<Politeness, string>>({ polite: '', assertive: '' });
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  const announce = useCallback<Announce>((message, politeness = 'polite') => {
    // Clear first, then set: otherwise repeating the same message wouldn't be read again.
    setMessages((m) => ({ ...m, [politeness]: '' }));
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setMessages((m) => ({ ...m, [politeness]: message })), 50);
  }, []);

  return (
    <AnnouncerContext.Provider value={announce}>
      {children}
      <div className="sr-only" role="status" aria-live="polite" data-testid="announcer-polite">
        {messages.polite}
      </div>
      <div className="sr-only" role="alert" aria-live="assertive" data-testid="announcer-assertive">
        {messages.assertive}
      </div>
    </AnnouncerContext.Provider>
  );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAnnounce(): Announce {
  return useContext(AnnouncerContext);
}
