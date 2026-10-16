import { execSync } from "node:child_process";
import { defineConfig, loadEnv, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { buildCsp } from "./src/lib/security/csp";
import { localSellerBuildProblems } from "./src/lib/security/localSellerBuild";

// Dev API proxy target. The frontend calls SAME-ORIGIN `/api/*` (see apiClient.ts BASE_URL), and the dev
// server forwards those to the backend here. Overriding the port for a disposable/walkthrough backend is
// done by exporting SELLEROPS_BACKEND_ORIGIN before `npm run dev` — NOT by editing an absolute
// VITE_API_BASE_URL (which is exactly the stale-port failure this proxy removes). Same-origin also avoids
// the localhost/127.0.0.1 CORS mismatch that broke a past login.
const BACKEND_ORIGIN = process.env.SELLEROPS_BACKEND_ORIGIN ?? "http://127.0.0.1:8080";

/** Release identity for Sentry only (docs/standing/service_readiness_v1.md §2-7): SELLEROPS_RELEASE, else the git SHA. */
function releaseId(): string {
  const fromEnv = process.env.SELLEROPS_RELEASE?.trim();
  if (fromEnv) return fromEnv;
  try {
    return execSync("git rev-parse --short=12 HEAD", { stdio: ["ignore", "pipe", "ignore"] }).toString().trim() || "unknown";
  } catch {
    return "unknown";
  }
}

/**
 * Production-build CSP `<meta>` (docs/standing/service_readiness_v1.md §2-5) from the same env that enables each vendor.
 * Build only: the dev server injects inline scripts (React refresh) and needs its websocket.
 */
function cspMetaPlugin(env: Record<string, string>): Plugin {
  return {
    name: "sellerops-csp-meta",
    apply: "build",
    transformIndexHtml() {
      return [{ tag: "meta", attrs: { "http-equiv": "Content-Security-Policy", content: buildCsp(env) }, injectTo: "head-prepend" }];
    },
  };
}

function previewProxy() {
  return {
    "/api": { target: BACKEND_ORIGIN, changeOrigin: true },
    "/oauth2": { target: BACKEND_ORIGIN, changeOrigin: false },
    "/login/oauth2": { target: BACKEND_ORIGIN, changeOrigin: false },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  // Seller-PC build (VITE_LOCAL_SELLER=true): refuse before anything is built or served if env would ship a
  // destination off the PC — scope lock v1.14 ⑤. Names the variables, never their values.
  const localSellerProblems = localSellerBuildProblems(env);
  if (localSellerProblems.length > 0) {
    throw new Error(
      `판매자 PC 빌드(VITE_LOCAL_SELLER=true)에 PC 밖으로 나가는 설정이 있습니다: ${localSellerProblems.join(", ")}`,
    );
  }
  return {
    plugins: [react(), cspMetaPlugin(env)],
    define: {
      __SELLEROPS_RELEASE__: JSON.stringify(releaseId()),
    },
    // `vite preview` (production build walkthroughs) proxies exactly like the dev server.
    preview: { port: 4173, proxy: previewProxy() },
    server: {
      port: 5173,
      host: true,
      proxy: {
        "/api": {
          target: BACKEND_ORIGIN,
          changeOrigin: true,
        },
        // Social login (Google · NAVER) is a top-level browser navigation to the backend's Spring Security
        // OAuth2 endpoints. Proxied WITHOUT changeOrigin so the backend computes the redirect URI from the
        // origin the browser used (http://localhost:5173/login/oauth2/code/<provider>) — the one registered
        // at the provider console. docs/standing/auth_growth_instrumentation_v1.md §3.
        "/oauth2": { target: BACKEND_ORIGIN, changeOrigin: false },
        "/login/oauth2": { target: BACKEND_ORIGIN, changeOrigin: false },
      },
    },
  };
});
