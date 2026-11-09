import { createContext, useContext, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import type { AuthResponse, UserView } from "./types";
import { api, clearToken, getToken, setToken } from "./apiClient";
import { analytics } from "./analytics";
import { clearSessionScopedState } from "./sessionScope";

interface AuthState {
  user: UserView | null;
  ready: boolean;
  login: (email: string, password: string) => Promise<void>;
  signup: (input: {
    email: string;
    password: string;
    name: string;
    orgName: string;
    termsAccepted: boolean;
    marketingConsent: boolean;
  }) => Promise<void>;
  /** A session the backend already issued (social login code exchange / onboarding complete). */
  acceptSession: (session: AuthResponse) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

/**
 * Product analytics identity = the opaque internal user id (never email/name/상호), told at the moment the session
 * changes — inside the same call that sets it, before the caller goes on (2026-11-07).
 *
 * <p>It used to be a `useEffect` on `user`, which runs only after React commits the new user. But `Login` tracks
 * `login` right after `await login()` returns, and the home screen tracks `today_inbox_viewed` while it renders — both
 * before that effect — so both reached the sinks under the previous (signed-out) identity and Hackle recorded them as
 * anonymous (browser check, 2026-11-07). Telling the identity here puts it ahead of anything the caller does next.
 *
 * <p>Only once the session is known: before hydration finishes, "no user" means "still loading", and telling a sink
 * "signed out" then would let a vendor send events under whoever it restored from an earlier visit. A failed login
 * changes nothing and tells nothing. `analytics.identify` ignores a repeat of the same id.
 */
function tellIdentity(user: UserView | null) {
  analytics.identify(user ? user.id : null);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserView | null>(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;
    async function hydrate() {
      if (!getToken()) {
        tellIdentity(null);
        setReady(true);
        return;
      }
      try {
        const me = await api.getMe();
        if (active) {
          tellIdentity(me);
          setUser(me);
        }
      } catch {
        clearToken();
        if (active) tellIdentity(null);
      } finally {
        if (active) {
          setReady(true);
        }
      }
    }
    hydrate();
    return () => {
      active = false;
    };
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      ready,
      async login(email: string, password: string) {
        const res = await api.login(email, password);
        setToken(res.token);
        tellIdentity(res.user);
        setUser(res.user);
      },
      // Sign-up ends signed in: the backend answers with the same token+user shape as login, so the new
      // seller lands in the app without a second form (Self-Pilot first-run UX: 가입 → 채널 연결 → 첫 수집 → 홈).
      async signup(input) {
        const res = await api.signup(input);
        setToken(res.token);
        tellIdentity(res.user);
        setUser(res.user);
      },
      acceptSession(session) {
        setToken(session.token);
        tellIdentity(session.user);
        setUser(session.user);
      },
      logout() {
        // A session teardown, not a token removal (Agent Interaction Model v2 §0): the conversation
        // pointer, helper pairing token and connection-flow state must not stand into the next
        // identity, which may be a different organization on this machine.
        clearToken();
        clearSessionScopedState();
        tellIdentity(null);
        setUser(null);
      },
    }),
    [user, ready],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return ctx;
}

/** The session when one is around, null outside the shell (bare test renders). Never throws. */
export function useOptionalAuth(): AuthState | null {
  return useContext(AuthContext) ?? null;
}
