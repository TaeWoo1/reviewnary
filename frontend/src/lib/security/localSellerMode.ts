/**
 * Whether this build is the seller-PC install (scope lock v1.14 ①): the backend runs on the seller's own
 * machine and calls the marketplace APIs from there. Fixed at build time by `VITE_LOCAL_SELLER=true`, the same
 * variable `localSellerBuild.ts` checks before the build is allowed — so the screen and the build guard can
 * never disagree about which install this is.
 */
export function isLocalSellerBuild(): boolean {
  const env = import.meta.env as Record<string, unknown>;
  return env.VITE_LOCAL_SELLER === "true";
}
