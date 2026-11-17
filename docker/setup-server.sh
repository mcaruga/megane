#!/usr/bin/env bash
# Installa tutto sul server: chiave di firma, sito, API gestionale, watcher (cron).
# Uso:  cd ~/megane && docker/setup-server.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

command -v docker >/dev/null || { echo "Installa Docker: curl -fsSL https://get.docker.com | sudo sh"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Manca 'docker compose' (v2)."; exit 1; }

mkdir -p docker/releases/archivio docker/stato/candidati docker/stato/coda docker/stato/log
[ -f docker/htpasswd ] || docker/cambia-password.sh

KS=docker/keystore
if [ ! -f "$KS/release.p12" ]; then
  echo "== Creo la chiave di firma (una volta sola) =="
  mkdir -p "$KS"; chmod 700 "$KS"
  openssl rand -base64 24 | tr -d '=+/' | cut -c1-24 > "$KS/ks.pass"; chmod 600 "$KS/ks.pass"
  docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT/$KS:/ks" eclipse-temurin:17-jdk keytool -genkeypair \
    -keystore /ks/release.p12 -storetype PKCS12 \
    -storepass "$(cat "$KS/ks.pass")" -keypass "$(cat "$KS/ks.pass")" \
    -alias megane -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Megane Diag"
  chmod 600 "$KS/release.p12"
  base64 -w0 "$KS/release.p12" > "$KS/KEYSTORE_B64.txt"; chmod 600 "$KS/KEYSTORE_B64.txt"
  echo "!! FAI UN BACKUP di $KS/ fuori dal server."
fi

echo "== Avvio sito + API =="
docker compose -f docker/docker-compose.yml up -d --build --remove-orphans

LINE="* * * * * $ROOT/docker/watcher.sh >> $ROOT/docker/stato/watcher.log 2>&1"
( crontab -l 2>/dev/null | grep -vF "$ROOT/docker/watcher.sh"; echo "$LINE" ) | crontab -
echo "== Watcher in cron (ogni minuto) =="

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo "Sito LAN:    http://${IP}:8484"
echo "Gestionale:  http://${IP}:8484/rilasci/   (utente admin)"
echo "Internet:    https://megane.omniking.cc  (Cloudflare tunnel -> http://localhost:8484)"
