#!/usr/bin/env bash
set -e

python3 - << 'PY'
from pathlib import Path
import re

for path in list(Path("app").rglob("*.kt")) + list(Path("shared").rglob("*.kt")) + list(Path("sharedUi").rglob("*.kt")) + [Path("README.md"), Path("build.gradle.kts"), Path("settings.gradle.kts")]:
    if path.is_file():
        text = path.read_text(errors="ignore")
        orig = text
        text = text.replace("Swayam", "pxper_")
        text = text.replace("swayam", "pxper_")
        if text != orig:
            path.write_text(text)
            print(f"Updated dev references in {path}")
PY

git add -A && git commit -m "Change developer name to pxper_" && git push origin main
echo "Developer name successfully changed to pxper_!"
