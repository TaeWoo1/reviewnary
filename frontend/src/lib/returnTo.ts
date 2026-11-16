/**
 * <b>Where to go back to after signing in</b> — only a path inside this service (night handoff 2026-11-16, unit ③).
 *
 * An alert's link opens `/notice/<id>`; a seller who is not signed in is sent to `/login?next=/notice/<id>` and
 * returns there after signing in (email or social). The `next` value comes from a URL anyone can craft, so it is
 * accepted only when it resolves to this same origin: it must start with one `/`, never `//` or `/\`, carry no
 * backslash, control character or scheme (also after decoding once), and not point back into the sign-in screens.
 * Anything else falls back to the home screen.
 */
const KEY = "sellerops_return_to";
const PROBE = "https://return-to.invalid";
const AUTH_PREFIXES = ["/login", "/signup", "/auth/", "/onboarding", "/forgot-password", "/reset-password"];

export function safeReturnPath(raw: string | null | undefined): string | null {
  if (!raw || raw.length > 512) return null;
  if (!raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return null;
  // eslint-disable-next-line no-control-regex
  if (/[\\\u0000-\u001f\u007f]/.test(raw)) return null;
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return null;
  }
  // eslint-disable-next-line no-control-regex
  if (decoded.startsWith("//") || /[\\\u0000-\u001f\u007f]/.test(decoded) || /^\/*[a-z][a-z0-9+.-]*:/i.test(decoded)) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(raw, PROBE);
  } catch {
    return null;
  }
  if (url.origin !== PROBE) return null;
  const path = `${url.pathname}${url.search}${url.hash}`;
  if (AUTH_PREFIXES.some((p) => url.pathname === p || url.pathname.startsWith(p.endsWith("/") ? p : `${p}/`))) {
    return null;
  }
  return path;
}

/** `/login?next=…` for the page the seller was trying to open, or plain `/login` when there is nothing to keep. */
export function loginPathFor(pathAndSearch: string, base = "/login"): string {
  const safe = safeReturnPath(pathAndSearch);
  if (!safe || safe === "/") return base;
  return `${base}${base.includes("?") ? "&" : "?"}next=${encodeURIComponent(safe)}`;
}

/** Kept across a social sign-in's round trip to the provider (this tab only). */
export function rememberReturnTo(path: string | null): void {
  try {
    if (path) sessionStorage.setItem(KEY, path);
    else sessionStorage.removeItem(KEY);
  } catch {
    /* storage unavailable: the seller lands on home instead */
  }
}

/** The remembered path, once — re-checked, then forgotten. */
export function takeReturnTo(): string | null {
  try {
    const v = sessionStorage.getItem(KEY);
    sessionStorage.removeItem(KEY);
    return safeReturnPath(v);
  } catch {
    return null;
  }
}
