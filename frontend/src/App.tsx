import { BrowserRouter, Link, Route, Routes } from 'react-router';
import { RequireAuth } from './auth/RequireAuth';
import { AppProviders } from './AppProviders';
import { Layout } from './components/Layout';
import { AdminPage } from './pages/AdminPage';
import { AuthPage } from './pages/AuthPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { EventDetailPage } from './pages/EventDetailPage';
import { EventsPage } from './pages/EventsPage';
import { MyBookingsPage } from './pages/MyBookingsPage';

export function AppRoutes() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<EventsPage />} />
        <Route path="/events/:eventId" element={<EventDetailPage />} />
        <Route path="/login" element={<AuthPage mode="login" key="login" />} />
        <Route path="/signup" element={<AuthPage mode="signup" key="signup" />} />
        <Route
          path="/checkout/:holdId"
          element={
            <RequireAuth>
              <CheckoutPage />
            </RequireAuth>
          }
        />
        <Route
          path="/bookings"
          element={
            <RequireAuth>
              <MyBookingsPage />
            </RequireAuth>
          }
        />
        <Route
          path="/admin"
          element={
            <RequireAuth requiredRole="ADMIN">
              <AdminPage />
            </RequireAuth>
          }
        />
        <Route
          path="*"
          element={
            <>
              <p className="eyebrow">Error 404</p>
              <h1 className="mt-2 text-4xl font-semibold">Page not found</h1>
              <Link to="/" className="text-link mt-4 inline-block">
                Back to events
              </Link>
            </>
          }
        />
      </Routes>
    </Layout>
  );
}

export default function App() {
  return (
    <AppProviders>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </AppProviders>
  );
}
