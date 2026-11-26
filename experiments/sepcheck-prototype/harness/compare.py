"""Scores results against expectations; optionally diffs against a baseline.

Usage: compare.py <expected> <results> [<baseline results>]
A pos job passes with no errors and no crash. A neg job passes when it has
errors and the set of error lines equals the set of `// error` markers.
With a baseline, prints jobs whose pass/fail status or error lines changed.
"""
import sys

def load(path):
    res = {}
    for line in open(path):
        parts = line.rstrip("\n").split("\t")
        res[parts[0]] = parts[1:]
    return res

def passes(kind, marks, r):
    n, locs, crash = int(r[0]), r[1], r[2]
    if crash:
        return False
    if kind.endswith("pos"):
        return n == 0
    got = {l for l in locs.split(",") if l and l != "?"}
    want = {m for m in marks.split(",") if m}
    return n > 0 and got == want

exp, res = load(sys.argv[1]), load(sys.argv[2])
base = load(sys.argv[3]) if len(sys.argv) > 3 else None
stats = {}
for jid, (kind, marks) in exp.items():
    r = res.get(jid)
    ok = r is not None and passes(kind, marks, r)
    stats.setdefault(kind, [0, 0])[0 if ok else 1] += 1
    if base is not None:
        b = base.get(jid)
        bok = b is not None and passes(kind, marks, b)
        if ok != bok or (r and b and r[:2] != b[:2]):
            print(f"{'FIXED ' if ok and not bok else 'BROKE ' if bok and not ok else 'CHANGED'} {jid}\n   base: {b}\n   now:  {r}")
for k, (p, f) in sorted(stats.items()):
    print(f"{k}: {p} pass, {f} fail")
