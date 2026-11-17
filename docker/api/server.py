"""API del gestionale rilasci. Solo libreria standard.

Gira dietro nginx: auth_basic la fa nginx, qui arrivano solo richieste autenticate.
Dati:
  /releases  -> docker/releases  (pubblico: ultima versione, archivio, versions.txt)
  /stato     -> docker/stato     (privato: candidati, coda build, log)
Le build le fa docker/watcher.sh sull'host (questa API non tocca Docker).
"""
import base64
import crypt
import hmac
import json
import os
import re
import shutil
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REL = "/releases"
ST = "/stato"
CAND = os.path.join(ST, "candidati")
CODA = os.path.join(ST, "coda")
HTPASSWD = "/auth/htpasswd"
VER_RE = re.compile(r"^\d+\.\d+\.\d+$")
REF_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$")


def leggi(path, default=""):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError:
        return default


def scrivi_atomico(path, testo):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(testo)
    os.replace(tmp, path)


def pubblicate():
    out = []
    for riga in leggi(os.path.join(REL, "versions.txt")).splitlines():
        p = riga.split("|")
        if p and p[0]:
            out.append({"ver": p[0], "data": p[1] if len(p) > 1 else "",
                        "note": p[2] if len(p) > 2 else "", "sha": p[3] if len(p) > 3 else ""})
    return out


def candidati():
    out = []
    if os.path.isdir(CAND):
        for v in sorted(os.listdir(CAND)):
            meta = os.path.join(CAND, v, "meta.json")
            if VER_RE.match(v) and os.path.isfile(meta):
                try:
                    out.append(json.loads(leggi(meta)))
                except ValueError:
                    pass
    return out


def coda():
    out = []
    if os.path.isdir(CODA):
        for f in sorted(os.listdir(CODA)):
            if f.endswith(".json"):
                try:
                    out.append(json.loads(leggi(os.path.join(CODA, f))))
                except ValueError:
                    pass
    return out


def stato():
    build = {}
    try:
        build = json.loads(leggi(os.path.join(ST, "build.json"), "{}"))
    except ValueError:
        pass
    log = ""
    if build.get("ver") and VER_RE.match(build["ver"]):
        log = leggi(os.path.join(ST, "log", build["ver"] + ".log"))[-6000:]
    return {"pubblicate": pubblicate(), "candidati": candidati(), "coda": coda(),
            "build": build, "log": log,
            "rifiutate": leggi(os.path.join(ST, "rifiutate.txt")).split()}


def approva(ver):
    if any(p["ver"] == ver for p in pubblicate()):
        return 409, "Versione gia pubblicata"
    src = os.path.join(CAND, ver)
    meta_p = os.path.join(src, "meta.json")
    if not os.path.isfile(meta_p):
        return 404, "Candidato non trovato"
    meta = json.loads(leggi(meta_p))
    apk = os.path.join(src, f"megane-diag-{ver}.apk")
    jar = os.path.join(src, f"megane-desktop-{ver}.jar")
    if not (os.path.isfile(apk) and os.path.isfile(jar)):
        return 409, "File mancanti nel candidato"
    dst = os.path.join(REL, "archivio", ver)
    os.makedirs(dst, exist_ok=True)
    for f in (apk, jar):
        shutil.copy2(f, dst)
    # "ultima versione": copia + rename atomico, chi scarica non vede mai file a meta
    for f, nome in ((apk, "megane-diag.apk"), (jar, "megane-desktop.jar")):
        tmp = os.path.join(REL, "." + nome + ".tmp")
        shutil.copy2(f, tmp)
        os.replace(tmp, os.path.join(REL, nome))
    note = str(meta.get("note", "")).replace("|", "/").replace("\n", " ")
    riga = f"{ver}|{time.strftime('%Y-%m-%d')}|{note}|{meta.get('sha', '')}\n"
    with open(os.path.join(REL, "versions.txt"), "a", encoding="utf-8") as f:
        f.write(riga)
    shutil.rmtree(src, ignore_errors=True)
    return 200, f"Pubblicata {ver}"


