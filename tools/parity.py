#!/usr/bin/env python3
"""Checks PARITY.md against the code of both apps. Standard library only.

    python3 tools/parity.py                    # ledger vs. `// feature: <id>` tags
    python3 tools/parity.py --diff origin/main # also: one app changed without PARITY.md

Fails (exit 1) when:
  - a ✅ cell has no tag for its ID in that platform's code
  - a tag names an ID that isn't in PARITY.md, or sits in a platform marked —
  - a ⚠️ or — row has no note, or an ID is listed twice
  - (--diff) only one app's code changed since BASE, PARITY.md didn't, and no commit in the
    range carries a `Parity: none` trailer
Lists the ⏳ rows (owed ports) as warnings.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER = "PARITY.md"
PLATFORMS = {
    # name: (column in the table, source tree, file extension, paths that count as "this app changed")
    "TV": (2, "src", ".java", ("src/", "res/", "AndroidManifest.xml")),
    "Windows": (3, "windows/src", ".cs", ("windows/src/", "windows/App.xaml")),
}
TAG = re.compile(r"//\s*feature:\s*([a-z0-9_.,\s-]+)")
ROW = re.compile(r"^\|\s*`([a-z0-9_.-]+)`\s*\|")


def status(cell):
    cell = cell.strip()
    for mark, name in (("✅", "done"), ("⏳", "owed"), ("⚠️", "divergent"), ("—", "n/a")):
        if cell.startswith(mark):
            return name
    return None


def read_ledger(errors):
    rows = {}
    with open(os.path.join(ROOT, LEDGER), encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            m = ROW.match(line)
            if not m:
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            fid = m.group(1)
            if len(cells) < 5:
                errors.append("%s:%d: %s: expected 5 columns (ID, Feature, TV, Windows, Notes)" % (LEDGER, n, fid))
                continue
            if fid in rows:
                errors.append("%s:%d: %s is listed twice" % (LEDGER, n, fid))
            row = {"line": n, "notes": cells[4]}
            for plat, (col, _, _, _) in PLATFORMS.items():
                s = status(cells[col])
                if s is None:
                    errors.append("%s:%d: %s: %s cell %r is not ✅, ⏳, ⚠️ or —" % (LEDGER, n, fid, plat, cells[col]))
                row[plat] = s
                row[plat + " cell"] = cells[col]
                if s in ("divergent", "n/a") and not cells[4]:
                    errors.append("%s:%d: %s is %s on %s but has no note saying why" % (LEDGER, n, fid, s, plat))
            rows[fid] = row
    return rows


def read_tags(tree, ext):
    tags = {}
    base = os.path.join(ROOT, tree)
    for dirpath, _, files in os.walk(base):
        for name in sorted(files):
            if not name.endswith(ext):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as f:
                for n, line in enumerate(f, 1):
                    for m in TAG.finditer(line):
                        for fid in m.group(1).split(","):
                            fid = fid.strip()
                            if fid:
                                tags.setdefault(fid, []).append("%s:%d" % (os.path.relpath(path, ROOT), n))
    return tags


def check_diff(base, errors):
    def git(*args):
        return subprocess.run(("git",) + args, cwd=ROOT, capture_output=True, text=True, check=True).stdout

    if not base or set(base) == {"0"}:
        print("parity: no base to diff against, skipping the diff check")
        return
    try:
        changed = git("diff", "--name-only", base, "HEAD").split()
        messages = git("log", "--format=%B", base + "..HEAD")
    except subprocess.CalledProcessError as e:
        errors.append("git diff against %s failed: %s" % (base, e.stderr.strip()))
        return
    touched = {p: any(c.startswith(pre) for c in changed for pre in prefixes)
               for p, (_, _, _, prefixes) in PLATFORMS.items()}
    if LEDGER in changed or all(touched.values()) or not any(touched.values()):
        return
    if re.search(r"^Parity:\s*none\b", messages, re.M | re.I):
        return
    only = [p for p, t in touched.items() if t][0]
    other = [p for p in PLATFORMS if p != only][0]
    errors.append("only the %s app changed since %s, and PARITY.md didn't. Port it to %s, or mark it ⏳ there "
                  "in PARITY.md, or add a 'Parity: none' trailer if nothing a user sees changed." % (only, base, other))


def main(argv):
    errors, warnings = [], []
    rows = read_ledger(errors)
    for plat, (_, tree, ext, _) in PLATFORMS.items():
        tags = read_tags(tree, ext)
        for fid, where in sorted(tags.items()):
            if fid not in rows:
                errors.append("%s: tag for %s, which isn't in PARITY.md" % (where[0], fid))
            elif rows[fid][plat] == "n/a":
                errors.append("%s: tag for %s, which PARITY.md marks — (n/a) on %s" % (where[0], fid, plat))
        for fid, row in rows.items():
            if row[plat] == "done" and fid not in tags:
                errors.append("%s:%d: %s is ✅ on %s but no `// feature: %s` tag is in %s/"
                              % (LEDGER, row["line"], fid, plat, fid, tree))
            if row[plat] == "owed":
                warnings.append("%s owes %s (%s)" % (plat, fid, row[plat + " cell"]))
    if "--diff" in argv:
        i = argv.index("--diff")
        check_diff(argv[i + 1] if i + 1 < len(argv) else "", errors)

    for w in warnings:
        print("owed   " + w)
    for e in errors:
        print("ERROR  " + e)
    print("parity: %d features, %d owed, %d errors" % (len(rows), len(warnings), len(errors)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
