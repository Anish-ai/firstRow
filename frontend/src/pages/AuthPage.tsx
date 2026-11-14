import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import { authStore } from '../auth/authStore';
import { useAnnounce } from '../components/Announcer';
import { usePageTitle } from '../lib/usePageTitle';

export function AuthPage({ mode }: { mode: 'login' | 'signup' }) {
  const isSignup = mode === 'signup';
  usePageTitle(isSignup ? 'Sign up' : 'Log in');
  const navigate = useNavigate();
  const announce = useAnnounce();
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  const submit = useMutation({
    mutationFn: () => (isSignup ? api.signup(email, password) : api.login(email, password)),
    onSuccess: (response) => {
      authStore.setFromResponse(response);
      announce(isSignup ? 'Account created. You are logged in.' : 'Logged in.');
      navigate(from, { replace: true });
    },
  });

  const problem = submit.error instanceof ApiError ? submit.error.problem : undefined;
  const fieldErrors = problem?.errors ?? {};

  function onSubmit(event: FormEvent) {
    event.preventDefault();
    submit.mutate();
  }

  return (
    <div className="mx-auto max-w-md">
      <p className="eyebrow text-center">{isSignup ? 'Join FirstRow' : 'Welcome back'}</p>
      <h1 className="mt-2 text-center text-4xl font-semibold">{isSignup ? 'Create an account' : 'Log in'}</h1>
      <form onSubmit={onSubmit} noValidate className="card mt-8 space-y-5 p-7">
        {submit.isError && (
          <div role="alert" className="alert-error">
            {Object.keys(fieldErrors).length > 0 ? 'Please fix the highlighted fields.' : submit.error.message}
          </div>
        )}
        <Field
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={setEmail}
          error={fieldErrors.email}
        />
        <Field
          id="password"
          label="Password"
          type="password"
          autoComplete={isSignup ? 'new-password' : 'current-password'}
          value={password}
          onChange={setPassword}
          error={fieldErrors.password}
          hint={isSignup ? 'At least 8 characters.' : undefined}
        />
        <button
          type="submit"
          disabled={submit.isPending}
          className="btn-primary w-full"
        >
          {submit.isPending ? 'Please wait…' : isSignup ? 'Sign up' : 'Log in'}
        </button>
      </form>
      <p className="mt-6 text-center text-sm text-muted">
        {isSignup ? 'Already have an account? ' : 'New here? '}
        <Link to={isSignup ? '/login' : '/signup'} state={{ from }} className="text-link">
          {isSignup ? 'Log in' : 'Create an account'}
        </Link>
      </p>
    </div>
  );
}

interface FieldProps {
  id: string;
  label: string;
  type: string;
  autoComplete: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
  hint?: string;
}

/** Label + input + hint/error, wired with aria-describedby and aria-invalid for screen readers. */
export function Field({ id, label, type, autoComplete, value, onChange, error, hint }: FieldProps) {
  const describedBy = [hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined;
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-ink">
        {label}
      </label>
      <input
        id={id}
        name={id}
        type={type}
        autoComplete={autoComplete}
        required
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={`field ${error ? 'border-red-500' : ''}`}
      />
      {hint && (
        <p id={`${id}-hint`} className="mt-1 text-xs text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="mt-1 text-sm text-red-700">
          {label} {error}
        </p>
      )}
    </div>
  );
}