def rifiuta(ver):
    src = os.path.join(CAND, ver)
    if not os.path.isdir(src):
        return 404, "Candidato non trovato"
    shutil.rmtree(src, ignore_errors=True)
    # il watcher non ricompila piu da solo questo tag
    with open(os.path.join(ST, "rifiutate.txt"), "a", encoding="utf-8") as f:
        f.write(ver + "\n")
    return 200, f"Scartata {ver}"


def chiedi_build(ver, ref, note):
    if any(p["ver"] == ver for p in pubblicate()):
        return 409, "Versione gia pubblicata: usa un numero nuovo"
    if os.path.isdir(os.path.join(CAND, ver)):
        return 409, "Esiste gia un candidato con questo numero"
    os.makedirs(CODA, exist_ok=True)
    req = {"ver": ver, "ref": ref, "note": note[:300], "fonte": "gestionale",
           "chiesta": time.strftime("%Y-%m-%d %H:%M")}
    scrivi_atomico(os.path.join(CODA, ver + ".json"), json.dumps(req))
    return 200, f"Build {ver} in coda (parte entro 1 minuto)"


def cambia_password(auth_header, vecchia, nuova):
    # nginx ha gia verificato l'header Basic: confronto la vecchia con quella usata ora
    try:
        utente, _, attuale = base64.b64decode(auth_header.split(" ", 1)[1]).decode().partition(":")
    except (IndexError, ValueError, UnicodeDecodeError):
        return 401, "Credenziali mancanti"
    if not hmac.compare_digest(vecchia.encode(), attuale.encode()):
        return 403, "Password attuale sbagliata"
    if len(nuova) < 10:
        return 400, "Nuova password troppo corta (minimo 10 caratteri)"
    if ":" in nuova or "\n" in nuova:
        return 400, "Caratteri non ammessi"
    riga = f"{utente}:{crypt.crypt(nuova, crypt.mksalt(crypt.METHOD_SHA512))}\n"
    # scrittura sul posto: il file e un bind mount singolo, un rename non arriverebbe a nginx
    with open(HTPASSWD, "r+", encoding="utf-8") as f:
        f.seek(0)
        f.write(riga)
        f.truncate()
    return 200, "Password cambiata. Il browser ti chiedera di nuovo l'accesso."


class H(BaseHTTPRequestHandler):
    server_version = "megane-api"
    sys_version = ""

    def _json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.split("?")[0] == "/stato":
            return self._json(200, stato())
        self._json(404, {"msg": "?"})

    def do_POST(self):
        # anti-CSRF: header custom = il browser non lo manda cross-site senza preflight
        if self.headers.get("X-Megane") != "1":
            return self._json(403, {"msg": "Richiesta non valida"})
        try:
            n = min(int(self.headers.get("Content-Length") or 0), 4096)
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self._json(400, {"msg": "JSON non valido"})
        if self.path == "/password":
            code, msg = cambia_password(self.headers.get("Authorization", ""),
                                        str(body.get("vecchia", "")), str(body.get("nuova", "")))
            return self._json(code, {"msg": msg})
        ver = str(body.get("ver", ""))
        if not VER_RE.match(ver):
            return self._json(400, {"msg": "Versione non valida (X.Y.Z)"})
        if self.path == "/approva":
            code, msg = approva(ver)
        elif self.path == "/rifiuta":
            code, msg = rifiuta(ver)
        elif self.path == "/build":
            ref = str(body.get("ref") or "main")
            if not REF_RE.match(ref) or ".." in ref:
                return self._json(400, {"msg": "Ref non valido"})
            code, msg = chiedi_build(ver, ref, str(body.get("note", "")))
        else:
            code, msg = 404, "?"
        self._json(code, {"msg": msg})

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    os.umask(0o022)
    for d in (CAND, CODA, os.path.join(REL, "archivio")):
        os.makedirs(d, exist_ok=True)
    ThreadingHTTPServer(("0.0.0.0", 8000), H).serve_forever()
