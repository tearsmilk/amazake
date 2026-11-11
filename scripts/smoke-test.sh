#!/usr/bin/env bash
# Starts the built server once, runs a few commands and checks it answers as Amazake.
#   scripts/smoke-test.sh path/to/amazake-paperclip.jar
set -euo pipefail
jar=$(realpath "$1")
dir=$(mktemp -d)
cd "$dir"
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
for command in "version" "amazake version" "amazake reload" "spark tps" "stop"; do
  echo "$command" >&3
  sleep 3
done
wait "$pid" || true
cat server.log
echo "---- checks ----"
fail=0
check() { if grep -qE "$1" server.log; then echo "ok: $2"; else echo "MISSING: $2"; fail=1; fi; }
check 'This server is running Amazake' "/version names Amazake"
check 'Amazake 1\.21\.11' "/amazake version answers"
check 'Loaded amazake\.yml' "amazake.yml is read"
check 'Reloaded amazake\.yml' "/amazake reload works"
check 'TPS from last' "/spark works (bundled spark)"
check 'first time Amazake starts' "Amazake's first-start message"
if grep -qE 'papermc\.io/downloads|outdated version of Minecraft|docs\.papermc\.io/paper/next-steps' server.log; then echo "FOUND: a Paper download or onboarding message"; fail=1; else echo "ok: no Paper download or onboarding message"; fi
[ -f amazake.yml ] && echo "ok: amazake.yml written" && cat amazake.yml || { echo "MISSING: amazake.yml"; fail=1; }
exit $fail
