import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import type { Role } from '../api/types';
import { useSession } from './authStore';

/**
 * Sends logged-out users to the login page (remembering where they were going).
 * This is only for user experience: the real access control is the backend's 401/403.
 */
export function RequireAuth({ children, requiredRole }: { children: ReactNode; requiredRole?: Role }) {
  const session = useSession();
  const location = useLocation();

  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (requiredRole && session.user.role !== requiredRole) {
    return (
      <div role="alert" className="alert-warn">
        This page is for {requiredRole.toLowerCase()}s only.
      </div>
    );
  }
  return <>{children}</>;
}
