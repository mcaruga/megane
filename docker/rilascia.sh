#!/usr/bin/env bash
# Compila APK + JAR e pubblica una nuova versione sul sito.
# Uso:  docker/rilascia.sh 1.2.0 "Cosa cambia"
set -euo pipefail
cd "$(dirname "$0")/.."

VER="${1:-}"
NOTE="${2:-}"
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "Uso: docker/rilascia.sh <X.Y.Z> \"note\"   (es. 1.2.0)"; exit 1; }
NOTE="${NOTE//|//}"            # '|' e il separatore di versions.txt
NOTE="${NOTE//$'\n'/ }"

REL=docker/releases
KS=docker/keystore
mkdir -p "$REL/archivio"

if grep -q "^$VER|" "$REL/versions.txt" 2>/dev/null; then
  echo "La versione $VER esiste gia. Usa un numero nuovo."; exit 1
fi

# Chiave di firma STABILE: senza, ogni build ha una chiave diversa e Android
# rifiuta l'aggiornamento sopra la versione gia installata.
if [ ! -f "$KS/release.p12" ]; then
  echo "== Creo la chiave di firma (una volta sola) =="
  mkdir -p "$KS"
  openssl rand -base64 24 | tr -d '=+/' | cut -c1-24 > "$KS/ks.pass"
  chmod 600 "$KS/ks.pass"
  docker run --rm -v "$PWD/$KS:/ks" eclipse-temurin:17-jdk keytool -genkeypair \
    -keystore /ks/release.p12 -storetype PKCS12 \
    -storepass "$(cat "$KS/ks.pass")" -keypass "$(cat "$KS/ks.pass")" \
    -alias megane -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Megane Diag"
  echo "!! FAI UN BACKUP di $KS/ : se la perdi, gli utenti devono disinstallare l'app per aggiornare."
fi

echo "== Compilo $VER (la prima volta scarica l'SDK Android: 5-15 min) =="
docker build -f docker/Dockerfile --target builder \
  --build-arg APP_VERSION="$VER" -t "megane-builder:$VER" .

CID=$(docker create "megane-builder:$VER")
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

mkdir -p "$REL/archivio/$VER"
APK="$REL/archivio/$VER/megane-diag-$VER.apk"
JAR="$REL/archivio/$VER/megane-desktop-$VER.jar"
docker cp "$CID:/src/android/build/outputs/apk/debug/android-debug.apk" "$APK"
docker cp "$CID:/src/desktop/build/libs/megane-desktop.jar" "$JAR"

# pubblica: copia "ultima versione" + riga nell'elenco (visibile sul sito subito)
cp "$APK" "$REL/megane-diag.apk"
cp "$JAR" "$REL/megane-desktop.jar"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
echo "$VER|$(date +%F)|$NOTE|$SHA" >> "$REL/versions.txt"

echo "== Rilasciata $VER =="
echo "   APK  sha256: $SHA"
echo "   Pagina: http://localhost:8080  |  Gestionale: /rilasci/"
