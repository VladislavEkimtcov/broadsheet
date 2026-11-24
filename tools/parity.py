#!/usr/bin/env python3
"""Checks PARITY.md against the code of every front end (TV, Phone, Windows). Standard library only.

    python3 tools/parity.py                    # ledger vs. `// feature: <id>` tags
    python3 tools/parity.py --diff origin/main # also: one app changed without PARITY.md

Where each platform's code is: the TV and the phone are one Android app. android/src/ is shared
by both (relay client, controller, reader, options), android/tv/src/ is the TV's own UI and
android/phone/src/ the phone's. Windows is windows/src/.

Fails (exit 1) when:
  - a ✅ cell has no tag for its ID in that platform's code (its own or the shared code)
  - a tag names an ID that isn't in PARITY.md, or sits in a platform's own code where it's
    marked — (a tag in shared Android code doesn't contradict a — on one of the two)
  - a ⚠️ or — row has no note, or an ID is listed twice
  - (--diff) some front ends' code changed since BASE, others' didn't, PARITY.md didn't, and
    no commit in the range carries a `Parity: none` trailer
Lists the ⏳ rows (owed ports) as warnings.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEDGER = "PARITY.md"
ANDROID = ("android/src/", "android/res/", "android/AndroidManifest.xml")
PLATFORMS = {
    # name: (column in the table, its own source tree, source trees it shares, file extension,
    #        paths that count as "this front end changed")
    "TV": (2, "android/tv/src", ("android/src",), ".java", ANDROID + ("android/tv/src/",)),
    "Phone": (3, "android/phone/src", ("android/src",), ".java", ANDROID + ("android/phone/src/",)),
    "Windows": (4, "windows/src", (), ".cs", ("windows/src/", "windows/App.xaml")),
}
COLUMNS = 3 + len(PLATFORMS)
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
            if len(cells) != COLUMNS:
                errors.append("%s:%d: %s: expected %d columns (ID, Feature, %s, Notes)"
                              % (LEDGER, n, fid, COLUMNS, ", ".join(PLATFORMS)))
                continue
            if fid in rows:
                errors.append("%s:%d: %s is listed twice" % (LEDGER, n, fid))
            notes = cells[COLUMNS - 1]
            row = {"line": n, "notes": notes}
            for plat, (col, _, _, _, _) in PLATFORMS.items():
                s = status(cells[col])
                if s is None:
                    errors.append("%s:%d: %s: %s cell %r is not ✅, ⏳, ⚠️ or —" % (LEDGER, n, fid, plat, cells[col]))
                row[plat] = s
                row[plat + " cell"] = cells[col]
                if s in ("divergent", "n/a") and not notes:
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
               for p, (_, _, _, _, prefixes) in PLATFORMS.items()}
    if LEDGER in changed or all(touched.values()) or not any(touched.values()):
        return
    if re.search(r"^Parity:\s*none\b", messages, re.M | re.I):
        return
    changed_in = [p for p, t in touched.items() if t]
    others = [p for p, t in touched.items() if not t]
    errors.append("only %s changed since %s, and PARITY.md didn't. Port it to %s, or mark it ⏳ there "
                  "in PARITY.md, or add a 'Parity: none' trailer if nothing a user sees changed."
                  % (" and ".join(changed_in), base, " and ".join(others)))


def main(argv):
    errors, warnings = [], []
    rows = read_ledger(errors)
    unknown = set()
    for plat, (_, tree, shared, ext, _) in PLATFORMS.items():
        own = read_tags(tree, ext)
        tags = dict(own)
        for t in shared:
            for fid, where in read_tags(t, ext).items():
                tags.setdefault(fid, []).extend(where)
        for fid, where in sorted(tags.items()):
            if fid not in rows:
                if where[0] not in unknown:
                    unknown.add(where[0])
                    errors.append("%s: tag for %s, which isn't in PARITY.md" % (where[0], fid))
            elif rows[fid][plat] == "n/a" and fid in own:
                errors.append("%s: tag for %s, which PARITY.md marks — (n/a) on %s" % (own[fid][0], fid, plat))
        for fid, row in rows.items():
            if row[plat] == "done" and fid not in tags:
                errors.append("%s:%d: %s is ✅ on %s but no `// feature: %s` tag is in %s/"
                              % (LEDGER, row["line"], fid, plat, fid, " or ".join((tree,) + shared)))
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
