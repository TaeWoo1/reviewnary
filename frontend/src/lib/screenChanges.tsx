import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api } from "./apiClient";

/**
 * <b>An open screen changes by itself when its data does</b> (owner decision 2026-10-27).
 *
 * <p>One question stays open with the server for the whole app — "has anything changed since this number?" — and the
 * server answers it the moment the PC stores what a delegate uploaded, a reply a delegate posted, a phone's approval.
 * Each read that draws a screen takes {@link useScreenChanges} as a dependency and reads again, quietly, when it moves:
 * what is on screen stays until the new answer lands, and a failed re-read keeps it.
 *
 * <p>A change while the window is in the background is kept and shown once when it comes back, so a tab left open all
 * day does not re-read for nobody. Any failure — no server, a build without the route — rests {@link REST_MS} and asks
 * again; the screens then simply stay as they were, which is how they behaved before this existed.
 */
export const REST_MS = 30_000;
/** An answer that is not a change, sooner than this, was not a held question; asking again at once would only spin. */
export const QUICK_MS = 5_000;

export interface ScreenChangesClient {
  waitForScreenChanges(after: number | null, signal?: AbortSignal): Promise<number>;
}

const ScreenChangesContext = createContext(0);

/** How many changes this app has been told of; 0 until the first. Outside the provider it never moves. */
export function useScreenChanges(): number {
  return useContext(ScreenChangesContext);
}

function rest(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(resolve, ms);
    signal.addEventListener("abort", () => {
      window.clearTimeout(timer);
      resolve();
    });
  });
}

export function ScreenChangesProvider({ children, client = api }: { children: ReactNode; client?: ScreenChangesClient }) {
  const [changes, setChanges] = useState(0);

  useEffect(() => {
    const stop = new AbortController();
    let pending = false;
    const show = () => {
      if (pending && document.visibilityState === "visible") {
        pending = false;
        setChanges((n) => n + 1);
      }
    };
    const run = async () => {
      let seen: number | null = null;
      while (!stop.signal.aborted) {
        const asked = Date.now();
        try {
          const version = await client.waitForScreenChanges(seen, stop.signal);
          if (stop.signal.aborted) return;
          if (seen !== null && version !== seen) {
            pending = true;
            show();
          } else if (seen !== null && Date.now() - asked < QUICK_MS) {
            await rest(REST_MS, stop.signal);
          }
          seen = version;
        } catch {
          if (stop.signal.aborted) return;
          await rest(REST_MS, stop.signal);
        }
      }
    };
    void run();
    document.addEventListener("visibilitychange", show);
    return () => {
      stop.abort();
      document.removeEventListener("visibilitychange", show);
    };
  }, [client]);

  return <ScreenChangesContext.Provider value={changes}>{children}</ScreenChangesContext.Provider>;
}
