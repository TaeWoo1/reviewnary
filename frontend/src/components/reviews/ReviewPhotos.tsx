import { useCallback, useEffect, useRef, useState } from "react";
import { Btn } from "../ui/Btn";
import { api } from "../../lib/apiClient";
import { photoWord } from "../../lib/copy/customerOps";
import type { ReviewMediaItemView } from "../../lib/types";

/**
 * <b>A review's photos, where the review is read</b> (product-owner decision, 2026-10-13: photos are visible on
 * the review screens, not only on the operations-case screen).
 *
 * <p>{@link MediaThumb} is the one thumbnail both surfaces draw — moved whole from `OperationsCase` so the case and
 * the review cannot show the same photo two ways. The bytes sit behind the bearer token, so each thumbnail asks its
 * caller to load them (`load`) and renders the data: URL that comes back; `imagePath` itself is never shown.
 */

/** What a thumbnail needs — the case's media rows and the review's media rows both carry it. */
export type MediaThumbItem = Pick<
  ReviewMediaItemView,
  "ordinal" | "inspected" | "statusKo" | "depicts" | "problemVisible" | "problemDescription" | "imagePath"
>;

/**
 * One photo as a thumbnail with what Reviewnary saw under it. A photo that was not inspected never gets a
 * description — only its status sentence.
 *
 * <p>`sourceKey` names whose photo this is (a case id, a review id) and is what re-runs the load; `load` itself is
 * read through a ref so a caller's inline arrow does not refetch on every render.
 *
 * <p><b>The larger view is in the page</b> (2026-10-13). The thumbnail used to be a link to its own data: URL in a
 * new tab, and Chrome refuses a top-level navigation to data: — so the press opened nothing. It now opens
 * {@link PhotoViewer} over the page; Esc or 닫기 closes it and focus goes back to the thumbnail that opened it.
 */
export function MediaThumb({
  media,
  sourceKey,
  load,
}: {
  media: MediaThumbItem;
  sourceKey: string;
  load: () => Promise<string>;
}) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [viewing, setViewing] = useState(false);
  const thumbRef = useRef<HTMLButtonElement>(null);
  const loadRef = useRef(load);
  loadRef.current = load;
  useEffect(() => {
    if (!media.imagePath) return;
    let alive = true;
    setSrc(null);
    setFailed(false);
    Promise.resolve()
      .then(() => loadRef.current())
      .then((url) => alive && setSrc(url))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [sourceKey, media.imagePath, media.ordinal]);
  const close = useCallback(() => {
    setViewing(false);
    thumbRef.current?.focus();
  }, []);
  const word = media.inspected ? photoWord(media.problemVisible) : null;
  const hit = media.inspected && media.problemVisible === "YES";
  const alt = `고객이 올린 사진 ${media.ordinal}`;
  return (
    <li className="w-[168px]">
      <span
        className={`relative block h-[112px] w-[168px] overflow-hidden rounded-xl bg-canvas ${
          hit ? "ring-2 ring-warn ring-offset-2" : ""
        }`}
      >
        {src ? (
          <button
            ref={thumbRef}
            type="button"
            aria-label={`${alt} 크게 보기`}
            onClick={() => setViewing(true)}
            className="block h-full w-full focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700"
          >
            <img src={src} alt={alt} className="h-full w-full object-cover" />
          </button>
        ) : (
          <span className="flex h-full items-center justify-center px-2 text-center text-xs text-muted">
            {media.imagePath ? (failed ? "불러오기 실패" : "불러오는 중") : "영상"}
          </span>
        )}
        {word ? (
          <span className={`absolute bottom-1.5 left-1.5 rounded bg-surface px-1.5 text-xs font-bold ${hit ? "text-warn" : "text-muted"}`}>
            {word}
          </span>
        ) : null}
      </span>
      {media.inspected && media.depicts ? <p className="mt-1.5 break-keep text-xs leading-snug text-muted">분석: {media.depicts}</p> : null}
      {media.inspected && media.problemDescription ? (
        <p className="mt-0.5 break-keep text-xs leading-snug text-ink">{media.problemDescription}</p>
      ) : null}
      {!media.inspected ? <p className="mt-1.5 break-keep text-xs leading-snug text-muted">{media.statusKo}</p> : null}
      {viewing && src ? <PhotoViewer src={src} alt={alt} onClose={close} /> : null}
    </li>
  );
}

