import { useEffect, useRef, type ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router';
import { authStore, useSession } from '../auth/authStore';

/** Text links with an orange underline that marks the current page (aria-current is set by NavLink). */
const navClass = ({ isActive }: { isActive: boolean }) =>
  `relative px-1 py-2 text-sm font-medium transition-colors after:absolute after:inset-x-1 after:-bottom-px after:h-0.5 after:rounded-full after:transition-colors ${
    isActive ? 'text-ink after:bg-accent-bright' : 'text-muted hover:text-ink after:bg-transparent hover:after:bg-tan'
  }`;

export function Layout({ children }: { children: ReactNode }) {
  const session = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const mainRef = useRef<HTMLElement>(null);
  const firstRender = useRef(true);

  // In a single-page app the browser doesn't announce "new page" on navigation.
  // Moving focus to <main> makes screen readers start reading the new page's content.
  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    mainRef.current?.focus();
  }, [location.pathname]);

  return (
    <div className="flex min-h-screen flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-full focus:bg-surface focus:px-4 focus:py-2 focus:font-medium focus:text-ink"
      >
        Skip to main content
      </a>

      <div className="bg-graphite text-[13px] text-sand">
        <p className="mx-auto flex max-w-6xl items-center gap-2 px-4 py-1.5">
          <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-accent-bright" />
          Box office open · premieres and live matches · demo payments only
        </p>
      </div>

      <header className="header-band border-b border-line">
        <nav aria-label="Main" className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-4">
          <Link to="/" className="mr-2 flex items-center gap-2.5 rounded-lg">
            <Logo />
            <span className="flex flex-col leading-none">
              <span className="font-display text-2xl font-semibold tracking-tight text-ink">FirstRow</span>
              <span className="mt-1 text-[10px] font-semibold uppercase tracking-[0.25em] text-cocoa">Seats, sorted</span>
            </span>
          </Link>
          <div className="flex items-center gap-4">
            <NavLink to="/" end className={navClass}>
              Events
            </NavLink>
            {session && (
              <NavLink to="/bookings" className={navClass}>
                My bookings
              </NavLink>
            )}
            {session?.user.role === 'ADMIN' && (
              <NavLink to="/admin" className={navClass}>
                Admin
              </NavLink>
            )}
          </div>
          <div className="ml-auto flex items-center gap-3">
            {session ? (
              <>
                <span className="hidden items-center gap-2 text-sm text-muted sm:flex">
                  <span
                    aria-hidden="true"
                    className="flex h-8 w-8 items-center justify-center rounded-full bg-sand font-semibold uppercase text-cocoa ring-1 ring-tan/60"
                  >
                    {session.user.email.charAt(0)}
                  </span>
                  {session.user.email}
                </span>
                <button
                  type="button"
                  onClick={() => {
                    authStore.clear();
                    navigate('/');
                  }}
                  className="btn-small"
                >
                  Log out
                </button>
              </>
            ) : (
              <>
                <NavLink to="/login" className={navClass}>
                  Log in
                </NavLink>
                <Link to="/signup" className="btn-primary px-4 py-2 text-sm">
                  Sign up
                </Link>
              </>
            )}
          </div>
        </nav>
      </header>

      <main id="main" ref={mainRef} tabIndex={-1} className="mx-auto w-full max-w-6xl flex-1 px-4 py-10 outline-none">
        {children}
      </main>

      <footer className="border-t border-line">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-2 px-4 py-6 text-sm text-muted">
          <span>
            <span className="font-display font-semibold text-ink">FirstRow</span> · a portfolio project. All events and
            venues are fictional.
          </span>
          <span>No real payments are taken.</span>
        </div>
      </footer>
    </div>
  );
}

/** A row of cinema seats with one picked out in orange: the whole app in one picture. */
function Logo() {
  return (
    <svg aria-hidden="true" viewBox="0 0 40 40" className="h-10 w-10">
      <rect width="40" height="40" rx="11" fill="var(--color-ink)" />
      <path d="M9 27v-7a3 3 0 0 1 3-3h16a3 3 0 0 1 3 3v7" fill="none" stroke="var(--color-tan)" strokeWidth="2.5" />
      <rect x="14.5" y="21" width="11" height="6" rx="2" fill="var(--color-accent-bright)" />
      <path d="M10 31h20" stroke="var(--color-tan)" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
  );
}
