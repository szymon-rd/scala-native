"""Builds a job file for CCDriver from test directories.

Usage: genjobs.py <out.jobs> <out.expected> <kind>=<dir> ...
kind is pos (no errors expected) or neg (errors expected on lines marked
`// error`). Expected file: <id>\t<kind>\t<comma separated file:line markers>.
"""
import os, re, sys

OPTS = {
    "pos": "-language:experimental.captureChecking -language:experimental.separationChecking",
    "neg": "-language:experimental.captureChecking -language:experimental.separationChecking -source 3.8",
    # hole suite: files import the language features themselves
    "spos": "",
    "sneg": "",
}
USING = re.compile(r"^//> using options (.*)$", re.M)

def file_opts(paths):
    opts = []
    for p in paths:
        with open(p, encoding="utf-8", errors="replace") as f:
            for m in USING.finditer(f.read()):
                opts += m.group(1).split()
    return opts

def markers(paths):
    res = []
    for p in paths:
        with open(p, encoding="utf-8", errors="replace") as f:
            for i, line in enumerate(f, 1):
                n = len(re.findall(r"//.*?\berror\b", line)) and line.count("// error") + line.count("// nopos-error")
                if "// error" in line or "// nopos-error" in line:
                    res.append(f"{os.path.basename(p)}:{i}")
    return res

def main():
    jobs, expected = open(sys.argv[1], "w"), open(sys.argv[2], "w")
    for spec in sys.argv[3:]:
        kind, d = spec.split("=", 1)
        for name in sorted(os.listdir(d)):
            p = os.path.join(d, name)
            if os.path.isdir(p):
                files = sorted(os.path.join(p, f) for f in os.listdir(p) if f.endswith(".scala"))
                if not files or any(f.endswith(".java") for f in os.listdir(p)):
                    continue
            elif name.endswith(".scala"):
                files = [p]
            else:
                continue
            opts = OPTS[kind].split() + file_opts(files)
            jid = f"{kind}:{os.path.relpath(p, d)}"
            jobs.write(f"{jid}\t{' '.join(opts)}\t{' '.join(files)}\n")
            expected.write(f"{jid}\t{kind}\t{','.join(sorted(markers(files)))}\n")

main()
