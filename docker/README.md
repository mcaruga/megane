# Server di build e rilascio (megane.omniking.cc)

Tutto gira sul server di casa (192.168.1.11). Build di APK + JAR **qui**,
pubblicazione **solo dopo la tua approvazione** dal gestionale con password.

## Come funziona
```
git tag v1.0.1 && git push origin v1.0.1      (oppure: pulsante "Avvia build" nel gestionale)
        |
docker/watcher.sh (cron, ogni minuto): git fetch -> build -> CANDIDATO (privato)
        |
https://megane.omniking.cc/rilasci/  -> scarichi, provi, "Approva"  -> pubblico per tutti
```
- Il watcher tiene aggiornata anche la copia server (`git merge --ff-only`, mai sovrascrive
  modifiche locali) e, se cambia `docker/`, ricostruisce il sito.
- Build fallita: il log compare nel gestionale. Lo stesso tag non viene ritentato da solo;
  rilancialo dal pulsante "Avvia build".

## Installare (una volta)
```bash
cd ~/megane && docker/setup-server.sh
```
Crea chiave di firma (se manca), avvia sito + API, mette il watcher in cron.

Cloudflare Zero Trust -> Tunnels -> tunnel omniking -> Public Hostname:
`megane.omniking.cc` -> `http://localhost:8484`.

## Comandi utili
| Cosa | Comando |
|---|---|
| Build subito, da terminale | `docker/rilascia.sh 1.1.0 "Cosa cambia"` |
| Cambiare password gestionale | `docker/cambia-password.sh` |
| Log watcher | `tail -f docker/stato/watcher.log` |
| Log sito | `docker compose -f docker/docker-compose.yml logs` |

## Repo privato
Il server legge GitHub con una deploy key (`~/.ssh/megane_deploy`, host `github-megane`).
Remote: `git@github-megane:mcaruga/megane.git`.

## Chiave di firma - FAI UN BACKUP
`docker/keystore/` (mai nel repo, mai nelle immagini Docker: passa come BuildKit secret).
Se la perdi, chi ha l'app installata deve disinstallarla per aggiornare.
Gli stessi valori vanno nei Secrets GitHub (`KEYSTORE_B64`, `KEYSTORE_PASS`), cosi anche
le build di GitHub Actions si installano sopra quelle del server.

## Cartelle
- `docker/releases/` pubblico: ultima versione, `archivio/` (con password), `versions.txt`
- `docker/stato/` privato: `candidati/`, `coda/`, `log/`, `build.json`
