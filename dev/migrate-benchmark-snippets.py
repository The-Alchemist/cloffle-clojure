#!/usr/bin/env python3
"""One-shot migrator: expression snippets -> (ns bench.snippet.*) + top-level defs + (defn bench [])."""

from __future__ import annotations

import re
from pathlib import Path

SNIPPET_DIR = Path(__file__).resolve().parents[1] / "src/benchmark/resources/snippets"


def balanced_end(s: str, start: int) -> int:
    if start >= len(s) or s[start] != "(":
        raise ValueError(f"expected '(' at {start}")
    depth = 0
    i = start
    while i < len(s):
        c = s[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        elif c == '"':
            i += 1
            while i < len(s):
                if s[i] == "\\":
                    i += 2
                    continue
                if s[i] == '"':
                    break
                i += 1
        i += 1
    raise ValueError("unbalanced parens")


def split_forms(s: str) -> list[str]:
    s = s.strip()
    forms: list[str] = []
    i = 0
    while i < len(s):
        while i < len(s) and s[i].isspace():
            i += 1
        if i >= len(s):
            break
        if not s.startswith("(", i):
            raise ValueError(f"expected form at {i}: {s[i:i+40]!r}")
        end = balanced_end(s, i)
        forms.append(s[i:end].strip())
        i = end
    return forms


def ns_name(stem: str) -> str:
    return f"bench.snippet.{stem}"


def split_comments_and_body(text: str) -> tuple[str, str]:
    lines = text.splitlines()
    comment_lines: list[str] = []
    body_lines: list[str] = []
    in_body = False
    for line in lines:
        if not in_body and (line.strip().startswith(";;") or line.strip() == ""):
            comment_lines.append(line)
            continue
        in_body = True
        body_lines.append(line)
    comments = "\n".join(comment_lines).strip()
    body = "\n".join(body_lines).strip()
    return comments, body


def indent_block(code: str, spaces: int) -> str:
    pad = " " * spaces
    return "\n".join(pad + line if line.strip() else line for line in code.splitlines())


def parse_let(body: str) -> tuple[list[tuple[str, str]], str] | None:
    body = body.strip()
    if not body.startswith("(let"):
        return None
    lb = body.find("[")
    if lb < 0:
        return None
    i = lb + 1
    pairs: list[tuple[str, str]] = []
    while i < len(body):
        while i < len(body) and body[i].isspace():
            i += 1
        if i >= len(body):
            return None
        if body[i] == "]":
            i += 1
            break
        name_start = i
        while i < len(body) and not body[i].isspace():
            i += 1
        name = body[name_start:i].strip()
        while i < len(body) and body[i].isspace():
            i += 1
        if body[i] != "(":
            return None
        val_end = balanced_end(body, i)
        pairs.append((name, body[i:val_end].strip()))
        i = val_end
    rest = body[i:].strip()
    if rest.endswith(")"):
        rest = rest[:-1].strip()
    return pairs, rest


def fn_form_to_defn(name: str, fn_form: str) -> str | None:
    if not fn_form.startswith("(fn"):
        return None
    inner = fn_form[3:].strip()
    if inner.startswith("["):
        arg_end = inner.find("]")
        args = inner[: arg_end + 1]
        fn_body = inner[arg_end + 1 :].strip()
        if fn_body.endswith(")"):
            fn_body = fn_body[:-1].strip()
        return f"(defn {name} {args}\n  {indent_block(fn_body, 2)})"
    if inner.startswith("[]"):
        fn_body = inner[2:].strip()
        if fn_body.endswith(")"):
            fn_body = fn_body[:-1].strip()
        return f"(defn {name} []\n  {indent_block(fn_body, 2)})"
    return None


def migrate_let_fn_bindings(body: str) -> str | None:
    parsed = parse_let(body)
    if parsed is None:
        return None
    pairs, rest = parsed
    if not pairs:
        return None
    defns: list[str] = []
    for name, val in pairs:
        if not val.startswith("(fn"):
            return None
        defn = fn_form_to_defn(name, val)
        if defn is None:
            return None
        defns.append(defn)
    return "\n\n".join(defns) + f"\n\n(defn bench []\n  {rest})"


def migrate_do_defns(body: str) -> str | None:
    body = body.strip()
    if not body.startswith("(do"):
        return None
    inner = body[3:].strip()
    if inner.endswith(")"):
        inner = inner[:-1].strip()
    forms = split_forms(inner)
    if len(forms) < 2:
        return None
    if not all(f.startswith("(defn") for f in forms[:-1]):
        return None
    defns = forms[:-1]
    bench_body = forms[-1]
    return "\n\n".join(defns) + f"\n\n(defn bench []\n  {bench_body})"


def migrate_invoke_fn(body: str) -> str | None:
    """((fn [args] ...) arg ...) -> defn + bench."""
    body = body.strip()
    if not body.startswith("(("):
        return None
    try:
        fn_end = balanced_end(body, 1)
    except ValueError:
        return None
    fn_form = body[1:fn_end].strip()
    args = body[fn_end:].strip()
    if args.endswith(")"):
        args = args[:-1].strip()
    if not fn_form.startswith("(fn"):
        return None
    inner = fn_form[3:].strip()
    arg_end = inner.find("]")
    if arg_end < 0:
        return None
    params = inner[: arg_end + 1]
    fn_body = inner[arg_end + 1 :].strip()
    if fn_body.endswith(")"):
        fn_body = fn_body[:-1].strip()
    defn = f"(defn run {params}\n  {indent_block(fn_body, 2)})"
    return f"{defn}\n\n(defn bench []\n  (run {args}))"


def migrate_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    comments, body = split_comments_and_body(text)
    if body.startswith("(ns "):
        return False

    stem = path.stem
    converted = (
        migrate_do_defns(body)
        or migrate_let_fn_bindings(body)
        or migrate_invoke_fn(body)
    )
    if converted is None:
        converted = f"(defn bench []\n  {indent_block(body, 2)})"

    header = f"(ns {ns_name(stem)})\n\n"
    if comments:
        header = f"{comments}\n\n{header}"
    out = header + converted + "\n"
    path.write_text(out, encoding="utf-8")
    return True


def main() -> None:
    changed = 0
    for path in sorted(SNIPPET_DIR.glob("*.clj")):
        if migrate_file(path):
            changed += 1
            print("migrated", path.name)
    print(f"done: {changed} files")


if __name__ == "__main__":
    main()
