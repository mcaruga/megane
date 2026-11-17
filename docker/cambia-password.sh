#!/usr/bin/env bash
# Imposta la password del gestionale rilasci (utente: admin).
# Attiva subito: nginx rilegge il file a ogni richiesta, nessun riavvio.
set -euo pipefail
cd "$(dirname "$0")"

read -rsp "Nuova password (min 10 caratteri): " P1; echo
read -rsp "Ripeti la password: " P2; echo
[ "$P1" = "$P2" ] || { echo "Le password non coincidono."; exit 1; }
[ "${#P1}" -ge 10 ] || { echo "Troppo corta (minimo 10 caratteri)."; exit 1; }

printf 'admin:%s\n' "$(openssl passwd -apr1 -stdin <<<"$P1")" > htpasswd
chmod 644 htpasswd
echo "Password aggiornata (utente: admin)."
