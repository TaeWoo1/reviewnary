import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../lib/apiClient";
import type { TalkTalkSummary } from "../../lib/talktalk";

/**
 * <b>톡톡 on the home</b> (review item 8). The home's tiles count inquiries and reviews; TalkTalk conversations are a
 * separate count, per conversation, and this line says so — so the home never reads as 「all CS is handled」 while
 * TalkTalk conversations wait for a person. Shown only once TalkTalk is connected or has something waiting.
 */
export function TalkTalkHomeLine({ refresh = 0 }: { refresh?: number }) {
  const [s, setS] = useState<TalkTalkSummary | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    api
      .getTalkTalkSummary()
      .then((v) => {
        if (!alive) return;
        setS(v);
        setFailed(false);
      })
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [refresh]);

  if (failed) {
    return (
      <p className="break-keep text-sm text-warn" data-testid="cs-talktalk">
        톡톡 상담 현황을 불러오지 못했어요. 위 숫자에는 톡톡이 들어가지 않아요.
      </p>
    );
  }
  if (!s || (!s.connected && s.needsHumanConversations === 0 && s.unknownSends === 0)) {
    return null;
  }
  return (
    <div
      className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-xl border border-line bg-surface px-4 py-3 text-sm"
      data-testid="cs-talktalk"
    >
      <Link to="/talktalk" className="font-semibold text-brand-700 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
        톡톡 상담
      </Link>
      <span className={s.needsHumanConversations > 0 ? "font-semibold text-warn" : "text-muted"}>
        사람 확인 대기 대화 <span className="tabular-nums">{s.needsHumanConversations}</span>개
      </span>
      {s.unknownSends > 0 ? (
        <span className="font-semibold text-warn">
          결과를 모르는 발신 <span className="tabular-nums">{s.unknownSends}</span>건
        </span>
      ) : null}
      {s.queuedSends > 0 ? (
        <span className="text-muted">
          보내는 중 <span className="tabular-nums">{s.queuedSends}</span>건
        </span>
      ) : null}
      {s.fake ? <span className="text-muted">모의 전송</span> : null}
      <span className="w-full break-keep text-muted md:w-auto">{s.notice}</span>
    </div>
  );
}
