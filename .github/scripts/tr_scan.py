"""Finds the Ui.tr(...) calls in the app's sources, for the Spanish checks (test_spanish.py)."""
import glob
import os
import re

SOURCES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "br", "dev", "vitormelo", "marquise")


def split_args(text, start):
    """The arguments of a call whose "(" is just before text[start], with strings and nested parentheses respected."""
    depth, i, args, cur, in_str = 1, start, [], "", False
    while i < len(text):
        c = text[i]
        if in_str:
            cur += c
            if c == "\\":
                cur += text[i + 1]
                i += 1
            elif c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            cur += c
        elif c == "'" and text[i + 2:i + 3] == "'":
            cur += text[i:i + 3]
            i += 2
        elif c in "([{":
            depth += 1
            cur += c
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                args.append(cur.strip())
                return args
            cur += c
        elif c == "," and depth == 1:
            args.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    raise ValueError("unbalanced call")


STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')


def literal(arg):
    """The text of an argument that is only string literals joined by +, or None when it has anything else in it."""
    pieces = STRING.findall(arg)
    rest = STRING.sub("", arg)
    return "".join(pieces) if pieces and re.fullmatch(r"[\s+]*", rest) else None


def calls():
    """Every Ui.tr call: (file, line, [argument source texts])."""
    out = []
    for path in sorted(glob.glob(os.path.join(SOURCES, "*.java"))):
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(r"\bUi\.tr\(", text):
            line_start = text.rfind("\n", 0, m.start()) + 1
            if re.match(r"\s*(//|\*|/\*)", text[line_start:m.start()]):   # an example in a comment, not a call
                continue
            out.append((os.path.basename(path), text.count("\n", 0, m.start()) + 1, split_args(text, m.end())))
    return out


def translated():
    """The English texts that Es.java has a Spanish for (the first string of each pair in PAIRS)."""
    text = open(os.path.join(SOURCES, "Es.java"), encoding="utf-8").read()
    body = text[text.index("PAIRS = {"):text.index("private static final Map")]
    return {m[0]: m[1] for m in re.findall(r'\{\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\}', body)}
