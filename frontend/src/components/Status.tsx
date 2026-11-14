import { ApiError } from '../api/client';

export function Loading({ what }: { what: string }) {
  return (
    <p role="status" className="flex items-center gap-3 text-muted">
      <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-line border-t-accent-bright" />
      Loading {what}…
    </p>
  );
}

/** Shows an API error's ProblemDetail message. role="alert" makes screen readers read it immediately. */
export function ErrorMessage({ error }: { error: unknown }) {
  const message = error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
  return (
    <div role="alert" className="alert-error p-4">
      {message}
    </div>
  );
}
