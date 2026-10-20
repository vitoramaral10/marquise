#!/usr/bin/env python3
"""Automatic releases: what the commits since the last tag ask for, and the files a release changes.

    release.py plan            -> prints "release=true|false", "version=X.Y.Z" and "code=N" (for $GITHUB_OUTPUT)
    release.py apply X.Y.Z N   -> bumps the manifest and turns CHANGELOG's [Unreleased] into [X.Y.Z] - today
    release.py notes X.Y.Z     -> prints that version's CHANGELOG section (the release notes)

Conventional Commits decide the bump: a breaking change ("type!:" or "BREAKING CHANGE") goes to the next major,
"feat" to the next minor, "fix" and "perf" to the next patch. Anything else (docs, chore, ci, style, refactor, test,
build), and any type scoped to docs or ci, makes no release on its own.
"""
import datetime
import re
import subprocess
import sys

MANIFEST = "app/AndroidManifest.xml"
CHANGELOG = "CHANGELOG.md"
SUBJECT = re.compile(r"^(?P<type>[a-z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?: (?P<text>.+)$")
SECTIONS = {"feat": "Added", "fix": "Fixed", "perf": "Changed"}


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def last_tag():
    try:
        return git("describe", "--tags", "--abbrev=0", "--match", "v[0-9]*").strip()
    except subprocess.CalledProcessError:
        return ""


def commits(since):
    """(subject, body) of each commit after the tag, oldest first; release commits left out."""
    span = f"{since}..HEAD" if since else "HEAD"
    raw = git("log", "--reverse", "--format=%s%x1f%b%x1e", span)
    out = []
    for entry in raw.split("\x1e"):
        if not entry.strip():
            continue
        subject, _, body = entry.strip("\n").partition("\x1f")
        if subject.startswith("chore(release)"):
            continue
        out.append((subject.strip(), body))
    return out


def bump(entries):
    """0 = none, 1 = patch, 2 = minor, 3 = major."""
    level = 0
    for subject, body in entries:
        m = SUBJECT.match(subject)
        if not m:
            continue
        if m["bang"] or "BREAKING CHANGE" in body:
            return 3
        if (m["scope"] or "") in ("docs", "ci"):
            continue
        if m["type"] == "feat":
            level = max(level, 2)
        elif m["type"] in ("fix", "perf"):
            level = max(level, 1)
    return level


def manifest_version():
    text = open(MANIFEST, encoding="utf-8").read()
    code = int(re.search(r'android:versionCode="(\d+)"', text)[1])
    name = re.search(r'android:versionName="([^"]+)"', text)[1]
    return code, name


def plan():
    tag = last_tag()
    level = bump(commits(tag))
    code, name = manifest_version()
    base = tag[1:] if tag else name
    major, minor, patch = (int(x) for x in base.split("."))
    if level == 3:
        major, minor, patch = major + 1, 0, 0
    elif level == 2:
        minor, patch = minor + 1, 0
    elif level == 1:
        patch += 1
    print(f"release={'true' if level else 'false'}")
    print(f"version={major}.{minor}.{patch}")
    print(f"code={code + 1}")


def generated_section(entries):
    """When [Unreleased] was left empty: the commit subjects, grouped as in the rest of the file."""
    groups = {}
    for subject, _ in entries:
        m = SUBJECT.match(subject)
        if not m or m["type"] not in SECTIONS or (m["scope"] or "") in ("docs", "ci"):
            continue
        text = m["text"][0].upper() + m["text"][1:]
        groups.setdefault(SECTIONS[m["type"]], []).append(f"- {text}{'' if text.endswith('.') else '.'}")
    out = []
    for title in ("Added", "Changed", "Fixed"):
        if title in groups:
            out += [f"### {title}", "", *groups[title], ""]
    return "\n".join(out).rstrip() + "\n"


def apply(version, code):
    text = open(MANIFEST, encoding="utf-8").read()
    text = re.sub(r'android:versionCode="\d+"', f'android:versionCode="{code}"', text, count=1)
    text = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{version}"', text, count=1)
    open(MANIFEST, "w", encoding="utf-8").write(text)

    log = open(CHANGELOG, encoding="utf-8").read()
    today = datetime.date.today().isoformat()
    head = f"## [{version}] - {today}\n"
    m = re.search(r"^## \[Unreleased\]\n(?P<body>.*?)(?=^## \[|\Z)", log, flags=re.M | re.S)
    if m and m["body"].strip():
        log = log[:m.start()] + "## [Unreleased]\n\n" + head + "\n" + m["body"].lstrip("\n") + log[m.end():]
    else:
        section = head + "\n" + generated_section(commits(last_tag())) + "\n"
        if m:
            log = log[:m.start()] + "## [Unreleased]\n\n" + section + log[m.end():]
        else:
            first = re.search(r"^## \[", log, flags=re.M)
            at = first.start() if first else len(log)
            log = log[:at] + "## [Unreleased]\n\n" + section + log[at:]
    open(CHANGELOG, "w", encoding="utf-8").write(log)


def notes(version):
    log = open(CHANGELOG, encoding="utf-8").read()
    m = re.search(rf"^## \[{re.escape(version)}\][^\n]*\n(?P<body>.*?)(?=^## \[|\Z)", log, flags=re.M | re.S)
    print((m["body"] if m else "").strip())


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "plan":
        plan()
    elif cmd == "apply":
        apply(sys.argv[2], int(sys.argv[3]))
    elif cmd == "notes":
        notes(sys.argv[2])
    else:
        sys.exit(__doc__)
