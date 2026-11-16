import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Btn } from "../../components/ui/Btn";
import { PageHead } from "../../components/ui/PageHead";
import { Status } from "../../components/ui/Status";
import { Disclosure } from "../../components/ui/Disclosure";
import { backendMessage } from "../../components/connect/channelShared";
import { folderSyncControl } from "../../components/knowledge/FolderSyncRunner";
import { api } from "../../lib/apiClient";
import { useAuth } from "../../lib/auth";
import {
  HELD_REASON_WORD,
  canWatchFolder,
  folderSyncState,
  forgetHandle,
  listHandle,
  listPicked,
  loadHandle,
  pickFolder,
  saveHandle,
  syncRound,
  type DirHandle,
  type FolderFileView,
  type FolderListing,
  type HashCache,
  type RoundSummary,
} from "../../lib/folderSync";
import { isAxiosError } from "axios";

const pickedCache: HashCache = new Map();

function when(iso: string | null): string {
  if (!iso) return "아직 없음";
  return new Date(iso).toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit" });
}

/**
 * 자료 폴더 동기화 (owner decision 2026-11-17). Chrome / Edge: connect a folder once and an open window keeps it in
 * step. Other browsers: send the folder whole; only what changed is kept. Stopped files are listed with why, and the
 * seller can choose the folder's copy where that is safe.
 */
