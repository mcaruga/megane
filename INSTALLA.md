# Come installare Megane Diag

Due app, due strade diverse. Leggi la tua.

---

## macOS (e Linux / Windows) — PRONTA

Il file `megane-desktop.jar` e gia compilato. Non devi costruire niente.

### 1. Serve Java (una volta sola)
Apri il Terminale e controlla:
```bash
java -version
```
Se dice "command not found", installa Java:
```bash
brew install temurin        # con Homebrew
```
oppure scarica "Temurin 21" da adoptium.net e installa il .pkg.

### 2. Prova che funziona (senza auto)
```bash
java -jar megane-desktop.jar --selftest
```
Deve stampare "SELFTEST OK".

### 3. Vedi le porte del dongle
Accoppia prima il **vLinker MC+** in Bluetooth (Preferenze Sistema -> Bluetooth).
Poi:
```bash
java -jar megane-desktop.jar --porte
```
Deve comparire una porta tipo `/dev/tty.vLinkerMC...`.

### 4. Avvia l'app
```bash
java -jar megane-desktop.jar
```
Si apre la finestra: scegli la porta, premi Connetti. A motore acceso vedi i dati.

### (facoltativo) Trasformarla in una vera app .app / .dmg
Da fare SULLA TUA macchina macOS, con Java installato:
```bash
jpackage --input . --name "Megane Diag" \
  --main-jar megane-desktop.jar \
  --main-class cafe.megane.desktop.MainKt \
  --type dmg
```
Ottieni un installer .dmg come una app normale.

---

## Android (telefono) — DA COMPILARE

L'APK NON e incluso: va compilato una volta con Android Studio.
Non si puo compilare in cloud perche il toolchain Android di Google e bloccato.

### 1. Installa Android Studio
Gratis da developer.android.com/studio. Installa anche l'SDK quando lo chiede.

### 2. Apri il progetto
Android Studio -> Open -> scegli la cartella `megane-diag`.
Aspetta che finisca "Gradle sync" (scarica da solo le librerie).

### 3. Compila l'APK
Menu: **Build -> Build Bundle(s)/APK(s) -> Build APK(s)**.
L'APK esce in:
```
android/build/outputs/apk/debug/android-debug.apk
```

### 4. Installa sul telefono
Collega il telefono via USB (con "debug USB" attivo), poi:
```bash
adb install android/build/outputs/apk/debug/android-debug.apk
```
oppure copia l'APK sul telefono e aprilo.

### 5. Falla apparire su R-Link2
- Android Auto -> Impostazioni -> tocca 10 volte "Versione" (attiva modalita sviluppatore)
- Menu sviluppatore -> "Origini sconosciute" ON
- Collega il telefono a R-Link2: l'app appare tra quelle Android Auto.

---

## Hardware da comprare

| Cosa | A cosa serve | Costo |
|---|---|---|
| vLinker MC+ **Bluetooth** | leggere l'auto | ~45 EUR |
| (gia tuo) smartphone Android o Mac | far girare l'app | - |

NON WiFi: il WiFi del dongle litiga con Android Auto.
Per il logger CAN serio: interfaccia SLCAN (canalyze/CANable) via USB.