/**
 * The enlarged photo, over the page — the same overlay grammar as the mobile menu (`MoreDrawer`): a scrim, a
 * `role="dialog"` surface with the floating shadow (§4: shadow only on what floats), focus on 닫기 when it
 * opens, Esc and the scrim close it. Focus is held on 닫기 while it is open — it is the only control there.
 */
function PhotoViewer({ src, alt, onClose }: { src: string; alt: string; onClose: () => void }) {
  const panelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const closeButton = panelRef.current?.querySelector<HTMLButtonElement>("button");
    closeButton?.focus();
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      } else if (event.key === "Tab") {
        event.preventDefault();
        closeButton?.focus();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div aria-hidden="true" onClick={onClose} className="absolute inset-0 bg-ink/10" />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={alt}
        className="relative flex max-h-full max-w-full flex-col gap-3 rounded-2xl bg-surface p-4 shadow-card"
      >
        <div className="flex justify-end">
          <Btn size="sm" variant="outline" onClick={onClose}>
            닫기
          </Btn>
        </div>
        <img src={src} alt={alt} className="max-h-[80vh] max-w-[min(960px,calc(100vw-64px))] rounded-xl object-contain" />
      </div>
    </div>
  );
}

type ListState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "failed" }
  | { kind: "loaded"; items: ReviewMediaItemView[] };

/**
 * The photos of one review, read when this block is on screen and not before.
 *
 * <p><b>When it asks.</b> A review whose count is a reading of 0 (`mediaCountObserved` with 0) has nothing to
 * fetch, and no request is made. A count above 0 is fetched and every state is said out loud — loading, could not
 * load, recorded-but-not-retrieved — because the seller was told photos exist. A count nobody took
 * (`mediaCountObserved === false`) is asked about too, since the server may hold photos the count never saw, but it
 * speaks only when photos come back: in that case a failure or an empty list is silence, never 「사진 없음」 and never
 * an apology for photos that were probably never there.
 *
 * <p>Never a placeholder image: a photo that could not be loaded says so in words.
 */
export function ReviewPhotos({
  reviewId,
  mediaCount,
  mediaCountObserved,
}: {
  reviewId: string;
  mediaCount: number;
  mediaCountObserved: boolean | undefined;
}) {
  const announced = mediaCount > 0;
  const shouldAsk = announced || mediaCountObserved === false;
  const [state, setState] = useState<ListState>({ kind: "idle" });
  useEffect(() => {
    if (!shouldAsk) {
      setState({ kind: "idle" });
      return;
    }
    let alive = true;
    setState({ kind: "loading" });
    Promise.resolve()
      .then(() => api.getReviewMedia(reviewId))
      .then((list) => alive && setState({ kind: "loaded", items: Array.isArray(list?.items) ? list.items : [] }))
      .catch(() => alive && setState({ kind: "failed" }));
    return () => {
      alive = false;
    };
  }, [reviewId, shouldAsk]);

  if (!shouldAsk || state.kind === "idle") return null;
  if (state.kind === "loading") {
    return announced ? (
      <p className="text-sm text-muted" role="status">
        사진을 불러오는 중입니다
      </p>
    ) : null;
  }
  if (state.kind === "failed") {
    return announced ? <p className="break-keep text-sm text-muted">사진을 불러오지 못했습니다. 잠시 후 다시 열어 주세요.</p> : null;
  }
  if (state.items.length === 0) {
    return announced ? (
      <p className="break-keep text-sm text-muted">
        채널에 사진·영상 {mediaCount}개가 있다고 기록돼 있지만, 아직 가져오지 않았습니다.
      </p>
    ) : null;
  }
  return (
    <ul className="flex flex-wrap gap-3" aria-label="고객이 올린 사진">
      {state.items.map((m) => (
        <MediaThumb
          key={m.ordinal}
          media={m}
          sourceKey={reviewId}
          load={() => api.getReviewMediaImage(reviewId, m.ordinal)}
        />
      ))}
    </ul>
  );
}
