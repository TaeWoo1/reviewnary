// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MediaThumb } from "./ReviewPhotos";
import { expectNoAxeViolations } from "../../test/axe";
import type { ChannelReviewDetailView, ReviewMediaItemView } from "../../lib/types";

const api = vi.hoisted(() => ({
  getReviewMedia: vi.fn(),
  getReviewMediaImage: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api, getToken: () => null }));

import { ReviewPhotos } from "./ReviewPhotos";
import { ReviewProblemCard } from "./decision/ReviewProblemCard";

const REVIEW = "8f2c1d4e-0000-4000-8000-000000000001";
const PIXEL = "data:image/png;base64,iVBORw0KGgo=";

function item(over: Partial<ReviewMediaItemView> = {}): ReviewMediaItemView {
  return {
    ordinal: 1,
    kind: "IMAGE",
    inspected: true,
    statusKo: "Reviewnary가 사진을 확인했습니다.",
    depicts: "몰딩 끝부분이 벌어진 모습",
    problemVisible: "YES",
    problemDescription: "접착면이 떨어져 있습니다.",
    imagePath: `/api/reviews/${REVIEW}/media/1`,
    ...over,
  };
}

beforeEach(() => {
  api.getReviewMedia.mockReset();
  api.getReviewMediaImage.mockReset();
});

