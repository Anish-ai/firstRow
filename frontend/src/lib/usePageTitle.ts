import { useEffect } from 'react';

/** Each page sets the browser tab title; screen readers announce it too. */
export function usePageTitle(title: string) {
  useEffect(() => {
    document.title = `${title} · FirstRow`;
  }, [title]);
}
