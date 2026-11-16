/**
 * 자료 폴더 동기화 — the browser half (owner decision 2026-11-17, backend `KnowledgeFolderSyncService`).
 *
 * Chrome and Edge on a PC can hold a folder the seller picked once (File System Access API). While a Reviewnary window
 * is open this looks at it every few minutes and sends only the files whose content changed; what it sent stays on
 * the server when the window or the PC is closed, and edits made meanwhile are caught on the next look. Other browsers
 * cannot hold a folder, so they send it whole when the seller picks it — the server still keeps only what changed.
 *
 * The folder's location on the PC is never read or sent: only paths inside it, sizes and SHA-256 fingerprints, and
 * the bytes of the files the server asks for.
 */

export type FolderPlanAction = "UPLOAD" | "UNCHANGED" | "REACTIVATED" | "HELD" | "IGNORED";

export interface FolderEntry {
  path: string;
  size: number;
  sha256: string | null;
}

export interface FolderPlan {
  path: string;
  action: FolderPlanAction;
  reason: string | null;
  message: string | null;
}

export interface FolderFileView {
  id: string;
  path: string;
  scope: "ORG" | "PRODUCT";
  productName: string | null;
  state: "SYNCED" | "MISSING" | "HELD";
  reason: string | null;
  message: string | null;
  sourceId: string | null;
  canUseFolder: boolean;
  updatedAt: string | null;
}

export interface FolderStatus {
  linked: boolean;
  folderName: string | null;
  mode: "WATCH" | "UPLOAD" | null;
  lastScanAt: string | null;
  synced: number;
  held: FolderFileView[];
  missing: FolderFileView[];
}

export interface FolderScanResult {
  plans: FolderPlan[];
  deactivated: number;
  reactivated: number;
  warning: string | null;
  status: FolderStatus;
}

export interface FolderUploadResult {
  path: string;
  state: "SYNCED" | "HELD";
  reason: string | null;
  message: string | null;
  sourceId: string | null;
  version: number | null;
}

/** The reason words the seller reads. */
export const HELD_REASON_WORD: Record<string, string> = {
  PRODUCT_UNMATCHED: "상품 확인 필요",
  CONFLICT_TITLE: "같은 이름 자료가 있음",
  CONFLICT_EDITED: "웹에서 따로 바뀜",
  ENCRYPTED: "암호가 걸린 PDF",
  READ_FAILED: "읽지 못함",
};

/** How often an open window looks at a held folder. */
export const WATCH_INTERVAL_MS = 3 * 60 * 1000;

/** The server's size bound; bigger files are not read here, the server holds them by size. */
export const MAX_BYTES = 10 * 1024 * 1024;

// ---------------------------------------------------------------- local files

/** One file of the folder as this browser can read it. */
export interface LocalFile {
  /** Inside the folder, `/`-separated, without the folder's own name. */
  path: string;
  size: number;
  lastModified: number;
  read(): Promise<File>;
}

export interface FolderListing {
  name: string;
  files: LocalFile[];
  /** False when some part could not be listed — then nothing is switched off this round. */
  complete: boolean;
}

/** The minimal shape of the File System Access handles this uses (not in the TS DOM lib). */
export interface DirHandle {
  kind: "directory";
  name: string;
  values(): AsyncIterable<DirHandle | FileHandleLike>;
  queryPermission?(d: { mode: "read" }): Promise<PermissionState>;
  requestPermission?(d: { mode: "read" }): Promise<PermissionState>;
}

export interface FileHandleLike {
  kind: "file";
  name: string;
  getFile(): Promise<File>;
}

/** Whether this browser can hold a folder (Chrome / Edge on a PC). */
export function canWatchFolder(): boolean {
  return typeof window !== "undefined" && typeof (window as unknown as { showDirectoryPicker?: unknown }).showDirectoryPicker === "function";
}

/** Ask the seller for a folder (needs a click). */
export async function pickFolder(): Promise<DirHandle> {
  const picker = (window as unknown as { showDirectoryPicker: (o?: { mode?: "read" }) => Promise<DirHandle> }).showDirectoryPicker;
  return picker({ mode: "read" });
}

/** A name nobody keeps material in: hidden, system or Office lock files. Never hashed or read. */
export function skipName(name: string): boolean {
  return name.startsWith(".") || name.startsWith("~$") || /^(thumbs\.db|desktop\.ini)$/i.test(name);
}