describe("ReviewPhotos — a review's photos on the review screen", () => {
  it("renders a thumbnail per photo from the list, with what was seen only when it was inspected", async () => {
    api.getReviewMedia.mockResolvedValue({
      items: [
        item(),
        item({
          ordinal: 2,
          inspected: false,
          statusKo: "사진 확인 기능이 꺼져 있어 사진 내용은 보지 않았습니다.",
          depicts: "보이면 안 되는 문장",
          problemVisible: null,
          problemDescription: null,
          imagePath: `/api/reviews/${REVIEW}/media/2`,
        }),
      ],
    });
    api.getReviewMediaImage.mockResolvedValue(PIXEL);
    render(<ReviewPhotos reviewId={REVIEW} mediaCount={2} mediaCountObserved />);

    const list = await screen.findByRole("list", { name: "고객이 올린 사진" });
    expect(await within(list).findByAltText("고객이 올린 사진 1")).toBeTruthy();
    expect(await within(list).findByAltText("고객이 올린 사진 2")).toBeTruthy();
    expect(api.getReviewMedia).toHaveBeenCalledWith(REVIEW);
    expect(api.getReviewMediaImage).toHaveBeenCalledWith(REVIEW, 1);
    expect(api.getReviewMediaImage).toHaveBeenCalledWith(REVIEW, 2);
    expect(screen.getByText("분석: 몰딩 끝부분이 벌어진 모습")).toBeTruthy();
    expect(screen.getByText("문제 보임")).toBeTruthy();
    expect(screen.getByText("사진 확인 기능이 꺼져 있어 사진 내용은 보지 않았습니다.")).toBeTruthy();
    expect(screen.queryByText(/보이면 안 되는 문장/)).toBeNull();
    // imagePath is for fetching only — never printed.
    expect(document.body.textContent).not.toContain("/api/reviews/");
  });

  it("labels a video in words and never asks for its bytes", async () => {
    api.getReviewMedia.mockResolvedValue({
      items: [item({ ordinal: 1, kind: "VIDEO", inspected: false, statusKo: "영상은 확인하지 않았습니다.", imagePath: null })],
    });
    render(<ReviewPhotos reviewId={REVIEW} mediaCount={1} mediaCountObserved />);
    const list = await screen.findByRole("list", { name: "고객이 올린 사진" });
    expect(within(list).getByText("영상")).toBeTruthy();
    expect(within(list).queryByRole("img")).toBeNull();
    expect(api.getReviewMediaImage).not.toHaveBeenCalled();
  });

  it("says a photo could not be loaded instead of showing a stand-in image", async () => {
    api.getReviewMedia.mockResolvedValue({ items: [item()] });
    api.getReviewMediaImage.mockRejectedValue(new Error("403"));
    render(<ReviewPhotos reviewId={REVIEW} mediaCount={1} mediaCountObserved />);
    expect(await screen.findByText("불러오기 실패")).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("says the photos could not be loaded when the list itself fails", async () => {
    api.getReviewMedia.mockRejectedValue(new Error("500"));
    render(<ReviewPhotos reviewId={REVIEW} mediaCount={3} mediaCountObserved />);
    expect(await screen.findByText(/사진을 불러오지 못했습니다/)).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
  });

  it("does not fetch when the count is a reading of 0", async () => {
    const { container } = render(<ReviewPhotos reviewId={REVIEW} mediaCount={0} mediaCountObserved />);
    await Promise.resolve();
    expect(api.getReviewMedia).not.toHaveBeenCalled();
    expect(container.textContent).toBe("");
  });

  it("asks when nobody counted, and stays silent when there is nothing or the read fails", async () => {
    api.getReviewMedia.mockRejectedValue(new Error("500"));
    const { container } = render(<ReviewPhotos reviewId={REVIEW} mediaCount={0} mediaCountObserved={false} />);
    await waitFor(() => expect(api.getReviewMedia).toHaveBeenCalledWith(REVIEW));
    await Promise.resolve();
    expect(container.textContent).toBe("");
  });
});

describe("the review screen's problem card carries the photos", () => {
  function detail(over: Partial<ChannelReviewDetailView> = {}): ChannelReviewDetailView {
    return {
      id: REVIEW,
      writtenOn: "2026-10-01",
      rating: 2,
      negative: true,
      body: "벽에서 떨어졌어요",
      bodyRedacted: false,
      productName: "합성 전선몰딩",
      mediaCount: 1,
      mediaCountObserved: true,
      textless: false,
      isNew: false,
      triage: { tier: "NEEDS_ATTENTION", reason: "2점", tags: [], recommendedAction: null },
      whyNow: null,
      aiMark: null,
      sellerCorrection: null,
      locateTarget: { productId: null, vendorItemId: null, writtenOn: null, rating: null },
      replyWork: null,
      sellerAccountId: null,
      replyUnavailableReason: null,
      ...over,
    };
  }

  it("renders the review's thumbnails when it has photos", async () => {
    api.getReviewMedia.mockResolvedValue({ items: [item()] });
    api.getReviewMediaImage.mockResolvedValue(PIXEL);
    render(<ReviewProblemCard detail={detail()} word="리뷰" />);
    expect(await screen.findByAltText("고객이 올린 사진 1")).toBeTruthy();
  });

  it("makes no media request for a review with no photos", async () => {
    render(<ReviewProblemCard detail={detail({ mediaCount: 0 })} word="리뷰" />);
    await Promise.resolve();
    expect(api.getReviewMedia).not.toHaveBeenCalled();
  });
});

describe("the enlarged view opens in the page", () => {
  async function openedThumb() {
    const user = userEvent.setup();
    const load = vi.fn().mockResolvedValue(PIXEL);
    const view = render(
      <ul>
        <MediaThumb media={item()} sourceKey={REVIEW} load={load} />
      </ul>,
    );
    const thumb = await screen.findByRole("button", { name: "고객이 올린 사진 1 크게 보기" });
    return { user, thumb, load, view };
  }

  it("opens a dialog with the larger photo, with focus on 닫기, and never links to the data: URL", async () => {
    const { user, thumb, view } = await openedThumb();
    expect(document.querySelector('a[href^="data:"]')).toBeNull();
    await user.click(thumb);
    const dialog = screen.getByRole("dialog", { name: "고객이 올린 사진 1" });
    expect(within(dialog).getByAltText("고객이 올린 사진 1").getAttribute("src")).toBe(PIXEL);
    expect(document.activeElement).toBe(within(dialog).getByRole("button", { name: "닫기" }));
    await expectNoAxeViolations(view.container);
  });

  it("closes on Esc and gives focus back to the thumbnail", async () => {
    const { user, thumb } = await openedThumb();
    await user.click(thumb);
    expect(screen.getByRole("dialog")).toBeTruthy();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(thumb);
  });

  it("closes on 닫기 and gives focus back to the thumbnail", async () => {
    const { user, thumb } = await openedThumb();
    await user.click(thumb);
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "닫기" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(document.activeElement).toBe(thumb);
  });

  it("offers no enlarged view for a photo that did not load", async () => {
    render(
      <ul>
        <MediaThumb media={item()} sourceKey={REVIEW} load={() => Promise.reject(new Error("403"))} />
      </ul>,
    );
    expect(await screen.findByText("불러오기 실패")).toBeTruthy();
    expect(screen.queryByRole("button")).toBeNull();
  });
});
