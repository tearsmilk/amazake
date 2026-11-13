#!/usr/bin/env bash
# Copies the systems API sources into the appendix of docs/systems.md (between the api-sources markers).
#   scripts/systems-api-docs.sh          rewrite the appendix
#   scripts/systems-api-docs.sh --check  fail if the appendix is out of date (CI)
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
python3 - "$repo" "${1:-}" <<'PY'
import pathlib, sys
repo = pathlib.Path(sys.argv[1])
check = sys.argv[2] == "--check"
api = repo / "amazake-server/src/main/java/core/amazake/systems/api"
order = ["AmazakeSystem.java", "SystemContext.java", "SystemInfo.java", "ServerStats.java", "package-info.java"]
files = order + sorted(p.name for p in api.glob("*.java") if p.name not in order)
parts = []
for name in files:
    text = (api / name).read_text(encoding="utf-8").rstrip("\n")
    parts.append(f"### {name}\n\n```java\n{text}\n```\n")
block = "<!-- api-sources:begin -->\n" + "\n".join(parts) + "<!-- api-sources:end -->"
doc = repo / "docs/systems.md"
current = doc.read_text(encoding="utf-8")
start = current.index("<!-- api-sources:begin -->")
end = current.index("<!-- api-sources:end -->") + len("<!-- api-sources:end -->")
updated = current[:start] + block + current[end:]
if check:
    if updated != current:
        sys.exit("docs/systems.md: the API appendix is out of date, run scripts/systems-api-docs.sh")
    print("docs/systems.md: the API appendix matches the sources")
else:
    doc.write_text(updated, encoding="utf-8")
    print("docs/systems.md: API appendix written")
PY
