#!/usr/bin/env bash
# Starts the built server once, runs a few commands and checks it answers as Amazake, then runs a short
# /amazake profile against the profiler site's backend (web/server) and fetches the report back.
#   scripts/smoke-test.sh path/to/amazake-paperclip.jar
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
jar=$(realpath "$1")
dir=$(mktemp -d)
site_port=18787
cd "$dir"

# The profiler site, as on a VPS but on localhost.
python3 "$repo/web/server/amazake_web.py" --host 127.0.0.1 --port "$site_port" \
  --data-dir "$dir/site-data" --static-dir "$repo/web/static" --min-free-mb 0 > site.log 2>&1 &
site_pid=$!
trap 'kill "$site_pid" 2>/dev/null || true' EXIT

echo "eula=true" > eula.txt
printf 'online-mode=false\nspawn-protection=0\nmax-players=1\n' > server.properties
mkfifo input
# Keep the fifo open for writing so the server never sees end-of-input.
exec 3<>input
java -Xmx2G -jar "$jar" --nogui < input > server.log 2>&1 &
pid=$!
for _ in $(seq 1 600); do
  grep -q 'Done (' server.log && break
  kill -0 "$pid" 2>/dev/null || { cat server.log; echo "The server stopped before it was ready"; exit 1; }
  sleep 1
done
grep -q 'Done (' server.log || { tail -50 server.log; echo "Not ready after 10 minutes"; exit 1; }

send() { echo "$1" >&3; sleep "${2:-3}"; }
send "version"
send "amazake version"
# Point the profiler at the local site, then reload the settings.
sed -i -E "s|^([[:space:]]*upload-url:).*|\1 http://127.0.0.1:$site_port|" amazake.yml
send "amazake reload"
send "spark tps"
send "amazake profile 5" 15
send "stop" 0
wait "$pid" || true
cat server.log
echo "---- site log ----"
cat site.log || true

echo "---- checks ----"
fail=0
check() { if grep -qE "$1" server.log; then echo "ok: $2"; else echo "MISSING: $2"; fail=1; fi; }
check 'This server is running Amazake' "/version names Amazake"
check 'Amazake 1\.21\.11' "/amazake version answers"
check 'Loaded amazake\.yml' "amazake.yml is read"
check 'Reloaded amazake\.yml' "/amazake reload works"
check 'TPS from last' "/spark works (bundled spark)"
check 'first time Amazake starts' "Amazake's first-start message"
if grep -qE 'papermc\.io/downloads|outdated version of Minecraft|docs\.papermc\.io/paper/next-steps' server.log; then
  echo "FOUND: a Paper download or onboarding message"; fail=1
else
  echo "ok: no Paper download or onboarding message"
fi
if [ -f amazake.yml ]; then echo "ok: amazake.yml written"; cat amazake.yml; else echo "MISSING: amazake.yml"; fail=1; fi

# The profile was uploaded: the server printed a /r/<id> link, and the site gives the report back.
id=$(grep -oE "127\.0\.0\.1:$site_port/r/[A-Za-z0-9]+" server.log | tail -1 | sed 's|.*/r/||' || true)
if [ -z "$id" ]; then
  echo "MISSING: /amazake profile uploaded a report (no /r/<id> link in the log)"; fail=1
else
  echo "ok: /amazake profile uploaded report $id"
  curl -fsS "http://127.0.0.1:$site_port/api/reports/$id" -o report.json
  if python3 - report.json <<'EOF'
import json, sys
r = json.load(open(sys.argv[1]))
assert r["format"] == "amazake-profile" and r["version"] == 1, "format"
assert r["server"]["brand"] == "Amazake", r["server"]["brand"]
assert len(r["samples"]) >= 3, "samples: %d" % len(r["samples"])
groups = {g["group"] for g in r["groups"]}
assert "main" in groups, "no main thread group: %s" % sorted(groups)
assert any(t["name"] == "Server thread" for t in r["threads"]), "no Server thread"
print("report: %d samples, %d threads, groups %s, %d cores, per-core %s" % (
    len(r["samples"]), len(r["threads"]), sorted(groups), r["system"]["logicalCores"], r["system"]["perCore"]))
EOF
  then echo "ok: the report is a valid Amazake profile"; else echo "BAD: the report is not what the contract says"; fail=1; fi
  if curl -fsS "http://127.0.0.1:$site_port/r/$id" | grep -qi '<html'; then echo "ok: /r/$id serves the viewer"; else echo "MISSING: viewer page"; fail=1; fi
fi
exit $fail
