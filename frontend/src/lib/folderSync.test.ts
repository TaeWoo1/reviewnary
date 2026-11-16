// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import {
  fingerprint,
  listHandle,
  listPicked,
  sha256Hex,
  syncRound,
  type DirHandle,
  type FileHandleLike,
  type FolderApi,
  type FolderScanResult,
  type HashCache,
} from "./folderSync";

const enc = new TextEncoder();
const file = (name: string, text: string, lastModified = 1) => new File([enc.encode(text)], name, { lastModified });

function dir(name: string, children: (DirHandle | FileHandleLike)[]): DirHandle {
  return {
    kind: "directory",
    name,
    async *values() {
      for (const c of children) yield c;
    },
  };
}
const fh = (f: File): FileHandleLike => ({ kind: "file", name: f.name, getFile: async () => f });

function scanResult(plans: FolderScanResult["plans"]): FolderScanResult {
  return {
    plans,
    deactivated: 0,
    reactivated: 0,
    warning: null,
    status: { linked: true, folderName: "자료", mode: "WATCH", lastScanAt: null, synced: 0, held: [], missing: [] },
  };
}

describe("자료 폴더 동기화 — 브라우저 쪽 (2026-11-17)", () => {
  it("walks subfolders, skips hidden and Office lock files, and never sends the folder's place on the PC", async () => {
    const root = dir("나누리 자료", [
      fh(file("배송 안내.txt", "평일 오후 3시 이전 결제는 당일 출고")),
      fh(file(".DS_Store", "x")),
      fh(file("~$배송 안내.docx", "lock")),
      dir("텀블러", [fh(file("세척.md", "손세척 해 주세요"))]),
    ]);
    const listing = await listHandle(root);
    expect(listing.name).toBe("나누리 자료");
    expect(listing.complete).toBe(true);
    expect(listing.files.map((f) => f.path).sort()).toEqual(["배송 안내.txt", "텀블러/세척.md"]);
  });

  it("a part of the folder that cannot be listed makes the listing incomplete (so nothing is switched off)", async () => {
    const broken: DirHandle = {
      kind: "directory",
      name: "잠긴 폴더",
      // eslint-disable-next-line require-yield
      async *values() {
        throw new Error("NotAllowedError");
      },
    };
    const listing = await listHandle(dir("자료", [fh(file("a.txt", "가나다")), broken]));
    expect(listing.complete).toBe(false);
    expect(listing.files.map((f) => f.path)).toEqual(["a.txt"]);
  });

  it("a picked folder (other browsers) drops the folder's own name from every path", () => {
    const a = file("배송 안내.txt", "x");
    Object.defineProperty(a, "webkitRelativePath", { value: "나누리 자료/배송 안내.txt" });
    const b = file("세척.md", "y");
    Object.defineProperty(b, "webkitRelativePath", { value: "나누리 자료/텀블러/세척.md" });
    const listing = listPicked([a, b])!;
    expect(listing.name).toBe("나누리 자료");
    expect(listing.files.map((f) => f.path)).toEqual(["배송 안내.txt", "텀블러/세척.md"]);
  });

  it("re-reads a file for its fingerprint only when its size or time moved; formats the server ignores are not read", async () => {
    const f = file("배송 안내.txt", "평일 오후 3시");
    const read = vi.fn(async () => f);
    const photo = vi.fn(async () => file("사진.jpg", "jpg"));
    const listing = {
      name: "자료",
      complete: true,
      files: [
        { path: "배송 안내.txt", size: f.size, lastModified: 1, read },
        { path: "사진.jpg", size: 3, lastModified: 1, read: photo },
      ],
    };
    const cache: HashCache = new Map();
    const first = await fingerprint(listing, cache);
    expect(first[0].sha256).toBe(await sha256Hex(await f.arrayBuffer()));
    expect(first[1].sha256).toBeNull();
    await fingerprint(listing, cache);
    expect(read).toHaveBeenCalledTimes(1);
    expect(photo).not.toHaveBeenCalled();
    listing.files[0].lastModified = 2;
    await fingerprint(listing, cache);
    expect(read).toHaveBeenCalledTimes(2);
  });

  it("sends only the files the server asks for, finds them under an NFD name, and keeps going past a failed send", async () => {
    const nfd = "텀블러/세척.md".normalize("NFD");
    const root = dir("자료", [
      fh(file("배송 안내.txt", "평일 오후 3시")),
      fh(file("교환 안내.txt", "7일 안에")),
      dir("텀블러".normalize("NFD"), [fh(file("세척.md".normalize("NFD"), "손세척"))]),
    ]);
    const listing = await listHandle(root);
    expect(listing.files.some((f) => f.path === nfd)).toBe(true);
    const api: FolderApi = {
      scanKnowledgeFolder: vi.fn(async () =>
        scanResult([
          { path: "배송 안내.txt", action: "UNCHANGED", reason: null, message: null },
          { path: "교환 안내.txt", action: "UPLOAD", reason: null, message: null },
          { path: "텀블러/세척.md", action: "UPLOAD", reason: null, message: null },
        ]),
      ),
      uploadKnowledgeFolderFile: vi.fn(async (path: string) => {
        if (path === "교환 안내.txt") throw new Error("network");
        return { path, state: "SYNCED" as const, reason: null, message: null, sourceId: "s", version: 1 };
      }),
    };
    const r = await syncRound(api, listing, "WATCH", new Map());
    expect(api.uploadKnowledgeFolderFile).toHaveBeenCalledTimes(2);
    expect(r.uploaded).toBe(1);
    expect(r.failed).toBe(1);
    const sent = (api.scanKnowledgeFolder as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(sent.folderName).toBe("자료");
    expect(sent.complete).toBe(true);
    expect(JSON.stringify(sent)).not.toMatch(/Users|C:\\\\/);
  });
});
