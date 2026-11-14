import { useEffect, useState } from 'react';

/** Whole seconds until `deadlineIso`, updated every second; never below 0. */
export function useSecondsLeft(deadlineIso: string | undefined): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (!deadlineIso) return 0;
  return Math.max(0, Math.ceil((new Date(deadlineIso).getTime() - now) / 1000));
}
