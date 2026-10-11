#!/usr/bin/env python3
"""Parse, print and diff soak snapshots.

Kept out of the shell script for the reason `schedule_guard.py` is: the reporting needs real string
formatting, and arithmetic over two JSON files inside a heredoc is a quoting trap rather than a
readability win. Holds no SQL and opens no connection — it reads `section|key|value` lines on stdin.
"""
import json
import sys

# Sections whose growth is the measurement, in the order a reader wants them.
GROWTH = ["run", "run_source", "run_source_method", "job", "job_trigger", "walk",
          "case", "case_reason", "case_event", "legacy_case", "collect", "rows"]

#: Every invariant here must read 0 in every snapshot. A non-zero one is not a slow soak, it is a
#: broken rule, and the exit code says so rather than leaving it in the middle of a wall of counts.
INVARIANTS = "invariant"


def parse(stamp, label, lines):
    snap = {"at": stamp, "label": label, "sections": {}}
    for line in lines:
        line = line.rstrip("\n")
        if not line or "|" not in line:
            continue
        parts = line.split("|")
        if len(parts) != 3:
            continue
        section, key, value = parts
        snap["sections"].setdefault(section, {})[key] = value
    return snap


def _num(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def show(snap):
    label = (" " + snap["label"]) if snap.get("label") else ""
    print("SNAPSHOT %s%s" % (snap["at"], label))
    broken = []
    for key, value in sorted(snap["sections"].get(INVARIANTS, {}).items()):
        n = _num(value)
        if n:
            broken.append("%s=%s" % (key, value))
    print("  invariants      %s" % ("OK (all zero)" if not broken else "BROKEN: " + ", ".join(broken)))
    writes = snap["sections"].get("write", {})
    print("  write-side rows %d across %d tables" % (sum(_num(v) or 0 for v in writes.values()), len(writes)))
    for section in ["standing", "standing_overdue", "freshness"]:
        for key, value in sorted(snap["sections"].get(section, {}).items()):
            print("  %-15s %s = %s" % (section, key, value))
    totals = []
    for section in GROWTH:
        rows = snap["sections"].get(section, {})
        if rows:
            totals.append("%s=%d" % (section, sum(_num(v) or 0 for v in rows.values())))
    print("  totals          %s" % " ".join(totals))
    return 1 if broken else 0


def diff(a, b):
    hours = None
    try:
        import datetime
        fmt = "%Y%m%dT%H%M%SZ"
        hours = (datetime.datetime.strptime(b["at"], fmt)
                 - datetime.datetime.strptime(a["at"], fmt)).total_seconds() / 3600.0
    except Exception:
        pass
    span = "over %.1fh" % hours if hours else "span unknown"
    print("GROWTH %s -> %s (%s)" % (a["at"], b["at"], span))
    status = 0

    # The write tables first, because this is the one section where any movement at all is the answer.
    moved = []
    for key in sorted(set(a["sections"].get("write", {})) | set(b["sections"].get("write", {}))):
        before = _num(a["sections"].get("write", {}).get(key, "0")) or 0
        after = _num(b["sections"].get("write", {}).get(key, "0")) or 0
        if after != before:
            moved.append("%s %+d" % (key, after - before))
    if moved:
        print("  MARKETPLACE WRITE DETECTED: %s" % ", ".join(moved))
        status = 1
    else:
        print("  marketplace WRITE  0 new rows in every write-side table")

    broken = [k for k, v in sorted(b["sections"].get(INVARIANTS, {}).items()) if _num(v)]
    if broken:
        print("  INVARIANTS BROKEN: %s" % ", ".join(broken))
        status = 1
    else:
        print("  invariants         still all zero")

    for section in GROWTH:
        keys = sorted(set(a["sections"].get(section, {})) | set(b["sections"].get(section, {})))
        changed = []
        for key in keys:
            before = _num(a["sections"].get(section, {}).get(key, "0")) or 0
            after = _num(b["sections"].get(section, {}).get(key, "0")) or 0
            if after != before:
                rate = "" if not hours else " (%.1f/h)" % ((after - before) / hours)
                changed.append("    %-44s %6d -> %6d  %+d%s" % (key, before, after, after - before, rate))
        if changed:
            print("  %s" % section)
            for line in changed:
                print(line)
    for section in ["standing", "standing_overdue"]:
        before = a["sections"].get(section, {})
        after = b["sections"].get(section, {})
        if before != after:
            print("  %s  %s -> %s" % (section, before, after))
    return status


def main():
    mode = sys.argv[1]
    if mode == "parse":
        json.dump(parse(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "", sys.stdin), sys.stdout, indent=1)
        print()
        return 0
    if mode == "show":
        with open(sys.argv[2]) as f:
            return show(json.load(f))
    if mode == "diff":
        with open(sys.argv[2]) as f:
            a = json.load(f)
        with open(sys.argv[3]) as f:
            b = json.load(f)
        return diff(a, b)
    raise SystemExit("unknown mode: %s" % mode)


if __name__ == "__main__":
    sys.exit(main())
