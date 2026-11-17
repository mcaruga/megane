# Deploy su server Linux (meganegt.omniking.cc)

Sito di download + gestionale rilasci con password. APK e JAR si compilano
SUL TUO SERVER (serve l'accesso a dl.google.com, bloccato in cloud).

## Installare tutto (un comando)
Copia il progetto sul server, poi:
```bash
cd megane-diag
docker/setup-server.sh
```
Fa: installa Docker se manca, avvia il sito, compila e pubblica la 1.0.0.
Prima compilazione 5-15 min (scarica l'SDK Android).
Sito: `http://IP-SERVER:8080` - gestionale: `http://IP-SERVER:8080/rilasci/`

## Gestionale rilasci (password)
- Utente `admin`. Protegge `/rilasci/` e l'archivio `/downloads/archivio/`.
- L'ultima versione resta scaricabile da tutti.
- Cambiare password (attiva subito, nessun riavvio):
```bash
docker/cambia-password.sh
```
- Anti brute-force: 10 tentativi/minuto per IP.

## Pubblicare una nuova versione
```bash
docker/rilascia.sh 1.1.0 "Cosa cambia"
```
Compila APK + JAR, li archivia in `docker/releases/archivio/1.1.0/`, aggiorna
"ultima versione" e l'elenco. Nessun riavvio del sito.
Il numero deve essere nuovo (X.Y.Z). Le build successive alla prima sono veloci (cache).

## Chiave di firma - FAI UN BACKUP
`docker/keystore/` (creata alla prima release) firma l'APK. Se la perdi, chi ha
l'app installata deve disinstallarla prima di aggiornare. Copiala altrove.

## HTTPS su meganegt.omniking.cc
Il container parla HTTP sulla 8080. Davanti serve un reverse proxy con
certificato Let's Encrypt, e un record DNS `A` di `meganegt` verso l'IP pubblico.
Esempio Caddy:
```
meganegt.omniking.cc {
    reverse_proxy localhost:8080
}
```
Su IP privato (192.168.x.x) niente HTTPS automatico: resti in HTTP in LAN.

## Se la build fallisce
- Il server vede Google?  `curl -I https://dl.google.com`
- Dietro proxy: passa HTTP_PROXY/HTTPS_PROXY a `docker build`.
- Log sito: `docker compose -f docker/docker-compose.yml logs`
- "permission denied" su docker: `sudo usermod -aG docker $USER` e rientra in SSH.
