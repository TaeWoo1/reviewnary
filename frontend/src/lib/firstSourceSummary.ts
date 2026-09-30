import type { AcquisitionPathView, ChannelCoverageRowView, SyncRunView } from "./types";

/**
 * <b>「무엇을 가져왔는지」 — the sentence a first connection owes the seller.</b>
 *
 * <p>Disconnected Channel Onboarding Live Walkthrough v1 §6/§7. A connection screen that ends at
 * 「연결 완료」 has told the seller about our plumbing and nothing about their shop. This turns the two
 * facts we already hold into one line per data type.
 *
 * <p><b>The state decides the sentence; the run decides the number.</b> Those are different questions
 * and this module keeps them apart on purpose:
 *
 * <ul>
 *   <li><b>State</b> comes from {@code ChannelDataState}, which already separates the four ways a
 *       channel can be quiet — a measured zero, an unproven silence, a channel that does not offer
 *       this at all, and a channel that is blocked. Only {@code ZERO} may be written as 「없습니다」;
 *       every other quiet state gets words that admit we do not know.</li>
 *   <li><b>Number</b> comes from a terminal sync run's {@code successRows} — what the CHANNEL just
 *       handed over. The coverage row's {@code rows} is not used for this: it counts everything the
 *       org holds, seeded rows included, and a seeded row has never been handed over by anyone. A
 *       count printed under 「가져왔습니다」 must be unable to include one.</li>
 * </ul>
 *
 * <p>Nothing here calls a channel, and nothing here can start a collection.
 */

/** The operator data types, in the order a seller reads them. Mirrors `ChannelCoverageService.DATA_TYPES`. */
export const SUMMARY_DATA_TYPES = ["ORDER_SUMMARY", "INQUIRY", "REVIEW"] as const;

export const DATA_TYPE_LABEL: Record<string, string> = {
  ORDER_SUMMARY: "주문",
  INQUIRY: "문의",
  REVIEW: "리뷰",
};

/**
 * How a line reads. `collected` is the only one that carries a number; `pending` and `blocked` are the
 * two shapes of "we cannot say", kept apart because the seller can act on one of them.
 */
export type SummaryTone = "collected" | "empty" | "pending" | "blocked" | "unsupported";

export interface SourceSummaryLine {
  readonly dataType: string;
  readonly label: string;
  readonly tone: SummaryTone;
  /** What this channel handed over in this connection's own run — null when no run has finished. */
  readonly count: number | null;
  readonly sentence: string;
}

/** The newest terminal run for one data type on this account, if the channel has finished one. */
function terminalRun(runs: readonly SyncRunView[], dataType: string): SyncRunView | null {
  const done = runs.filter(
    (run) => run.dataType === dataType && (run.status === "SUCCESS" || run.status === "PARTIAL"),
  );
  if (done.length === 0) return null;
  // Newest first by finish time; a run with no finish time cannot be the newest terminal one.
  return done
    .slice()
    .sort((a, b) => (b.finishedAt ?? "").localeCompare(a.finishedAt ?? ""))[0];
}

