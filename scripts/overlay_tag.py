#!/usr/bin/env python3
"""Read or replace the single image tag in a service Kustomize overlay."""

import re
import sys
from pathlib import Path


def main() -> None:
    if len(sys.argv) not in (3, 5) or sys.argv[1] not in ("read", "replace"):
        raise SystemExit("usage: overlay_tag.py read FILE | replace FILE OLD NEW")
    action, path = sys.argv[1:3]
    if (action == "read" and len(sys.argv) != 3) or (
        action == "replace" and len(sys.argv) != 5
    ):
        raise SystemExit("invalid arguments")
    file = Path(path)
    original = file.read_text()
    pattern = re.compile(r"(?m)^(\s*newTag:\s*)([A-Za-z0-9][A-Za-z0-9._-]*)\s*$")
    matches = list(pattern.finditer(original))
    if len(matches) != 1:
        raise SystemExit(f"expected exactly one newTag in {file}")
    current = matches[0].group(2)
    if action == "read":
        print(current)
        return
    old, new = sys.argv[3:5]
    if current != old or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", new):
        raise SystemExit(f"unexpected current tag or invalid replacement in {file}")
    updated = original[: matches[0].start(2)] + new + original[matches[0].end(2) :]
    file.write_text(updated)


if __name__ == "__main__":
    main()
