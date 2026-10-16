/**
 * Seller-PC build guard — scope lock v1.14 ① · ⑤ (`docs/product-scope-v1.md`), proposal §18.7 L1.
 *
 * A build made for a seller's own PC (`VITE_LOCAL_SELLER=true`) must not carry a destination off that PC:
 * no Sentry, no GTM/GA4, no PostHog, and no backend / Agent Runtime / bridge address other than this
 * machine. Each of those is switched on by env alone (see `csp.ts`), so a stray variable inherited from a
 * shell or a CI secret would silently ship one — this refuses the build instead. Read by `vite.config.ts`
 * before anything is built or served. Pure, so it is unit-tested; it names variables, never their values.
 */
export interface LocalSellerBuildEnv {
  VITE_LOCAL_SELLER?: string;
  VITE_SENTRY_DSN?: string;
  VITE_GTM_ID?: string;
  VITE_POSTHOG_KEY?: string;
  VITE_API_BASE_URL?: string;
  VITE_AGENT_RUNTIME_URL?: string;
  VITE_BRIDGE_URL?: string;
}

/** Variables that turn on a destination off the PC merely by being non-empty. */
const OFF_PC_WHEN_SET = ["VITE_SENTRY_DSN", "VITE_GTM_ID", "VITE_POSTHOG_KEY"] as const;

/** Addresses the app calls; each must be empty (the code default / same origin) or a loopback http(s) URL. */
const MUST_BE_LOCAL = ["VITE_API_BASE_URL", "VITE_AGENT_RUNTIME_URL", "VITE_BRIDGE_URL"] as const;

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

function isLoopbackUrl(raw: string): boolean {
  try {
    const url = new URL(raw);
    return (url.protocol === "http:" || url.protocol === "https:") && LOOPBACK_HOSTS.has(url.hostname);
  } catch {
    return false;
  }
}

/** The offending variable names, in a stable order; empty when the build is not a seller-PC build. */
export function localSellerBuildProblems(env: LocalSellerBuildEnv): string[] {
  if (env.VITE_LOCAL_SELLER?.trim() !== "true") return [];
  const problems: string[] = [];
  for (const name of OFF_PC_WHEN_SET) {
    if (env[name]?.trim()) problems.push(name);
  }
  for (const name of MUST_BE_LOCAL) {
    const value = env[name]?.trim();
    if (value && !isLoopbackUrl(value)) problems.push(name);
  }
  return problems;
}