function lineFor(
  row: ChannelCoverageRowView,
  run: SyncRunView | null,
  paths: readonly AcquisitionPathView[],
): SourceSummaryLine {
  const label = DATA_TYPE_LABEL[row.dataType] ?? row.dataType;
  const base = { dataType: row.dataType, label } as const;

  if (row.state === "NOT_SUPPORTED") {
    // Not a failure and not an empty result. Saying 「0건」 here would describe the seller's shop
    // using a fact about the channel's API.
    //
    // <b>「제공하지 않습니다」was the sentence that made a connected channel read as half-connected.</b>
    // `NOT_SUPPORTED` is a fact about the PULL CONNECTOR — 「이 채널의 API로는 이 자료를 못 가져온다」 —
    // and it was printed as 「이 채널에서 제공하지 않습니다」, which a seller reads as 「이 채널에는 리뷰가
    // 없다」. NAVER 리뷰가 그 경우다: API는 없지만 판매자센터 내보내기로 정식 수집하며, 데모 org의 리뷰
    // 3,858건이 전부 그 길로 들어왔다. 주문 한 줄만 숫자를 달고 리뷰 줄이 「제공하지 않습니다」라고 말하면
    // 화면 전체가 「주문만 연결됐다」로 읽힌다.
    //
    // 그래서 이 줄은 <b>커넥터 사실이 아니라 취득 경로</b>를 말한다. 경로는 채널 상세 화면이 이미 읽는
    // 같은 출처(`ChannelCapabilityOverview.dataTypes[].acquisitionPaths`)에서 오므로 두 화면이 같은
    // 채널을 다르게 설명할 수 없다. 경로를 읽지 못했으면 이 제품이 아는 가장 약한 문장만 쓴다.
    return { ...base, tone: "unsupported", count: null, sentence: unsupportedSentence(label, paths) };
  }
  if (row.state === "BLOCKED") {
    return { ...base, tone: "blocked", count: null, sentence: `${label}를 가져오지 못하고 있습니다. 연결을 다시 확인해 주세요.` };
  }
  if (row.state === "NOT_CONNECTED") {
    return { ...base, tone: "pending", count: null, sentence: `${label}는 아직 연결되지 않았습니다.` };
  }
  if (row.state === "ZERO") {
    // The only honest 「없습니다」 in this file: collection is running and it found nothing.
    return { ...base, tone: "empty", count: 0, sentence: `확인된 ${label}가 없습니다.` };
  }
  // OBSERVED_FRESH / OBSERVED_FRESHNESS_UNPROVEN. A finished run gives a number, and the verb has to
  // match what the number counts: `successRows` is what THIS run brought in, not what the channel
  // holds. Read on a first connection those are the same thing; read again a week later they are not,
  // and 「문의 0건을 확인했습니다」 appeared on a completion screen for an org holding two of them
  // (observed, Demo Org, 2026-08-27). 「가져왔습니다」 is true in both readings.
  if (run) {
    if (run.successRows > 0) {
      return { ...base, tone: "collected", count: run.successRows, sentence: `${label} ${run.successRows}건을 가져왔습니다.` };
    }
    // A finished run that brought nothing back. That is a fact about the collection, not about the
    // shop — it may not become 「문의가 없습니다」, which is a claim only ZERO gets to make.
    return { ...base, tone: "empty", count: 0, sentence: `새로 가져온 ${label}는 없습니다.` };
  }
  return { ...base, tone: "pending", count: null, sentence: `${label}는 아직 확인하지 못했습니다.` };
}

/**
 * The lines for one channel, in reading order.
 *
 * @param coverage every coverage row this org has (the endpoint returns all visible channels)
 * @param runs sync runs for the account just connected — the only source of a printed number
 * @param channelCode which channel this summary is about
 */
export function sourceSummaryLines(
  coverage: readonly ChannelCoverageRowView[],
  runs: readonly SyncRunView[],
  channelCode: string,
  acquisitionPaths?: ReadonlyMap<string, readonly AcquisitionPathView[]>,
): SourceSummaryLine[] {
  const byType = new Map<string, ChannelCoverageRowView>();
  for (const row of coverage) {
    if (row.channelCode === channelCode) byType.set(row.dataType, row);
  }
  const out: SourceSummaryLine[] = [];
  for (const dataType of SUMMARY_DATA_TYPES) {
    const row = byType.get(dataType);
    if (!row) continue; // the backend did not describe this type; inventing a line would invent a fact
    out.push(lineFor(row, terminalRun(runs, dataType), acquisitionPaths?.get(dataType) ?? []));
  }
  return out;
}

/**
 * How this channel actually acquires a type its pull connector cannot serve.
 *
 * <p>Three routes, and they are different promises to the seller: a window they open, an export they
 * download, or a file they upload. A missing or unrecognised route falls back to the weakest sentence
 * this product can stand behind — never to 「제공하지 않습니다」, which is the claim that started this.
 */
function unsupportedSentence(label: string, paths: readonly AcquisitionPathView[]): string {
  const not_auto = `${label}는 자동 수집 대상이 아닙니다.`;
  if (paths.some((p) => p.method === "ACTION_WINDOW")) {
    return `${not_auto} 판매자센터 화면에서 직접 실행해 가져옵니다.`;
  }
  if (paths.some((p) => p.method === "EXPORT")) {
    return `${not_auto} 판매자센터에서 내려받은 파일을 올리면 같은 형태로 정리해 드립니다.`;
  }
  return `${not_auto} 자료 업로드로 채울 수 있습니다.`;
}

/**
 * The one sentence over the lines.
 *
 * <p>Arithmetic over the lines below it, never a claim of its own — and it never adds two counts
 * together: 「문의 22」 and 「리뷰 133」 are two facts, and 「155건」 is a third one nobody read.
 */
