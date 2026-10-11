#!/usr/bin/env python3
"""docs-link-check — 문서 경로 참조가 실제로 존재하는지 검사한다.

왜 있나
-------
문서를 옮긴 뒤 참조가 조용히 끊기고, 몇 달 뒤 "그 문서 어디 갔지"로 시간을 쓴다.
2026-10-12 정리에서 29건이 끊겨 있었다. 내력: docs/FINDINGS.md §B4 · §B5.

이 검사기가 아는 것
-------------------
1. **등재된 폐기 문서는 통과시킨다.** docs/lineage_index.md § "저장소에 없는 문서 이름"에
   삭제 커밋과 함께 등재된 이름은, 본문에서 "이 틀은 폐기됐다"고 지목하기 위해 일부러
   쓰인다. 파일이 없는 것이 정상이다. 등재되지 않은 끊김만 결함으로 센다.
2. **collector/ 안의 docs/<이름>.md 는 그 서브트리 기준 상대경로다** (= collector/docs/...).
   루트에서 보면 깨진 것처럼 보이지만 맞다.
3. **aiagent/docs/... 는 공유 git host의 부모 저장소**를 가리키는 의도된 교차 참조다.
4. **중복 접두**(docs/archive/2026-q3/docs/... 처럼 한 단 겹친 경로)를 따로 잡는다 —
   sed 치환에서 `&` 가 접두까지 삼켜 생기는 사고다.

사용법
------
    python3 tools/docs-link-check.py          # 끊김이 있으면 exit 1
    python3 tools/docs-link-check.py -v       # 등재된 폐기 참조까지 전부 보여준다
"""

from __future__ import annotations

import os
import re
import sys

REPO = os.path.dirname(os.path.abspath(os.path.join(__file__, "..")))
LINEAGE = "docs/lineage_index.md"

SCAN_EXT = {
    ".md", ".java", ".ts", ".tsx", ".yml", ".yaml",
    ".json", ".sh", ".sql", ".mjs", ".cjs", ".properties",
    ".conf", ".toml", ".gradle", ".example", ".txt",
}

# 확장자가 없거나 점으로 시작하는 파일도 문서 경로를 적는다. 확장자 allowlist만 보면
# `.gitignore` 와 `Dockerfile` 안의 낡은 경로를 놓친다 — 2026-10-12에 실제로 놓쳤다.
# 내력: docs/FINDINGS.md §B8.
SCAN_NAMES = {
    ".gitignore", ".gitattributes", ".dockerignore", ".env.example",
    "Makefile", "Dockerfile", "nginx.conf",
}
SKIP_DIRS = {
    "node_modules", ".git", "build", "dist", ".gradle", ".run",
    ".profile", ".status", ".connections", "downloads", "target",
}

# 적용된 Flyway 마이그레이션은 주석조차 불변이다 — 한 글자만 바뀌어도 체크섬이 달라져
# 기존 DB에서 validate 가 깨진다. 그래서 그 안의 낡은 문서 경로는 고치지 않고, 검사도
# 하지 않는다. 내력: docs/FINDINGS.md §B7.
SKIP_PATH_PARTS = ("db/migration/",)

REF = re.compile(r"[A-Za-z0-9_./-]*docs/[A-Za-z0-9_./-]+\.md")
DOUBLED = re.compile(r"(docs|contracts)/[A-Za-z0-9_.-]+/(docs|contracts)/")


def acknowledged() -> set[str]:
    """lineage_index.md 가 '저장소에 없다'고 등재한 경로."""
    path = os.path.join(REPO, LINEAGE)
    if not os.path.isfile(path):
        return set()
    text = open(path, encoding="utf-8").read()
    marker = "저장소에 없는 문서 이름"
    idx = text.find(marker)
    if idx == -1:
        return set()
    return set(re.findall(r"`(docs/[A-Za-z0-9_./-]+\.md)`", text[idx:]))


def walk() -> list[str]:
    out = []
    for root, dirs, files in os.walk(REPO):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if os.path.splitext(f)[1] in SCAN_EXT or f in SCAN_NAMES \
                    or f.startswith("Dockerfile"):
                out.append(os.path.relpath(os.path.join(root, f), REPO))
    return sorted(out)


def main() -> int:
    verbose = "-v" in sys.argv or "--verbose" in sys.argv
    known = acknowledged()
    broken: list[tuple[str, int, str]] = []
    acked = 0

    for rel in walk():
        if rel == LINEAGE:
            continue  # 없는 이름을 싣는 것이 이 파일의 역할이다
        if any(part in rel for part in SKIP_PATH_PARTS):
            continue  # 체크섬 불변 (§B7)
        try:
            lines = open(os.path.join(REPO, rel), encoding="utf-8").read().split("\n")
        except (UnicodeDecodeError, OSError):
            continue
        subtree = rel.split("/")[0] if "/" in rel else ""
        for n, line in enumerate(lines, 1):
            for ref in REF.findall(line):
                if ref.startswith("aiagent/") or "<" in ref:
                    continue
                if os.path.isfile(os.path.join(REPO, ref)):
                    continue
                if subtree and os.path.isfile(os.path.join(REPO, subtree, ref)):
                    continue  # 서브트리 기준 상대경로
                here = os.path.dirname(os.path.join(REPO, rel))
                if os.path.isfile(os.path.normpath(os.path.join(here, ref))):
                    continue  # 그 파일 위치 기준 상대경로
                if ref in known:
                    acked += 1
                    if verbose:
                        print(f"등재됨  {rel}:{n}  ->  {ref}")
                    continue
                broken.append((rel, n, ref))

    doubled: list[tuple[str, int, str]] = []
    for rel in walk():
        if any(part in rel for part in SKIP_PATH_PARTS):
            continue
        try:
            lines = open(os.path.join(REPO, rel), encoding="utf-8").read().split("\n")
        except (UnicodeDecodeError, OSError):
            continue
        for n, line in enumerate(lines, 1):
            if DOUBLED.search(line):
                doubled.append((rel, n, line.strip()[:100]))

    for rel, n, ref in broken:
        print(f"끊김  {rel}:{n}  ->  {ref}")
    if doubled:
        print()
        print("중복 접두 의심 (docs/FINDINGS.md §B4):")
        for rel, n, line in doubled:
            print(f"  {rel}:{n}  {line}")

    print()
    print(f"끊김 {len(broken)}건 · 등재된 폐기 참조 {acked}건 · 중복 접두 {len(doubled)}건")
    if broken:
        print(
            "지워진 문서라면 docs/lineage_index.md 의 "
            "'저장소에 없는 문서 이름' 표에 삭제 커밋과 함께 등재한다."
        )
    return 1 if (broken or doubled) else 0


if __name__ == "__main__":
    sys.exit(main())