/** Every file under a held folder. An entry that cannot be read makes the listing incomplete instead of failing it. */
export async function listHandle(root: DirHandle): Promise<FolderListing> {
  const files: LocalFile[] = [];
  let complete = true;
  async function walk(dir: DirHandle, prefix: string): Promise<void> {
    try {
      for await (const entry of dir.values()) {
        if (skipName(entry.name)) continue;
        const path = prefix ? `${prefix}/${entry.name}` : entry.name;
        if (entry.kind === "directory") {
          await walk(entry, path);
        } else {
          try {
            const f = await entry.getFile();
            files.push({ path, size: f.size, lastModified: f.lastModified, read: () => entry.getFile() });
          } catch {
            complete = false;
          }
        }
      }
    } catch {
      complete = false;
    }
  }
  await walk(root, "");
  return { name: root.name, files, complete };
}

/** A folder the seller picked with `<input webkitdirectory>`: paths come with the folder's name in front. */
export function listPicked(list: FileList | File[]): FolderListing | null {
  const all = Array.from(list);
  if (all.length === 0) return null;
  const first = (all[0] as File & { webkitRelativePath?: string }).webkitRelativePath ?? "";
  const name = first.split("/")[0] || "폴더";
  const files: LocalFile[] = [];
  for (const f of all) {
    const rel = (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name;
    const parts = rel.split("/").slice(1);
    if (parts.length === 0 || parts.some(skipName)) continue;
    files.push({ path: parts.join("/"), size: f.size, lastModified: f.lastModified, read: async () => f });
  }
  return { name, files, complete: true };
}

// ---------------------------------------------------------------- fingerprints

/** A file's bytes. `Blob.arrayBuffer` where the browser has it, FileReader where it does not (older Safari). */
export function readBytes(file: Blob): Promise<ArrayBuffer> {
  if (typeof (file as Blob & { arrayBuffer?: unknown }).arrayBuffer === "function") return file.arrayBuffer();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(file);
  });
}

