#!/usr/bin/env python3
"""Coverage of the lines this branch changed, from kover's XML and git's diff.

The whole-repository floor can only catch a slide: a feature can arrive with no tests at all and
barely move a 10,000-line total. This asks the narrower question -- of the lines you added, how
many does a test reach -- which is the one a reviewer actually wants answered.
"""
import re, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

THRESHOLD = int(sys.argv[1]) if len(sys.argv) > 1 else 80
BASE = sys.argv[2] if len(sys.argv) > 2 else "origin/main"
# Below this, the percentage says more about the denominator than the tests: one uncovered
# default argument in a four-line diff is 75%. Reported, not enforced.
MIN_LINES = 20

def added_lines():
    """{path: {line numbers added}} for Kotlin sources, tests excluded."""
    out = subprocess.run(["git", "diff", "-U0", f"{BASE}...HEAD", "--", "*.kt"],
                         capture_output=True, text=True, check=True).stdout
    added, path = {}, None
    for line in out.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
            if "/test/" in path or "/androidTest/" in path or "/sharedTest/" in path:
                path = None
        elif line.startswith("@@") and path:
            m = re.search(r"\+(\d+)(?:,(\d+))?", line)
            if m:
                start, count = int(m.group(1)), int(m.group(2) or 1)
                added.setdefault(path, set()).update(range(start, start + count))
    return added

def covered_and_missed():
    """Per source file name, the line numbers kover saw covered and missed."""
    cov, miss = {}, {}
    for report in Path(".").glob("**/build/reports/kover/report.xml"):
        for pkg in ET.parse(report).getroot().iter("package"):
            for sf in pkg.findall("sourcefile"):
                key = (pkg.get("name") or "").replace(".", "/") + "/" + sf.get("name")
                for ln in sf.findall("line"):
                    n, hit = int(ln.get("nr")), int(ln.get("ci", 0))
                    (cov if hit else miss).setdefault(key, set()).add(n)
    return cov, miss

def main():
    added = added_lines()
    if not added:
        print("No Kotlin source lines added outside tests: nothing to measure.")
        return 0
    cov, miss = covered_and_missed()
    hit = total = 0
    detail = []
    for path, lines in sorted(added.items()):
        # kover keys by package/file; the diff gives a repo path. Match on the tail.
        key = next((k for k in set(cov) | set(miss) if path.endswith(k)), None)
        if not key:
            continue  # a file kover doesn't measure (excluded, or not compiled)
        c = lines & cov.get(key, set())
        m = lines & miss.get(key, set())
        if not (c or m):
            continue
        hit += len(c); total += len(c) + len(m)
        if m:
            detail.append(f"  {path}: {len(c)}/{len(c) + len(m)} covered, missed "
                          + ",".join(str(n) for n in sorted(m)[:12])
                          + (" …" if len(m) > 12 else ""))
    if total == 0:
        print("No measurable lines changed: nothing to measure.")
        return 0
    pct = 100 * hit / total
    print(f"Changed-line coverage: {pct:.1f}% ({hit}/{total}) against a {THRESHOLD}% floor")
    for d in detail:
        print(d)
    if total < MIN_LINES:
        print(f"Under {MIN_LINES} measurable lines changed: reported, not enforced.")
        return 0
    if pct < THRESHOLD:
        print(f"::error::the lines this branch adds are {pct:.1f}% covered, under {THRESHOLD}%")
        return 1
    return 0

sys.exit(main())
