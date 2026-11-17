#!/usr/bin/env bash
# Compila un CANDIDATO (non pubblico). Lo approvi tu da /rilasci/.
# Uso:  docker/build.sh <X.Y.Z> [ref git] [note] [fonte]
#   ref: tag o branch (default: tag vX.Y.Z se esiste, se no main)
set -euo pipefail
umask 022
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VER="${1:-}"; REF="${2:-}"; NOTE="${3:-}"; FONTE="${4:-manuale}"
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "Uso: docker/build.sh <X.Y.Z> [ref] [note]"; exit 1; }

KS=docker/keystore
ST=docker/stato
REL=docker/releases
mkdir -p "$ST/candidati" "$ST/log" "$REL/archivio"

grep -q "^$VER|" "$REL/versions.txt" 2>/dev/null && { echo "$VER gia pubblicata."; exit 1; }
[ -d "$ST/candidati/$VER" ] && { echo "Candidato $VER esiste gia."; exit 1; }
[ -s "$KS/release.p12" ] && [ -s "$KS/ks.pass" ] || { echo "Manca la chiave in $KS/"; exit 1; }

git fetch --quiet --tags --force origin 2>/dev/null || echo "ATTENZIONE: git fetch fallito, uso copia locale"

if [ -z "$REF" ]; then
  if git rev-parse -q --verify "refs/tags/v$VER" >/dev/null; then REF="v$VER"; else REF="main"; fi
fi
[[ "$REF" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ && "$REF" != *..* ]] || { echo "Ref non valido: $REF"; exit 1; }
COMMIT=$(git rev-parse -q --verify "refs/tags/$REF^{commit}" 2>/dev/null \
      || git rev-parse -q --verify "refs/remotes/origin/$REF^{commit}" 2>/dev/null \
      || git rev-parse -q --verify "$REF^{commit}") || { echo "Ref $REF non trovato"; exit 1; }
[ -z "$NOTE" ] && NOTE=$(git log -1 --format=%s "$COMMIT")

echo "== Build $VER da $REF (${COMMIT:0:8}) =="
SRC=$(mktemp -d); OUT=$(mktemp -d "$ST/candidati/.tmp-XXXX")
IMG="megane-builder:$VER"
pulisci() { rm -rf "$SRC" "$OUT"; docker rm -f "megane-cp-$VER" >/dev/null 2>&1 || true; }
trap pulisci EXIT
git archive "$COMMIT" | tar -x -C "$SRC"

# Dockerfile della copia server (sempre aggiornato), sorgenti del commit scelto.
DOCKER_BUILDKIT=1 docker build -f "$ROOT/docker/Dockerfile" --target builder \
  --build-arg APP_VERSION="$VER" \
  --secret id=ks,src="$ROOT/$KS/release.p12" \
  --secret id=kspass,src="$ROOT/$KS/ks.pass" \
  -t "$IMG" "$SRC"

docker create --name "megane-cp-$VER" "$IMG" >/dev/null
docker cp "megane-cp-$VER:/src/android/build/outputs/apk/debug/android-debug.apk" "$OUT/megane-diag-$VER.apk"
docker cp "megane-cp-$VER:/src/desktop/build/libs/megane-desktop.jar" "$OUT/megane-desktop-$VER.jar"
docker rm -f "megane-cp-$VER" >/dev/null
docker rmi "$IMG" >/dev/null 2>&1 || true

SHA=$(sha256sum "$OUT/megane-diag-$VER.apk" | cut -d' ' -f1)
SHAJ=$(sha256sum "$OUT/megane-desktop-$VER.jar" | cut -d' ' -f1)
python3 - "$OUT/meta.json" "$VER" "$REF" "$COMMIT" "$NOTE" "$SHA" "$SHAJ" "$FONTE" <<'EOF'
import json, sys, time
p, ver, ref, commit, note, sha, shaj, fonte = sys.argv[1:]
json.dump({"ver": ver, "ref": ref, "commit": commit, "note": note, "sha": sha,
           "sha_jar": shaj, "fonte": fonte, "data": time.strftime("%Y-%m-%d %H:%M")},
          open(p, "w"))
EOF
chmod 755 "$OUT"; chmod 644 "$OUT"/*
mv "$OUT" "$ST/candidati/$VER"
echo "== Candidato $VER pronto. APK sha256: $SHA =="
echo "   Approva su /rilasci/ (megane.omniking.cc/rilasci/)"