export async function sha256Hex(data: ArrayBuffer): Promise<string> {
  // A view made here, so a buffer from another realm (an iframe, a FileReader of another window) is still accepted.
  const digest = await crypto.subtle.digest("SHA-256", new Uint8Array(data));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

const READABLE = /\.(pdf|docx|txt|md|csv)$/i;

export type HashCache = Map<string, { size: number; lastModified: number; sha256: string }>;

/**
 * The fingerprint of each file, re-reading only files whose size or modified time moved since last time. A format the
 * server does not read, or a file over the size bound, is sent without one (the server ignores or holds it by size).
 */
export async function fingerprint(listing: FolderListing, cache: HashCache): Promise<FolderEntry[]> {
  const out: FolderEntry[] = [];
  for (const f of listing.files) {
    if (!READABLE.test(f.path) || f.size > MAX_BYTES) {
      out.push({ path: f.path, size: f.size, sha256: f.size > MAX_BYTES ? `big:${f.size}:${f.lastModified}` : null });
      continue;
    }
    const hit = cache.get(f.path);
    if (hit && hit.size === f.size && hit.lastModified === f.lastModified) {
      out.push({ path: f.path, size: f.size, sha256: hit.sha256 });
      continue;
    }
    const file = await f.read();
    const sha = await sha256Hex(await readBytes(file));
    cache.set(f.path, { size: f.size, lastModified: f.lastModified, sha256: sha });
    out.push({ path: f.path, size: f.size, sha256: sha });
  }
  return out;
}

// ---------------------------------------------------------------- one round

export interface FolderApi {
  scanKnowledgeFolder(body: {
    folderName: string;
    mode: "WATCH" | "UPLOAD";
    complete: boolean;
    replaceFolder: boolean;
    files: FolderEntry[];
  }): Promise<FolderScanResult>;
  uploadKnowledgeFolderFile(path: string, sha256: string | null, file: File): Promise<FolderUploadResult>;
  /** Read after the sends, so the counts include what this round wrote. */
  getKnowledgeFolder?(): Promise<FolderStatus>;
}

export interface RoundSummary {
  uploaded: number;
  newlyHeld: FolderUploadResult[];
  failed: number;
  scan: FolderScanResult;
  /** The folder as the server holds it after this round's sends. */
  status: FolderStatus;
}

/** NFC the way the server does, so a plan path finds its file (macOS hands out NFD names). */
const nfc = (p: string) => p.split("/").map((s) => s.normalize("NFC").trim()).filter(Boolean).join("/");

/**
 * Look once: fingerprint, ask the server what to send, send it. A file that fails to send is retried on the next look;
 * it never stops the others.
 */
export async function syncRound(
  api: FolderApi,
  listing: FolderListing,
  mode: "WATCH" | "UPLOAD",
  cache: HashCache,
  replaceFolder = false,
): Promise<RoundSummary> {
  const entries = await fingerprint(listing, cache);
  const scan = await api.scanKnowledgeFolder({
    folderName: listing.name,
    mode,
    complete: listing.complete,
    replaceFolder,
    files: entries,
  });
  const byPath = new Map(listing.files.map((f) => [nfc(f.path), f]));
  const shaByPath = new Map(entries.map((e) => [nfc(e.path), e.sha256]));
  let uploaded = 0;
  let failed = 0;
  const newlyHeld: FolderUploadResult[] = [];
  for (const plan of scan.plans) {
    if (plan.action !== "UPLOAD") continue;
    const local = byPath.get(nfc(plan.path));
    if (!local) continue;
    try {
      const file = await local.read();
      const name = plan.path.split("/").pop() ?? file.name;
      const result = await api.uploadKnowledgeFolderFile(
        plan.path,
        shaByPath.get(nfc(plan.path)) ?? null,
        new File([file], name, { type: file.type }),
      );
      if (result.state === "HELD") newlyHeld.push(result);
      else uploaded++;
    } catch {
      failed++;
      cache.delete(local.path);
    }
  }
  let status = scan.status;
  if ((uploaded > 0 || newlyHeld.length > 0) && api.getKnowledgeFolder) {
    try {
      status = await api.getKnowledgeFolder();
    } catch {
      /* the scan's own status is one round behind, not wrong */
    }
  }
  return { uploaded, newlyHeld, failed, scan, status };
}

// ---------------------------------------------------------------- the held folder (IndexedDB)

const DB = "reviewnary-folder-sync";
const STORE = "kv";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function kv<T>(mode: IDBTransactionMode, op: (s: IDBObjectStore) => IDBRequest): Promise<T | undefined> {
  try {
    const db = await openDb();
    return await new Promise<T | undefined>((resolve, reject) => {
      const req = op(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => resolve(req.result as T);
      req.onerror = () => reject(req.error);
    });
  } catch {
    return undefined;
  }
}

/** The folder this browser holds for the signed-in org, if any. Kept per org so two shops on one PC never mix. */
export async function loadHandle(orgKey: string): Promise<DirHandle | undefined> {
  return kv<DirHandle>("readonly", (s) => s.get(`handle:${orgKey}`));
}

export async function saveHandle(orgKey: string, handle: DirHandle): Promise<void> {
  await kv("readwrite", (s) => s.put(handle, `handle:${orgKey}`));
}

export async function forgetHandle(orgKey: string): Promise<void> {
  await kv("readwrite", (s) => s.delete(`handle:${orgKey}`));
}

/** Whether the held folder may be read now without a click ("granted"), or needs one ("prompt"). */
export async function permissionOf(handle: DirHandle): Promise<PermissionState> {
  try {
    return (await handle.queryPermission?.({ mode: "read" })) ?? "granted";
  } catch {
    return "prompt";
  }
}

// ---------------------------------------------------------------- one runner per window

type Listener = () => void;
const listeners = new Set<Listener>();
let lastStatus: FolderStatus | null = null;
let lastRound: RoundSummary | null = null;
let running = false;

export const folderSyncState = {
  get status() {
    return lastStatus;
  },
  get lastRound() {
    return lastRound;
  },
  get running() {
    return running;
  },
  subscribe(l: Listener): () => void {
    listeners.add(l);
    return () => listeners.delete(l);
  },
  publish(status: FolderStatus | null, round?: RoundSummary | null) {
    if (status) lastStatus = status;
    if (round !== undefined) lastRound = round;
    listeners.forEach((l) => l());
  },
  setRunning(v: boolean) {
    running = v;
    listeners.forEach((l) => l());
  },
};

/**
 * Run `work` unless another Reviewnary tab of this browser is already looking at the folder (Web Locks), so two tabs
 * never send the same file twice.
 */
export async function exclusively<T>(work: () => Promise<T>): Promise<T | null> {
  const locks = (navigator as Navigator & { locks?: { request: (n: string, o: { ifAvailable: boolean }, cb: (l: unknown) => Promise<T | null>) => Promise<T | null> } }).locks;
  if (!locks) return work();
  return locks.request("reviewnary-folder-sync", { ifAvailable: true }, async (lock) => (lock ? work() : null));
}
