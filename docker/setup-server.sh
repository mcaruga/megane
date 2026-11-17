#!/usr/bin/env bash
# Installa tutto sul server: Docker, sito, prima compilazione e rilascio.
# Uso:  cd megane-diag && docker/setup-server.sh
set -euo pipefail
cd "$(dirname "$0")/.."

SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

if ! command -v docker >/dev/null 2>&1; then
  echo "== Installo Docker (script ufficiale get.docker.com) =="
  curl -fsSL https://get.docker.com | $SUDO sh
  $SUDO usermod -aG docker "$USER" || true
  echo "Docker installato. Se i comandi seguenti danno 'permission denied':"
  echo "esci e rientra in SSH (gruppo docker), poi rilancia questo script."
fi
docker compose version >/dev/null 2>&1 || {
  echo "Manca il plugin 'docker compose' (v2). Installa docker-compose-plugin."; exit 1; }

# la build dell'APK scarica l'SDK da Google: il server deve raggiungerlo
curl -fsI "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
  >/dev/null || { echo "Il server non raggiunge dl.google.com (serve per l'SDK Android)."; exit 1; }

mkdir -p docker/releases/archivio
[ -f docker/htpasswd ] || docker/cambia-password.sh

echo "== Avvio il sito =="
docker compose -f docker/docker-compose.yml up -d --build

if [ ! -s docker/releases/versions.txt ]; then
  docker/rilascia.sh 1.0.0 "Prima versione"
fi

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo
echo "Sito in LAN:  http://${IP:-IP-SERVER}:8080"
echo "Gestionale:   http://${IP:-IP-SERVER}:8080/rilasci/   (utente: admin)"
echo "Dominio:      https://meganegt.omniking.cc  -> serve DNS + reverse proxy (docker/README.md)"
