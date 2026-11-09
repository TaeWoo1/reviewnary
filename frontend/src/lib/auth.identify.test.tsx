// @vitest-environment jsdom
/**
 * The analytics identity is told only once the session is known (2026-11-07). Before `ready`, "no user" means "still
 * loading": telling the sinks "signed out" then, and the real user a moment later, would let a vendor that restored
 * an earlier visitor's id send under it — or would announce a sign-out that never happened.
 */
import { act, render } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const identify = vi.fn();
let token: string | null = null;
let me: { resolve: (u: unknown) => void; reject: (e: unknown) => void };
const login = vi.fn();

vi.mock("./analytics", () => ({ analytics: { identify: (id: string | null) => identify(id) } }));
vi.mock("./sessionScope", () => ({ clearSessionScopedState: () => {} }));
vi.mock("./apiClient", () => ({
  getToken: () => token,
  setToken: (t: string) => (token = t),
  clearToken: () => (token = null),
  api: {
    getMe: () =>
      new Promise((resolve, reject) => {
        me = { resolve, reject };
      }),
    login: (email: string, password: string) => login(email, password),
  },
}));

import { AuthProvider, useAuth } from "./auth";

beforeEach(() => {
  identify.mockReset();
  login.mockReset();
  token = null;
});

/** The session, handed out so a test can call it the way `Login` / `SettingsHome` do. */
let auth: ReturnType<typeof useAuth>;
function Grab() {
  auth = useAuth();
  return null;
}

const userA = { id: "uuid-a", email: "a@example.test", name: "a", orgName: "o" };
const userB = { id: "uuid-b", email: "b@example.test", name: "b", orgName: "o" };

describe("AuthProvider → analytics.identify", () => {
  it("with a stored session: nothing while /me loads, then the user's opaque id once", async () => {
    token = "t";
    render(<AuthProvider>x</AuthProvider>);
    expect(identify).not.toHaveBeenCalled();
    await act(async () => me.resolve({ id: "uuid-1", email: "a@example.com", name: "n", orgName: "o" }));
    expect(identify.mock.calls).toEqual([["uuid-1"]]);
  });

  it("with no session: signed out, said once the session is known", async () => {
    render(<AuthProvider>x</AuthProvider>);
    await act(async () => {});
    expect(identify.mock.calls).toEqual([[null]]);
  });

  it("a refused session ends signed out — never a user", async () => {
    token = "t";
    render(<AuthProvider>x</AuthProvider>);
    expect(identify).not.toHaveBeenCalled();
    await act(async () => me.reject({ response: { status: 401 } }));
    expect(identify.mock.calls).toEqual([[null]]);
  });
});

describe("the identity is told inside the call that changes the session (2026-11-07)", () => {
  async function signedOut() {
    render(
      <AuthProvider>
        <Grab />
      </AuthProvider>,
    );
    await act(async () => {});
    identify.mockReset();
  }

  it("login: the user is in analytics before login() returns — so the caller's `login` event is theirs", async () => {
    await signedOut();
    login.mockResolvedValue({ token: "t", user: userA });
    let seenWhenReturned: unknown[][] = [];
    await act(async () => {
      await auth.login("a@example.test", "pw");
      seenWhenReturned = [...identify.mock.calls];
    });
    expect(seenWhenReturned).toEqual([["uuid-a"]]);
    expect(identify.mock.calls).toEqual([["uuid-a"]]);
  });

  it("a failed login tells nothing", async () => {
    await signedOut();
    login.mockRejectedValue({ response: { status: 401 } });
    await act(async () => {
      await auth.login("a@example.test", "wrong").catch(() => {});
    });
    expect(identify).not.toHaveBeenCalled();
  });

  it("A → sign-out → B: A, then signed out, then B — each once, B never under A", async () => {
    await signedOut();
    login.mockResolvedValueOnce({ token: "t", user: userA }).mockResolvedValueOnce({ token: "t", user: userB });
    await act(async () => {
      await auth.login("a@example.test", "pw");
    });
    await act(async () => auth.logout());
    await act(async () => {
      await auth.login("b@example.test", "pw");
    });
    expect(identify.mock.calls).toEqual([["uuid-a"], [null], ["uuid-b"]]);
  });

  it("acceptSession (social login) tells the user before the caller goes on", async () => {
    await signedOut();
    act(() => auth.acceptSession({ token: "t", user: userA } as never));
    expect(identify.mock.calls).toEqual([["uuid-a"]]);
  });
});