export function KnowledgeFolder() {
  const { user } = useAuth();
  const orgKey = user?.orgId ?? null;
  const watch = canWatchFolder();
  const status = useSyncExternalStore(folderSyncState.subscribe, () => folderSyncState.status);
  const round = useSyncExternalStore(folderSyncState.subscribe, () => folderSyncState.lastRound);
  const running = useSyncExternalStore(folderSyncState.subscribe, () => folderSyncState.running);
  const [held, setHeld] = useState<DirHandle | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mismatch, setMismatch] = useState<{ listing: FolderListing; mode: "WATCH" | "UPLOAD"; handle?: DirHandle } | null>(null);
  const pickRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    void api.getKnowledgeFolder().then((s) => folderSyncState.publish(s)).catch(() => setError("폴더 상태를 불러오지 못했어요."));
    if (watch && orgKey) void loadHandle(orgKey).then((h) => setHeld(h ?? null));
  }, [watch, orgKey]);

  /** First round on a newly picked folder. A different folder already linked asks before replacing it. */
  const firstRound = async (listing: FolderListing, mode: "WATCH" | "UPLOAD", handle?: DirHandle, replace = false) => {
    setBusy(true);
    setError(null);
    try {
      const r: RoundSummary = await syncRound(api, listing, mode, mode === "WATCH" ? new Map() : pickedCache, replace);
      if (handle && orgKey) {
        await saveHandle(orgKey, handle);
        setHeld(handle);
      }
      folderSyncState.publish(r.status, r);
      setMismatch(null);
    } catch (e) {
      if (isAxiosError(e) && (e.response?.data as { code?: string } | undefined)?.code === "FOLDER_MISMATCH") {
        setMismatch({ listing, mode, handle });
        setError(backendMessage(e));
      } else {
        setError(backendMessage(e) ?? "폴더를 읽지 못했어요. 다시 시도해 주세요.");
      }
    } finally {
      setBusy(false);
    }
  };

  const connect = async () => {
    let handle: DirHandle;
    try {
      handle = await pickFolder();
    } catch {
      return; // the seller closed the picker
    }
    await firstRound(await listHandle(handle), "WATCH", handle);
  };

  const disconnect = async () => {
    if (orgKey) await forgetHandle(orgKey);
    setHeld(null);
    try {
      folderSyncState.publish(await api.unlinkKnowledgeFolder(), null);
    } catch (e) {
      setError(backendMessage(e) ?? "연결을 끊지 못했어요.");
    }
  };

  const useFolderCopy = async (f: FolderFileView) => {
    setBusy(true);
    setError(null);
    try {
      folderSyncState.publish(await api.useKnowledgeFolderCopy(f.id));
      if (held && folderSyncControl.runNow) await folderSyncControl.runNow();
    } catch (e) {
      setError(backendMessage(e) ?? "바꾸지 못했어요.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHead
        title="자료 폴더 동기화"
        description="매뉴얼을 모아 둔 PC 폴더를 연결하면, 바뀐 파일이 새 판으로 올라가요. 문제가 있는 파일만 멈추고 알려 드려요."
      />
      <div className="max-w-[760px] space-y-6">
        <section aria-labelledby="fs-link" className="space-y-3 rounded-xl border border-line bg-surface px-4 py-4">
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <h2 id="fs-link" className="text-section font-bold text-ink">
              연결한 폴더
            </h2>
            {status?.linked ? <Status tone="good">{status.folderName}</Status> : <Status tone="neutral">연결 안 됨</Status>}
          </div>
          {status?.linked ? (
            <p className="break-keep text-sm text-muted" data-testid="fs-summary">
              자료 {status.synced}개가 폴더와 같아요. 마지막 확인 {when(status.lastScanAt)}
              {status.mode === "UPLOAD" ? " (폴더째 올리기)" : ""}.
            </p>
          ) : null}
          {watch ? (
            <>
              <p className="break-keep text-sm text-muted">
                {held
                  ? "이 브라우저가 폴더를 기억하고 있어요. 리뷰너리 창이 열려 있는 동안 3분마다 확인하고, 창을 닫았다 열면 그 사이 바뀐 것도 올려요."
                  : "크롬 · 엣지에서는 폴더를 한 번 연결해 두면 창이 열려 있는 동안 알아서 확인해요. 브라우저가 묻는 「방문할 때마다 허용」을 고르면 다음에 다시 묻지 않아요."}
              </p>
              <div className="flex flex-wrap gap-2">
                {held ? (
                  <>
                    <Btn disabled={busy || running} onClick={() => void folderSyncControl.runNow?.()}>
                      {running ? "확인하는 중…" : "지금 확인"}
                    </Btn>
                    <Btn variant="outline" disabled={busy} onClick={() => void connect()}>
                      다른 폴더 연결
                    </Btn>
                    <Btn variant="ghost" disabled={busy} onClick={() => void disconnect()}>
                      연결 끊기
                    </Btn>
                  </>
                ) : (
                  <Btn disabled={busy} onClick={() => void connect()} data-testid="fs-connect">
                    {busy ? "읽는 중…" : "폴더 연결하기"}
                  </Btn>
                )}
              </div>
            </>
          ) : (
            <>
              <p className="break-keep text-sm text-muted">
                이 브라우저는 폴더를 기억하지 못해요. 자료가 바뀌면 폴더를 다시 골라 올려 주세요. 바뀐 파일만 새 판이 돼요.
                (크롬 · 엣지에서는 한 번 연결로 계속 맞춰져요.)
              </p>
              <input
                ref={pickRef}
                type="file"
                className="sr-only"
                aria-label="올릴 폴더"
                data-testid="fs-pick"
                {...({ webkitdirectory: "", directory: "" } as Record<string, string>)}
                multiple
                onChange={(e) => {
                  const listing = e.target.files ? listPicked(e.target.files) : null;
                  e.target.value = "";
                  if (listing) void firstRound(listing, "UPLOAD");
                }}
              />
              <Btn disabled={busy} onClick={() => pickRef.current?.click()}>
                {busy ? "올리는 중…" : "폴더째 올리기"}
              </Btn>
            </>
          )}
          {mismatch ? (
            <div className="flex flex-wrap gap-2">
              <Btn
                variant="outline"
                disabled={busy}
                onClick={() => void firstRound(mismatch.listing, mismatch.mode, mismatch.handle, true)}
              >
                「{mismatch.listing.name}」 폴더로 바꾸기
              </Btn>
              <Btn variant="ghost" onClick={() => { setMismatch(null); setError(null); }}>
                그대로 두기
              </Btn>
            </div>
          ) : null}
          {error ? <p className="break-keep text-sm text-warn" role="alert">{error}</p> : null}
          {round ? (
            <p className="break-keep text-sm text-ink" data-testid="fs-round">
              마지막 확인 결과: 올림 {round.uploaded}개, 사용 중지 {round.scan.deactivated}개, 다시 켬 {round.scan.reactivated}개
              {round.failed > 0 ? `, 보내지 못함 ${round.failed}개(다음 확인 때 다시 보내요)` : ""}.
              {round.scan.warning === "EMPTY_FOLDER"
                ? " 폴더가 비어 보여서 아무 자료도 끄지 않았어요. 폴더 위치가 바뀌었는지 확인해 주세요."
                : ""}
            </p>
          ) : null}
        </section>

        {status && status.held.length > 0 ? (
          <section aria-labelledby="fs-held" className="space-y-2">
            <h2 id="fs-held" className="text-section font-bold text-ink">
              멈춘 파일 {status.held.length}개
            </h2>
            <p className="break-keep text-sm text-muted">이 파일들만 멈췄고, 나머지는 계속 맞춰져요. 파일을 고쳐 저장하면 다음 확인 때 다시 올려요.</p>
            <ul className="divide-y divide-line rounded-xl border border-line bg-surface" data-testid="fs-held">
              {status.held.map((f) => (
                <li key={f.id} className="space-y-1 px-4 py-3">
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                    <span className="break-all font-semibold text-ink">{f.path}</span>
                    <Status tone="warn">{HELD_REASON_WORD[f.reason ?? ""] ?? "확인 필요"}</Status>
                  </div>
                  {f.message ? <p className="break-keep text-sm text-muted">{f.message}</p> : null}
                  {f.canUseFolder ? (
                    <Btn variant="outline" disabled={busy} onClick={() => void useFolderCopy(f)}>
                      폴더 파일로 덮어쓰기
                    </Btn>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {status && status.missing.length > 0 ? (
          <section aria-labelledby="fs-missing" className="space-y-2">
            <h2 id="fs-missing" className="text-section font-bold text-ink">
              폴더에서 빠져 사용 중지한 자료 {status.missing.length}개
            </h2>
            <p className="break-keep text-sm text-muted">지우지 않았어요. 파일을 폴더에 다시 넣으면 다시 켜져요.</p>
            <ul className="divide-y divide-line rounded-xl border border-line bg-surface" data-testid="fs-missing">
              {status.missing.map((f) => (
                <li key={f.id} className="px-4 py-3">
                  <span className="break-all text-ink">{f.path}</span>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <Disclosure label="폴더는 이렇게 읽어요">
          <ul className="mt-2 space-y-2 break-keep text-sm text-ink">
            <li>폴더 맨 위에 둔 파일은 회사 전체 자료가 돼요.</li>
            <li>
              하위 폴더 이름이 상품 이름과 똑같으면 그 상품의 자료가 돼요. 이름이 다르면 「상품 확인 필요」로 멈추고, 회사
              전체 자료로 올리지 않아요.
            </li>
            <li>바뀐 파일은 같은 자료의 새 판이 되고, 이전 판은 이력으로 남아요.</li>
            <li>폴더에서 지운 파일의 자료는 지우지 않고 사용 중지해요.</li>
            <li>PDF · DOCX · TXT · MD · CSV를 읽어요. 사진, 엑셀, 숨김 파일은 건너뛰어요.</li>
            <li>PC의 폴더 위치는 서버에 보내지 않아요. 폴더 안의 파일 이름과 내용만 올려요.</li>
          </ul>
        </Disclosure>
      </div>
    </>
  );
}
