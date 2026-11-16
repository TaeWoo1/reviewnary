// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { FolderStatus } from "../../lib/folderSync";

const api = vi.hoisted(() => ({
  getKnowledgeFolder: vi.fn(),
  scanKnowledgeFolder: vi.fn(),
  uploadKnowledgeFolderFile: vi.fn(),
  useKnowledgeFolderCopy: vi.fn(),
  unlinkKnowledgeFolder: vi.fn(),
}));
vi.mock("../../lib/apiClient", () => ({ api }));
vi.mock("../../lib/auth", () => ({ useAuth: () => ({ user: { orgId: "org-1" } }) }));

import { KnowledgeFolder } from "./KnowledgeFolder";
import { webcrypto } from "node:crypto";

// jsdom's window.crypto has no SubtleCrypto; a real Chrome / Edge / Safari does.
if (!globalThis.crypto?.subtle) Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });

function status(over: Partial<FolderStatus> = {}): FolderStatus {
  return { linked: true, folderName: "나누리 자료", mode: "UPLOAD", lastScanAt: "2026-11-17T01:00:00Z", synced: 3, held: [], missing: [], ...over };
}

const HELD = {
  id: "f-1", path: "보온병/세척.txt", scope: "PRODUCT" as const, productName: null, state: "HELD" as const,
  reason: "PRODUCT_UNMATCHED", message: "상품 확인 필요: 「보온병」와 이름이 똑같은 상품이 없어요.", sourceId: null,
  canUseFolder: false, updatedAt: null,
};
const EDITED = { ...HELD, id: "f-2", path: "배송 안내.txt", scope: "ORG" as const, reason: "CONFLICT_EDITED", message: "웹에서 이 자료가 따로 바뀌었어요.", canUseFolder: true };

const renderPage = () => render(<MemoryRouter><KnowledgeFolder /></MemoryRouter>);

describe("자료 폴더 동기화 화면 (2026-11-17)", () => {
  beforeEach(() => vi.resetAllMocks());

  it("in a browser that cannot hold a folder, offers 폴더째 올리기 and lists stopped and switched-off files with why", async () => {
    api.getKnowledgeFolder.mockResolvedValue(status({
      held: [HELD, EDITED],
      missing: [{ ...HELD, id: "f-3", path: "옛 안내.txt", state: "MISSING", reason: null, message: null }],
    }));
    renderPage();
    expect(await screen.findByText("폴더째 올리기")).toBeTruthy();
    expect(screen.queryByText("폴더 연결하기")).toBeNull();
    expect((await screen.findByTestId("fs-held")).textContent).toContain("상품 확인 필요");
    expect(screen.getByTestId("fs-missing").textContent).toContain("옛 안내.txt");
    expect(screen.getByTestId("fs-summary").textContent).toContain("자료 3개");
    // Only the edited-on-the-web file may take the folder's copy.
    expect(screen.getAllByRole("button", { name: "폴더 파일로 덮어쓰기" })).toHaveLength(1);
  });

  it("폴더 파일로 덮어쓰기 asks the server for exactly that file", async () => {
    api.getKnowledgeFolder.mockResolvedValue(status({ held: [EDITED] }));
    api.useKnowledgeFolderCopy.mockResolvedValue(status());
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "폴더 파일로 덮어쓰기" }));
    await waitFor(() => expect(api.useKnowledgeFolderCopy).toHaveBeenCalledWith("f-2"));
  });

  it("a picked folder is scanned once, with its own name and without the PC location", async () => {
    api.getKnowledgeFolder.mockResolvedValue(status({ linked: false, folderName: null, mode: null, synced: 0 }));
    api.scanKnowledgeFolder.mockResolvedValue({
      plans: [{ path: "배송 안내.txt", action: "UPLOAD", reason: null, message: null }],
      deactivated: 0, reactivated: 0, warning: null, status: status({ synced: 1 }),
    });
    api.uploadKnowledgeFolderFile.mockResolvedValue({ path: "배송 안내.txt", state: "SYNCED", reason: null, message: null, sourceId: "s", version: 1 });
    api.getKnowledgeFolder.mockResolvedValueOnce(status({ linked: false, folderName: null, mode: null, synced: 0 })).mockResolvedValueOnce(status({ synced: 4 }));
    renderPage();
    const input = await screen.findByTestId("fs-pick");
    const f = new File(["평일 오후 3시 이전 결제는 당일 출고"], "배송 안내.txt");
    Object.defineProperty(f, "webkitRelativePath", { value: "나누리 자료/배송 안내.txt" });
    fireEvent.change(input, { target: { files: [f] } });
    await waitFor(() => expect(api.uploadKnowledgeFolderFile).toHaveBeenCalled());
    const body = api.scanKnowledgeFolder.mock.calls[0][0];
    expect(body.folderName).toBe("나누리 자료");
    expect(body.mode).toBe("UPLOAD");
    expect(body.files.map((x: { path: string }) => x.path)).toEqual(["배송 안내.txt"]);
    expect((await screen.findByTestId("fs-round")).textContent).toContain("올림 1개");
    // The counts are read after the sends, not from the scan that came before them.
    expect(api.getKnowledgeFolder).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("fs-summary").textContent).toContain("자료 4개");
  });
});
