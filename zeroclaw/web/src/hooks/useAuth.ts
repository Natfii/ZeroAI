import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import React from 'react';
import { getSession, logout as apiLogout, pair as apiPair } from '../lib/api';
import {
  AUTH_CHANGED_EVENT,
  isAuthenticated as sessionIsAuthenticated,
  unauthenticatedSession,
  type SessionState,
} from '../lib/auth';

export interface AuthState {
  token: null;
  isAuthenticated: boolean;
  loading: boolean;
  pair: (code: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshSession: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

export interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [session, setSession] = useState<SessionState>(unauthenticatedSession);
  const [loading, setLoading] = useState<boolean>(true);

  const refreshSession = useCallback(async (): Promise<void> => {
    try {
      const nextSession = await getSession();
      setSession(nextSession);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshSession();

    const handleAuthChange = () => {
      void refreshSession();
    };

    window.addEventListener(AUTH_CHANGED_EVENT, handleAuthChange);
    window.addEventListener('zeroclaw-unauthorized', handleAuthChange);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, handleAuthChange);
      window.removeEventListener('zeroclaw-unauthorized', handleAuthChange);
    };
  }, [refreshSession]);

  const pair = useCallback(async (code: string): Promise<void> => {
    await apiPair(code);
    await refreshSession();
  }, [refreshSession]);

  const logout = useCallback(async (): Promise<void> => {
    await apiLogout();
    await refreshSession();
  }, [refreshSession]);

  const value = useMemo<AuthState>(() => ({
    token: null,
    isAuthenticated: sessionIsAuthenticated(session),
    loading,
    pair,
    logout,
    refreshSession,
  }), [loading, logout, pair, refreshSession, session]);

  return React.createElement(AuthContext.Provider, { value }, children);
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an <AuthProvider>');
  }
  return ctx;
}
