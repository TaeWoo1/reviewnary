import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Link, useLocation } from "react-router-dom";
import { Btn } from "../ui/Btn";
import { api } from "../../lib/apiClient";
import { useAuth } from "../../lib/auth";
import {
  WATCH_INTERVAL_MS,
  canWatchFolder,
  exclusively,
  folderSyncState,
  listHandle,
  loadHandle,
  permissionOf,
  syncRound,
  type DirHandle,
  type HashCache,
} from "../../lib/folderSync";

/** The page's 「지금 확인」 reaches the window's one runner through this. */
export const folderSyncControl: { runNow: (() => Promise<void>) | null } = { runNow: null };

const cache: HashCache = new Map();

/**
 * One per window, mounted in the app shell: while any Reviewnary page is open in Chrome or Edge and a folder is held,
 * look at it every few minutes and when the window comes back to the front. Draws one line only when the seller has
 * something to do — files that stopped, or a browser that wants the folder permission granted again.
 */
export function FolderSyncRunner() {
  const { user } = useAuth();
  const orgKey = user?.orgId ?? null;
  const location = useLocation();
  const [needsPermission, setNeedsPermission] = useState<DirHandle | null>(null);
  const handleRef = useRef<DirHandle | null>(null);
  const lastRun = useRef(0);
  const status = useSyncExternalStore(folderSyncState.subscribe, () => folderSyncState.status);

  const run = useCallback(async () => {
    const handle = handleRef.current;
    if (!handle) {
      // Not held here (another browser, or not connected): only refresh what the server says.
      try {
        folderSyncState.publish(await api.getKnowledgeFolder());
      } catch {
        /* the line stays as it was */
      }
      return;
    }
    if ((await permissionOf(handle)) !== "granted") {
      setNeedsPermission(handle);
      return;
    }
    lastRun.current = Date.now();
    await exclusively(async () => {
      folderSyncState.setRunning(true);
      try {
        const listing = await listHandle(handle);
        const round = await syncRound(api, listing, "WATCH", cache);
        folderSyncState.publish(round.status, round);
      } catch {
        // A folder that moved, a network blip or another folder linked on the server: the page explains; try later.
        try {
          folderSyncState.publish(await api.getKnowledgeFolder());
        } catch {
          /* nothing to add */
        }
      } finally {
        folderSyncState.setRunning(false);
      }
      return null;
    });
  }, []);

  useEffect(() => {
    if (!orgKey) return;
    let stopped = false;
    void (async () => {
      handleRef.current = canWatchFolder() ? ((await loadHandle(orgKey)) ?? null) : null;
      if (!stopped) await run();
    })();
    folderSyncControl.runNow = async () => {
      handleRef.current = canWatchFolder() ? ((await loadHandle(orgKey)) ?? null) : null;
      setNeedsPermission(null);
      await run();
    };
    const timer = window.setInterval(() => void run(), WATCH_INTERVAL_MS);
    const onVisible = () => {
      if (document.visibilityState === "visible" && Date.now() - lastRun.current > 60_000) void run();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      folderSyncControl.runNow = null;
    };
  }, [orgKey, run]);

  if (location.pathname === "/knowledge/folder") return null;
  const held = status?.held.length ?? 0;
  if (!needsPermission && held === 0) return null;
  return (
    <div className="border-b border-line bg-canvas px-4 py-2 md:px-8" role="status" data-testid="folder-sync-line">
      <p className="flex flex-wrap items-center gap-x-3 gap-y-1 break-keep text-sm text-ink">
        {needsPermission ? (
          <>
            <span>자료 폴더를 다시 읽으려면 허용이 필요해요.</span>
            <Btn
              variant="outline"
              className="min-h-[36px] px-3 py-1.5 text-sm"
              onClick={async () => {
                const granted = (await needsPermission.requestPermission?.({ mode: "read" })) === "granted";
                if (granted) {
                  setNeedsPermission(null);
                  await run();
                }
              }}
            >
              허용하기
            </Btn>
          </>
        ) : (
          <>
            <span className="text-warn">자료 폴더에서 멈춘 파일이 {held}개 있어요.</span>
            <Link to="/knowledge/folder" className="font-semibold text-brand-700 underline underline-offset-4">
              확인하기
            </Link>
          </>
        )}
      </p>
    </div>
  );
}
