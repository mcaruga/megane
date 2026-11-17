#!/usr/bin/env bash
# Gira ogni minuto (cron). Fa tre cose:
#  1. aggiorna la copia server da GitHub (solo fast-forward, mai sovrascrive modifiche locali)
#     e se cambia docker/ riavvia il sito;
#  2. ogni nuovo tag vX.Y.Z su GitHub -> mette in coda la build;
#  3. esegue UNA build in coda -> diventa candidato da approvare su /rilasci/.
set -uo pipefail
umask 022
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
ST=docker/stato; REL=docker/releases
mkdir -p "$ST/coda" "$ST/log" "$ST/candidati"
exec 9>"$ST/.lock"; flock -n 9 || exit 0     # una build alla volta
ts() { date '+%F %T'; }

# --- 1. aggiorna da GitHub ---
if git fetch --quiet --tags --force --prune origin 2>"$ST/fetch.err"; then
  rm -f "$ST/fetch.err"
  if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ] \
     && git merge-base --is-ancestor HEAD origin/main \
     && [ -z "$(git status --porcelain --untracked-files=no)" ]; then
    CAMBIATI=$(git diff --name-only HEAD origin/main)
    git merge --quiet --ff-only origin/main && echo "$(ts) aggiornato a $(git rev-parse --short HEAD)"
    if grep -q '^docker/' <<<"$CAMBIATI"; then
      docker compose -f docker/docker-compose.yml up -d --build --remove-orphans >/dev/null 2>&1 \
        && echo "$(ts) sito ricostruito"
    fi
  fi
else
  echo "$(ts) git fetch fallito: $(head -c 200 "$ST/fetch.err")"
fi

# --- 2. nuovi tag -> coda ---
for TAG in $(git tag -l 'v[0-9]*.[0-9]*.[0-9]*'); do
  V="${TAG#v}"
  [[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || continue
  grep -q "^$V|" "$REL/versions.txt" 2>/dev/null && continue
  [ -d "$ST/candidati/$V" ] || [ -f "$ST/coda/$V.json" ] && continue
  grep -qx "$V" "$ST/rifiutate.txt" "$ST/fallite.txt" 2>/dev/null && continue
  NOTE=$(git tag -l --format='%(contents:subject)' "$TAG"); [ -z "$NOTE" ] && NOTE=$(git log -1 --format=%s "$TAG")
  python3 -c 'import json,sys,time; json.dump({"ver":sys.argv[1],"ref":sys.argv[2],"note":sys.argv[3],"fonte":"tag GitHub","chiesta":time.strftime("%Y-%m-%d %H:%M")}, open(sys.argv[4],"w"))' \
    "$V" "$TAG" "$NOTE" "$ST/coda/$V.json"
  echo "$(ts) nuovo tag $TAG in coda"
done

# --- 3. una build dalla coda ---
REQ=$(ls "$ST/coda/"*.json 2>/dev/null | sort -V | head -1)
[ -n "$REQ" ] || exit 0
V=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ver"])' "$REQ")
R=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("ref","main"))' "$REQ")
N=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("note",""))' "$REQ")
F=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("fonte",""))' "$REQ")
rm -f "$REQ"

stato() { python3 -c 'import json,sys,time; json.dump({"ver":sys.argv[1],"ref":sys.argv[2],"stato":sys.argv[3],"quando":time.strftime("%Y-%m-%d %H:%M:%S")}, open(sys.argv[4]+".tmp","w")); import os; os.replace(sys.argv[4]+".tmp",sys.argv[4])' "$V" "$R" "$1" "$ST/build.json"; }
echo "$(ts) build $V ($R) avviata"
stato in_corso
if docker/build.sh "$V" "$R" "$N" "$F" >"$ST/log/$V.log" 2>&1; then
  stato ok;     echo "$(ts) build $V ok: candidato pronto"
else
  stato errore; echo "$(ts) build $V FALLITA (log: $ST/log/$V.log)"
  echo "$V" >> "$ST/fallite.txt"
fi