export function sourceSummaryHeadline(channelNameKo: string, lines: readonly SourceSummaryLine[]): string {
  const collected = lines.filter((line) => line.tone === "collected");
  if (collected.length > 0) {
    return `${channelNameKo}에서 다음 정보를 가져왔습니다.`;
  }
  if (lines.some((line) => line.tone === "blocked")) {
    return `${channelNameKo} 연결은 되었지만, 아직 가져오지 못한 정보가 있습니다.`;
  }
  return `${channelNameKo} 연결이 완료되었습니다. 정보는 순서대로 확인합니다.`;
}

/**
 * <b>「앞으로 무엇이 자동으로 들어오는가」 — 채널마다 다르므로 채널마다 다르게 말한다.</b>
 *
 * <p>이 자리의 문장은 고정이었다: 「앞으로는 새 주문·문의·리뷰를 알아서 확인해 정리해 드립니다.」 세 가지를
 * 이름으로 부르면서, 그중 하나가 자동 수집 대상이 아닌 채널에서도 똑같이 약속했다. NAVER가 그 경우다 —
 * 리뷰는 판매자가 내보내기를 해야 들어오는데 화면은 알아서 해준다고 말했고, 바로 아래 줄에서는 같은 리뷰를
 * 두고 「제공하지 않습니다」라고 말했다. 한 화면이 한 자료에 대해 서로 반대되는 두 문장을 갖고 있었던 것이
 * 「주문만 연결된 것 같다」로 읽힌 이유다.
 *
 * <p>그래서 이 문장은 <b>줄들에서 계산</b>된다. 자동으로 들어오는 것만 자동이라 부르고, 판매자가 가져와야
 * 하는 것은 그렇게 말한다. 아무것도 자동이 아니면 자동이라는 말을 쓰지 않는다.
 */
export function sourceSummarySubtitle(lines: readonly SourceSummaryLine[]): string {
  // `unsupported`가 「자동 경로가 없다」를 뜻하는 유일한 tone이다. `pending`(아직 수집이 끝나지 않았다)과
  // `blocked`(막혀 있고, 자기 줄에서 그렇게 말한다)는 둘 다 경로가 <b>있는</b> 상태이고, 첫 연결 화면에서
  // 앞을 보는 약속이 가장 필요한 쪽이 바로 `pending`이다 — 아직 아무것도 안 들어왔으니까.
  const automatic = lines.filter((line) => line.tone !== "unsupported");
  const sellerRun = lines.filter((line) => line.tone === "unsupported");
  const names = (rows: readonly SourceSummaryLine[]) => rows.map((r) => r.label).join("·");

  const first =
    automatic.length > 0
      ? `앞으로는 새 ${names(automatic)}${objectParticle(names(automatic))} 알아서 확인해 정리해 드립니다.`
      : "가져온 자료는 채널이 달라도 같은 형태로 정리해 드립니다.";
  // 판매자가 해야 하는 일은 약속 뒤에 붙인다 — 약속 안에 섞으면 그것도 자동인 줄로 읽힌다.
  return sellerRun.length > 0
    ? `${first} ${names(sellerRun)}${topicParticle(names(sellerRun))} 판매자센터에서 가져오실 때 함께 정리해 드립니다.`
    : first;
}

/**
 * 조사는 단어에 맞춘다.
 *
 * <p>이 자리의 문장은 고정이었고(「새 주문·문의·리뷰를」), 목록에서 만들기 시작하는 순간 조사가 틀린다 —
 * 「주문」으로 끝나면 「주문을」, 「리뷰」로 끝나면 「리뷰를」. 종성이 있는지는 한글 음절 코드로 정확히
 * 결정되므로 추측할 일이 아니다. 한글이 아닌 끝 글자에는 손대지 않는다(「를/는」이 덜 틀린다).
 */
function hasFinalConsonant(word: string): boolean {
  // `String.prototype.at` is outside this project's configured lib target; indexing is.
  if (word.length === 0) return false;
  const code = word.charCodeAt(word.length - 1);
  if (code < 0xac00 || code > 0xd7a3) return false;
  return (code - 0xac00) % 28 !== 0;
}

function objectParticle(word: string): string {
  return hasFinalConsonant(word) ? "을" : "를";
}

function topicParticle(word: string): string {
  return hasFinalConsonant(word) ? "은" : "는";
}
