export const AUTH_CHANGED_EVENT = "zeroclaw-auth-changed";

export interface SessionState {
  authenticated: boolean;
  paired: boolean;
  require_pairing: boolean;
}

export function unauthenticatedSession(): SessionState {
  return {
    authenticated: false,
    paired: false,
    require_pairing: true,
  };
}

export function isAuthenticated(session: SessionState): boolean {
  return session.authenticated || !session.require_pairing;
}

export function dispatchAuthChanged(): void {
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}
