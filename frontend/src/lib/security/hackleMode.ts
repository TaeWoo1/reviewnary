/**
 * Hackle is a test-only switch (2026-11-07, owner): the business app does not ship it, because Hackle 12.2.1 attaches
 * the full page address, query, referrer and title to every event and cannot be told not to (`docs/FINDINGS.md` B116).
 *
 * The key therefore lives in `frontend/.env.hackle.local`, which Vite reads only in this mode
 * (`npm run dev:hackle` · `npm run build:hackle`). A key reaching any other mode — `.env.local`, a shell variable, a CI
 * secret — would silently turn the vendor on (`sinksFromEnv`, `csp.ts`), so `vite.config.ts` refuses it instead.
 * Pure, so it is unit-tested; it names the variable, never its value.
 */
export const HACKLE_MODE = "hackle";

export function hackleKeyOutsideItsMode(mode: string, env: { VITE_HACKLE_SDK_KEY?: string }): boolean {
  return mode !== HACKLE_MODE && Boolean(env.VITE_HACKLE_SDK_KEY?.trim());
}
