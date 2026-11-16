// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import type { KnowledgeDocumentView } from "../../lib/types";

const api = vi.hoisted(() => ({
  replaceKnowledgeDocumentFile: vi.fn(),
  setKnowledgeDocumentActive: vi.fn(),
  setKnowledgeEffectiveFrom: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api }));

import { KnowledgeDocumentList } from "./KnowledgeDocuments";

function doc(over: Partial<KnowledgeDocumentView> = {}): KnowledgeDocumentView {
  return {
    sourceId: "s-1", scope: "ORG", productId: null, productName: null, fileName: "01_배송정책.pdf", title: "01_배송정책",
    kind: "SHIPPING_POLICY", active: true, passages: 3, uploadedBy: "판매자", uploadedAt: "2026-11-15T21:00:00Z",
    effectiveFrom: null, version: 1, updatedAt: "2026-11-15T21:00:00Z", ...over,
  };
}

describe("파일 바꾸기 (2026-11-16)", () => {
  beforeEach(() => vi.resetAllMocks());

  it("sends the chosen file for THIS document and refreshes the list", async () => {
    api.replaceKnowledgeDocumentFile.mockResolvedValue(doc({ version: 2, fileName: "01_배송정책_개정.pdf" }));
    const onChanged = vi.fn();
    render(<KnowledgeDocumentList documents={[doc()]} onChanged={onChanged} />);
    expect(screen.getByRole("button", { name: "파일 바꾸기" })).toBeTruthy();
    const file = new File(["배송비는 3,000원입니다."], "01_배송정책_개정.pdf", { type: "application/pdf" });
    fireEvent.change(screen.getByLabelText("01_배송정책.pdf 새 파일"), { target: { files: [file] } });
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(api.replaceKnowledgeDocumentFile).toHaveBeenCalledWith("s-1", file);
    expect(screen.getByTestId("knowledge-document-replaced").textContent).toContain("2판");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("a refused file says why and that the old document is unchanged; the list is not refreshed as if it worked", async () => {
    api.replaceKnowledgeDocumentFile.mockRejectedValue(
      Object.assign(new Error("400"), { isAxiosError: true, response: { data: { message: "암호가 걸린 PDF는 읽을 수 없습니다." } } }),
    );
    const onChanged = vi.fn();
    render(<KnowledgeDocumentList documents={[doc()]} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("01_배송정책.pdf 새 파일"), { target: { files: [new File(["x"], "a.pdf")] } });
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("암호가 걸린 PDF");
    expect(alert.textContent).toContain("기존 자료는 그대로");
    expect(onChanged).not.toHaveBeenCalled();
    expect(screen.queryByTestId("knowledge-document-replaced")).toBeNull();
  });

  it("shows the revision once a file has been replaced, and nothing extra before", () => {
    const { rerender } = render(<KnowledgeDocumentList documents={[doc()]} onChanged={() => {}} />);
    expect(screen.queryByTestId("knowledge-document-revision")).toBeNull();
    rerender(<KnowledgeDocumentList documents={[doc({ version: 2, updatedAt: "2026-11-16T01:00:00Z" })]} onChanged={() => {}} />);
    expect(screen.getByTestId("knowledge-document-revision").textContent).toContain("2판");
  });

  it("replaced (200) but the list could not be re-read: says the file WAS replaced, never «기존 자료는 그대로»", async () => {
    api.replaceKnowledgeDocumentFile.mockResolvedValue(doc({ version: 2, fileName: "01_배송정책_개정.pdf" }));
    const onChanged = vi.fn().mockRejectedValue(new Error("network"));
    render(<KnowledgeDocumentList documents={[doc()]} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("01_배송정책.pdf 새 파일"), { target: { files: [new File(["x"], "01_배송정책_개정.pdf")] } });
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("파일은 바뀌었어요");
    expect(alert.textContent).toContain("새로고침");
    expect(alert.textContent).not.toContain("기존 자료는 그대로");
    expect(alert.textContent).not.toContain("바꾸지 못했어요");
    expect(screen.getByTestId("knowledge-document-replaced").textContent).toContain("2판");
    await waitFor(() => expect(screen.getAllByRole("button", { name: "파일 바꾸기" })[0].hasAttribute("disabled")).toBe(false));
  });

  it("while one row's file is being replaced, no other row can start a replace or a 사용 change", async () => {
    let finish: (v: KnowledgeDocumentView) => void = () => {};
    api.replaceKnowledgeDocumentFile.mockReturnValue(new Promise((r) => { finish = r; }));
    render(<KnowledgeDocumentList documents={[doc(), doc({ sourceId: "s-2", fileName: "02_교환반품정책.docx" })]} onChanged={() => {}} />);
    fireEvent.change(screen.getByLabelText("01_배송정책.pdf 새 파일"), { target: { files: [new File(["x"], "a.pdf")] } });
    await waitFor(() => expect(screen.getAllByRole("button", { name: "파일 바꾸기" }).every((b) => b.hasAttribute("disabled"))).toBe(true));
    expect(screen.getAllByRole("button", { name: "쓰지 않기" }).every((b) => b.hasAttribute("disabled"))).toBe(true);
    finish(doc({ version: 2 }));
    await waitFor(() => expect(screen.getAllByRole("button", { name: "파일 바꾸기" }).every((b) => !b.hasAttribute("disabled"))).toBe(true));
  });
});
