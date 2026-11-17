# Megane Diag

App di diagnostica **in sola lettura** per Renault Megane IV (TCe benzina) con R-Link2.
Si mostra sullo schermo R-Link2 via Android Auto. Non tocca il firmware R-Link2,
non scrive sulle centraline, non invia frame sul bus CAN.

## Cosa fa

- Dati live a schermo R-Link2: giri, temperature (acqua/olio/aria), turbo (boost reale
  da MAP - barometrica), carico, tensione 12V, fuel trim, consumo istantaneo.
- Griglia personalizzabile: scegli quali riquadri vedere.
- Codici guasto (DTC) con spiegazione in italiano.
- Log tensione 12V nel tempo, con riassunto diagnostico.
- Correlatore CAN passivo: isola l'ID che cambia tra "funziona" e "non funziona"
  (per il bug Sport mode intermittente).

## Cosa NON fa, per scelta

- Non scrive parametri sulle centraline (niente mappe potenza, niente "modalita RS").
- Non sblocca funzioni riservate ad altri allestimenti.
- Non invia frame CAN (nessun metodo di trasmissione esiste nel codice del logger).
- Non modifica la configurazione del quadro (logo GT, sound design): quella non ha
  "undo" da app. Va documentata e fatta sistemare in officina con Renault Clip.

## Struttura

```
core/     libreria Kotlin pura (JVM), testata. Decoder PID, catalogo, logger CAN, correlatore.
android/  app Android + Android Auto (Car App Library). Richiede Android SDK per compilare.
```

## Compilare

### core (libreria + test) — verificato
```bash
./gradlew :core:test
```
Nessun Android SDK necessario. 17 test, tutti verdi.

### android (APK) — serve Android SDK
Apri la cartella in **Android Studio** (che installa l'SDK da solo), oppure:
```bash
export ANDROID_HOME=/percorso/android-sdk
./gradlew :android:assembleDebug
```
APK in `android/build/outputs/apk/debug/`.

## Installare sul telefono (senza Play Store)

1. Installa l'APK: `adb install android-debug.apk`
2. Android Auto -> impostazioni -> tocca 10 volte "Versione" -> attiva modalita sviluppatore
3. Menu sviluppatore -> "Origini sconosciute" ON
4. Collega il telefono a R-Link2: l'app appare tra quelle Android Auto.

## Hardware OBD consigliato

- **vLinker MC+** (Bluetooth), ~45 EUR. Generico, standby basso.
- Per logging CAN serio: interfaccia **SLCAN** (canalyze / CANable), alimentata via USB,
  si spegne quando la stacchi. Supportata in listen-only (nessun ACK sul bus).

## Nota batteria

Il dongle e alimentato dal pin 16 OBD, sempre sotto tensione su Renault.
Per spegnimento totale: prolunga OBD con interruttore. L'app comunque non apre la
connessione a motore spento (vedi `EngineGate`), quindi non tiene sveglio l'adattatore.
